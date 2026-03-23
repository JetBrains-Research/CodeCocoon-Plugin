package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.TransformationStepFailed
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation.Companion.withReadAction
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.MoveFileIntoSuggestedDirectoryTransformation.Companion.withAI
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.MoveFileIntoSuggestedDirectoryTransformation.Companion.withConfig
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.java.JavaTransformation
import com.github.pderakhshanfar.codecocoonplugin.suggestions.SuggestionsApi
import com.github.pderakhshanfar.codecocoonplugin.transformation.require
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.usageView.UsageInfo
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture


/**
 * A transformation that moves a given file to a directory either 1) suggested by AI
 * or 2) provided in the config.
 * The target directory is determined dynamically based on the given strategy.
 *
 * **IMPORTANT: the transformation [id] is defined based on the given directory suggestion strategy:**
 * 1. When AI used: [AI.ID]
 * 2. When config used: [Config.ID]
 *
 * The config schema depends on the selected [DirectorySuggestionApi].
 *
 * I. When [DirectorySuggestionApi.AI] is used, the schema is empty:
 * ```yaml
 * config: # empty!
 * ```
 *
 * II. When [DirectorySuggestionApi.Config] is used, the config schema is:
 * ```yaml
 * config:
 *   destination: string # required, a directory (absolute or relative to the project root) where the file should be moved to (new or existing)
 * ```
 *
 * @see withAI
 * @see withConfig
 */
class MoveFileIntoSuggestedDirectoryTransformation private constructor(
    override val id: String,
    override val config: Map<String, Any>,
    private val directorySuggestionApi: DirectorySuggestionApi,
) : JavaTransformation, SelfManagedTransformation() {
    override val description = "Places the given Java file into a directory suggested by AI"

    private val logger = thisLogger().withStdout()

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile,
    ): TransformationResult {
        // validate that this is a Java file
        if (psiFile !is PsiJavaFile) {
            return TransformationResult.Failure("File ${virtualFile.path} is not a Java file")
        }

        return try {
            logger.info("  ⏲ Retrieving suggestion directories for ${virtualFile.name}...")
            val result = directorySuggestionApi.suggest(psiFile, virtualFile)

            if (result.isFailure) {
                logger.error("    ✗ Failed to get directory suggestions")
                return TransformationResult.Failure(
                    "Failed to get directory suggestions",
                    result.exceptionOrNull(),
                )
            }

            val suggestedDirectories = result.getOrThrow()
            logger.info("    • Received ${suggestedDirectories.size} directory suggestions")

            return tryToMoveFileIntoSuggestedDirectory(
                project = psiFile.project,
                fileToMove = psiFile,
                suggestions = suggestedDirectories,
            )
        } catch (e: Exception) {
            logger.error("  ✗ Failed to move file ${virtualFile.name}", e)
            TransformationResult.Failure("Failed to move file ${virtualFile.path}: ${e.message}", e)
        }
    }

    fun tryToMoveFileIntoSuggestedDirectory(
        project: Project,
        fileToMove: PsiFile,
        suggestions: List<String>
    ): TransformationResult {
        val filename = withReadAction { fileToMove.name }

        if (fileToMove !is PsiJavaFile) {
            return TransformationResult.Failure("Cannot move $filename: Not a Java file")
        }
        if (suggestions.isEmpty()) {
            return TransformationResult.Failure("Cannot move $filename: No directory suggestions received")
        }

        val fileIndex = ProjectFileIndex.getInstance(project)
        val isInTestSourceContent = withReadAction {
            fileIndex.isInTestSourceContent(fileToMove.virtualFile)
        }
        if (isInTestSourceContent) {
            return TransformationResult.Skipped(
                "Cannot move $filename: It is located under a test source directory and likely a test file")
        }

        // if any package-local class is in use by other classes within the same package,
        // we cannot move the file into another directory. Otherwise, it breaks these other classes.
        if (packageLocalClassesInUseByOtherFiles(fileToMove)) {
            return TransformationResult.Skipped("Cannot move $filename: Package-local classes are in use by other files")
        }

        val projectRoot = project.basePath
            ?: return TransformationResult.Failure("Project root not found")

        logger.info("  ⏲ Attempting to move $filename into suggestions...")
        for ((index, suggestionPath) in suggestions.withIndex()) {
            logger.info("    ↳ Attempting suggestion #${index + 1}: '$suggestionPath'")

            val isRelative = !Paths.get(suggestionPath).isAbsolute
            val suggestion = if (isRelative) {
                logger.info("    ↳ The suggested path is relative, prepending project root: '$projectRoot'")
                val absolute = Paths.get(projectRoot, suggestionPath).toString()
                logger.info("    ↳ Converted to absolute path: '$absolute'")
                absolute
            } else {
                // the suggested path is already absolute, no need to prepend project root
                suggestionPath
            }

            val suggestedDirectory = WriteCommandAction.runWriteCommandAction<VirtualFile>(project) {
                VfsUtil.createDirectories(suggestion)
            }
            val where = withReadAction {
                PsiManager.getInstance(project).findDirectory(suggestedDirectory)
            } ?: continue

            val successfullyMoved = CompletableFuture<Boolean>()
            val processor = withReadAction {
                MoveFilesOrDirectoriesProcessorWrapper(
                    project = project,
                    elements = arrayOf(fileToMove),
                    newParent = where,
                    searchInComments = false,
                    searchInNonJavaFiles = false,
                    moveCallback = {
                        // overriding `refactoringCompleted` method
                        successfullyMoved.complete(true)
                    },
                    prepareSuccessfulCallback = { /* no-op */ },
                )
            }

            try {
                ApplicationManager.getApplication().invokeAndWait {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                    processor.run()
                }
            } catch (err: ProcessCanceledException) {
                // NOTE: `ProcessCanceledException` cannot be silenced, see its Javadoc
                throw err
            } catch (err: Exception) {
                logger.error("Failed to move '$filename' into suggestion #${index + 1}", err)
            }

            // finish when moved successfully into the current suggestion
            if (successfullyMoved.join()) {
                val (filesModified, usageSummary) = withReadAction {
                    val usages = processor.foundUsages

                    val modifiedFiles = usages.values.flatten().toSet().size
                    val summary = buildString {
                        for ((index, entry) in usages.entries.withIndex()) {
                            val (which, usageInfo) = entry
                            // the same file can appear multiple times, thus we count the number of usages
                            val where = usageInfo
                                .mapNotNull { it.virtualFile?.name }
                                .groupingBy { it }
                                .eachCount()
                                .toList()

                            appendLine("    ${index+1}. ${which.name} is used in ${where.size} place(s):")
                            for ((usageIndex, usage) in where.withIndex()) {
                                val (filename, count) = usage
                                appendLine("      ${usageIndex + 1}. $filename: $count time${if (count > 1) "s" else ""}")
                            }
                        }
                    }

                    modifiedFiles to summary
                }

                return TransformationResult.Success(
                    message = "Successfully moved $filename into $suggestion.\n  Usage Summary:\n$usageSummary",
                    filesModified,
                )
            }
        }

        // no suggestions fit
        logger.info("    ✗ Failed to move $filename: None of ${suggestions.size} suggestions fit")
        return TransformationResult.Failure(
            "Failed to move $filename into any of ${suggestions.size} suggested directories:\n${suggestions.joinToString("\n") { "  - $it" }}")
    }

    /**
     * Checks if any package-local classes in the given Java file are used by other files (i.e., **not the file they are defined at**).
     *
     * @param javaFile The Java file to analyze for package-local classes and their usage.
     * @return `true` if any package-local class in the file is referenced by other files (i.e., not in [javaFile]), otherwise `false`.
     */
    private fun packageLocalClassesInUseByOtherFiles(javaFile: PsiJavaFile): Boolean = withReadAction {
        // find all package-local classes in the file
        val packageLocalClasses = buildList<PsiClass> {
            for (clazz in javaFile.classes) {
                // A class is package-local if it has no explicit access modifier
                // (not public, protected, or private)
                val isPackageLocal = !clazz.hasModifierProperty(PsiModifier.PUBLIC) &&
                        !clazz.hasModifierProperty(PsiModifier.PROTECTED) &&
                        !clazz.hasModifierProperty(PsiModifier.PRIVATE)

                if (isPackageLocal) {
                    add(clazz)
                }
            }
        }
        val classNames = packageLocalClasses.mapNotNull { it.name }
        logger.info("    ↳ Found ${packageLocalClasses.size} package-local classes in ${javaFile.name}: ${classNames.joinToString()}")

        // check if any package-local class is used by other files
        val references = buildMap<PsiClass, List<PsiReference>> {
            for (clazz in packageLocalClasses) {
                val references = ReferencesSearch.search(clazz).findAll()
                val refsFromOtherFiles = references.filter { ref ->
                    // check if the reference is from a different file
                    ref.element.containingFile != javaFile
                }
                put(clazz, refsFromOtherFiles)
            }
        }

        val packageLocalClassesInUseByOtherFiles = references.any { (_, refs) -> refs.isNotEmpty() }
        if (packageLocalClassesInUseByOtherFiles) {
            // logging for transparency
            logger.info("    ⚠ Some package-local classes are in use by other files:")
            var index = 0
            for ((clazz, refs) in references) {
                if (refs.isNotEmpty()) {
                    val fileNames = refs.mapNotNull { it.element.containingFile?.name }
                    logger.info("       ${index+1}) `${clazz.name}` class referenced in ${refs.size} files: ${fileNames.joinToString()}")
                    index += 1
                }
            }
        } else {
            logger.info("    ↳ No package-local classes are in use by other files: ${javaFile.name} can be moved")
        }
        packageLocalClassesInUseByOtherFiles
    }

    companion object {
        object AI {
            const val ID = "move-file-into-suggested-directory-transformation/ai"
        }

        object Config {
            const val ID = "move-file-into-suggested-directory-transformation/config"
        }

        fun withAI(config: Map<String, Any>, token: String) = MoveFileIntoSuggestedDirectoryTransformation(
            id = AI.ID,
            config,
            directorySuggestionApi = DirectorySuggestionApi.AI(token)
        )

        fun withConfig(config: Map<String, Any>) = MoveFileIntoSuggestedDirectoryTransformation(
            id = Config.ID,
            config,
            directorySuggestionApi = DirectorySuggestionApi.Config(config),
        )
    }
}


/**
 * This wrapper delegates ALL methods to [MoveFilesOrDirectoriesProcessor].
 * It only exposes a protected [myFoundUsages] variable for enriched logging.
 */
private class MoveFilesOrDirectoriesProcessorWrapper(
    project: Project,
    elements: Array<PsiElement>,
    newParent: PsiDirectory,
    searchInComments: Boolean,
    searchInNonJavaFiles: Boolean,
    moveCallback: MoveCallback,
    prepareSuccessfulCallback: Runnable
) : MoveFilesOrDirectoriesProcessor(
    project,
    elements,
    newParent,
    searchInComments,
    searchInNonJavaFiles,
    moveCallback,
    prepareSuccessfulCallback,
) {
    val foundUsages: Map<PsiFile, List<UsageInfo>>
        get() = myFoundUsages
}

/**
 * Strategy for selecting a destination directory.
 */
sealed class DirectorySuggestionApi {
    /**
     * Get the list of suggested directories.
     */
    abstract fun suggest(psiFile: PsiFile, virtualFile: VirtualFile): Result<List<String>>

    // implementations
    class AI(private val token: String) : DirectorySuggestionApi() {
        override fun suggest(psiFile: PsiFile, virtualFile: VirtualFile): Result<List<String>> {
            val projectRoot = psiFile.project.basePath ?: return Result.failure(
                TransformationStepFailed("Project root not found.")
            )

            return runBlocking {
                SuggestionsApi.suggestNewDirectory(
                    token = token,
                    projectRoot = projectRoot,
                    filepath = virtualFile.path,
                    // TODO: extract only declarations if the file is big
                    content = {
                        withReadAction { psiFile.text }
                    },
                    existingOnly = false,
                )
            }
        }
    }

    class Config(private val config: Map<String, Any>) : DirectorySuggestionApi() {
        override fun suggest(psiFile: PsiFile, virtualFile: VirtualFile): Result<List<String>> {
            val dest = config.require<String>("destination")
            return Result.success(listOf(dest))
        }
    }
}
