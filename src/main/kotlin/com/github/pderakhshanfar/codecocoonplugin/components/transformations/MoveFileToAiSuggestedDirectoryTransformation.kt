package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.common.TransformationStepFailed
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.suggestions.SuggestionsApi
import com.github.pderakhshanfar.codecocoonplugin.transformation.requireOrDefault
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File


/**
 * A transformation that moves a given file to a directory suggested by AI.
 *
 * This transformation analyzes the context of the provided file and relocates it to an AI-recommended
 * directory based on its content, usage, or other metadata. The target directory is determined dynamically based
 * on AI algorithms, making project organization more intuitive and efficient.
 *
 * TODO: this transformation is tailored to Java projects; not multi-lingual!
 *
 * Expected config schema:
 * ```yaml
 * config:
 *   existingOnly: boolean (default: false) # optional, whether to suggestion yet non-existent directories
 * ```
 *
 * @property config Configuration parameters required for the transformation.
 * @constructor Initializes the transformation with the provided configuration map.
 */
class MoveFileToAiSuggestedDirectoryTransformation(
    override val config: Map<String, Any>,
) : IntelliJAwareTransformation {
    override val id = ID
    override val description = "Places the given file into a directory suggested by AI"

    private val existingOnly: Boolean = config.requireOrDefault("existingOnly", false)

    private val logger = thisLogger().withStdout()
    private val impl = MoveFileToNewDirectoryTransformationImpl()

    override fun accepts(context: FileContext): Boolean {
        // NOTE: accepting only Java files for now
        return context.language == Language.JAVA
    }

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult {
        val project = psiFile.project
        val requestor = this

        // validate that this is a Java file
        if (psiFile !is PsiJavaFile) {
            return TransformationResult.Failure("File ${virtualFile.path} is not a Java file")
        }

        return try {
            val projectRoot = project.basePath
                ?: return TransformationResult.Failure("Cannot determine project base path")

            /**
             * STEP 0: Generate AI Directory Suggestions and Create a Select New Package
             */
            logger.info("STEP 0: Generate AI Directory Suggestions and Create a Select New Package")
            val suggestedDirectories = runBlocking {
                SuggestionsApi.suggestNewDirectory(
                    token = System.getenv("OPENAI_API_KEY"),
                    projectRoot = projectRoot,
                    filepath = virtualFile.path,
                    // TODO: extract only declarations if the file is big
                    content = { psiFile.text },
                    existingOnly = existingOnly
                )
            }
            if (suggestedDirectories.isEmpty()) {
                return TransformationResult.Failure("No directory suggestions received from AI")
            }

            // select suitable directory from AI suggestions
            val oldPackageName = psiFile.packageName
            logger.info("Suggestions produced by AI: $suggestedDirectories")
            val result = WriteCommandAction.runWriteCommandAction<Result<DestinationDirectory>>(project) {
                findAndCreateSuitableDestinationDirectory(
                    project = project,
                    projectRoot = projectRoot,
                    oldPackageName = oldPackageName,
                    suggestions = suggestedDirectories,
                )
            }
            // validate
            val (destinationDirectory, newPackageName) = when {
                result.isSuccess -> result.getOrThrow()
                else -> return TransformationResult.Failure(
                    result.exceptionOrNull()?.message ?: "Unknown error")
            }

            return impl.apply(
                psiFile,
                virtualFile,
                destinationDirectory,
                newPackageName,
                requestor,
            )

            /*
            logger.info("Selected destination directory: $destinationDirectory, and new package: $newPackageName")
            logger.info("Package change for file ${virtualFile.path}: $oldPackageName -> $newPackageName")

            var modifiedFilesCount = 0
            WriteCommandAction.runWriteCommandAction<Unit>(project) {
                *//**
                 * STEP 1: Import Class From The Same Package
                 *
                 * Rationale:
                 * Import referenced classes from the same package BEFORE moving this file,
                 * as these imports will be required AFTER moving the file.
                 *//*
                logger.info("STEP 1: Import Class From The Same Package")
                psiFile.importClassesFromPackage(oldPackageName)

                *//**
                 * STEP 2: Collect Symbols Referenced In Other Files Before Move
                 *
                 * Rationale:
                 * The package of the following collected symbols will be updated.
                 * Therefore, BEFORE moving the file and updating its package,
                 * we collect all files that reference the symbols to update or add
                 * imports AFTER the move.
                 *//*
                logger.info("STEP 2: Collect Symbols Referenced In Other Files Before Move")
                // collect public classes/interfaces that will need import updates in other files
                // and find all files that reference these classes.
                val publicClasses: List<PsiClass> = psiFile.collectPublicClasses()
                val referencingFilesToClasses: Map<PsiJavaFile, Set<PsiClass>> =
                    publicClasses.findReferencingFiles(project)

                logger.info("${psiFile.name} contains ${publicClasses.size} public classes: ${publicClasses.map { it.qualifiedName }}")
                logger.info("${referencingFilesToClasses.size} files reference classes from ${psiFile.name}: ${referencingFilesToClasses.map { (file, refs) -> "${file.name} -> ${refs.map { it.qualifiedName }}" }}")

                *//**
                 * STEP 3: Move the File and Update its Package
                 *//*
                logger.info("STEP 3: Move the File and Update its Package")
                val result = virtualFile.moveAndUpdatePackage(
                    project = project,
                    requestor = requestor,
                    where = destinationDirectory,
                    packageName = newPackageName,
                )
                if (result.isFailure) {
                    val err = result.exceptionOrNull() ?: TransformationStepFailed(
                        "Failed to move file ${virtualFile.name} or update its package to $newPackageName")
                    throw err
                }
                // we change the package within the moved file
                modifiedFilesCount += 1

                *//**
                 * STEP 4: Update Imports In Referencing Files
                 *//*
                logger.info("STEP 4: Update Imports In Referencing Files")
                for ((referencingFile, referencedClasses) in referencingFilesToClasses) {
                    // update or add imports of the moved classes in the referencing files
                    // IMPORTANT: this step MUST be done AFTER the file is moved and its package is updated
                    val fileModified = referencingFile.updateImportsOfMovedReferencedClasses(
                        referencedClasses,
                        oldPackageName,
                    )
                    if (fileModified) {
                        modifiedFilesCount += 1
                    }
                }
            }

            TransformationResult.Success(
                message = "Moved ${virtualFile.name} from package '$oldPackageName' to '$newPackageName'",
                filesModified = modifiedFilesCount,
            )*/
        } catch (e: Exception) {
            logger.error("Failed to move file ${virtualFile.name}", e)
            TransformationResult.Failure("Failed to move file ${virtualFile.path}: ${e.message}", e)
        }
    }

    /**
     * Represents a directory destination where a file should be moved during a transformation process
     * and its corresponding package.
     *
     * @property directory The virtual representation of the target directory.
     * @property packageName The package name associated with the target directory.
     */
    data class DestinationDirectory(
        val directory: VirtualFile,
        val packageName: String,
    )

    /**
     * Finds and creates a suitable destination directory among the provided suggestions.
     * A suitable directory is one that matches the required criteria, such as being under a source root
     * and having a valid package name. The first successful suggestion is returned as the result.
     *
     * If no suitable directory is found, a failure result is returned.
     *
     * NOTES:
     * 1. **This method may produce side effects by creating directories on the file system for unsuccessful suggestions.**
     * 2. **REQUIRES the write action scope.**
     *
     * @param project The IntelliJ Platform `Project` in which the operation is being performed.
     * @param projectRoot The absolute path of the project's root directory.
     * @param oldPackageName The original package name of the file being moved.
     * @param suggestions A list of suggested directory paths to evaluate as potential destinations.
     * @return A `Result` object containing the successfully created or validated `DestinationDirectory`,
     *         or a failure with an appropriate error message.
     */
    private fun findAndCreateSuitableDestinationDirectory(
        project: Project,
        projectRoot: String,
        oldPackageName: String,
        suggestions: List<String>,
    ): Result<DestinationDirectory> {
        val fileIndex = ProjectFileIndex.getInstance(project)

        /**
         * Produces a side effect for the unsuccessful suggestions:
         * The directory path of the suggestion gets created on the file system.
         *
         * @param suggestion a directory path suggested by AI.
         */
        fun toDestinationDirectoryIfSuitable(suggestion: String): Result<DestinationDirectory> {
            logger.info("Considering suggested directory: $suggestion")

            // in case when the suggestion API returns paths relative to the project root
            val targetDirectory = when {
                suggestion.toPath().isAbsolute -> suggestion
                else -> File(projectRoot, suggestion).absolutePath
            }
            logger.info("Absolute target directory: $targetDirectory")

            // verify the target directory is under a source root
            // Create a directory structure if it doesn't exist
            val virtualFile = VfsUtil.createDirectories(targetDirectory)
                ?: return Result.failure(TransformationStepFailed(
                    "Cannot find virtual file for target: $targetDirectory"))

            val sourceRoot = fileIndex.getSourceRootForFile(virtualFile)
                ?: return Result.failure(TransformationStepFailed(
                    "Target directory $targetDirectory doesn't belong to any project's source roots"))

            // Calculate new package name relative to source root
            val newPackageName = when {
                targetDirectory.startsWith(sourceRoot.path) -> {
                    // trim the source root path from the target directory to build a new package
                    val relativePath = File(targetDirectory)
                        .relativeTo(File(sourceRoot.path)).path

                    if (relativePath.isEmpty()) "" else relativePath.replace(File.separatorChar, '.')
                }
                else -> null
            }

            logger.info("""
                suggestion: $suggestion
                targetDirectory: $targetDirectory
                oldPackageName: $oldPackageName
                newPackageName: $newPackageName (isValid=${newPackageName?.isValidPackageName()})
                targetSourceRoot: ${sourceRoot.path}
            """.trimIndent())

            // validate package
            when {
                newPackageName == null -> return Result.failure(TransformationStepFailed(
                    "Cannot create new package name for target directory $targetDirectory: " +
                            "target directory is not under source root ${sourceRoot.path}"
                ))
                newPackageName == oldPackageName -> return Result.failure(TransformationStepFailed(
                    "The new package equals to the original one: $newPackageName. " +
                            "The suggested directory would remain unchanged: $targetDirectory"
                ))
                !newPackageName.isValidPackageName() -> return Result.failure(TransformationStepFailed(
                    "Suggestion $targetDirectory leads to invalid package name: $newPackageName"
                ))
            }

            return Result.success(DestinationDirectory(
                directory = virtualFile,
                packageName = newPackageName
            ))
        }

        /**
         * Turning into sequence to lazily evaluate suggestions:
         * The `firstOrNull` at the end ensures only the minimal
         * number of suggestions is evaluated.
         */
        val suitableResult = suggestions.asSequence()
            .map { suggestion ->
                val result = toDestinationDirectoryIfSuitable(suggestion)
                if (result.isFailure) {
                    logger.warn(
                        "Failed to create target directory for suggestion $suggestion: ${result.exceptionOrNull()?.message}")
                }
                result
            }
            .firstOrNull { it.isSuccess }

        if (suitableResult == null) {
            return Result.failure(TransformationStepFailed(
                "No suitable target directory found among suggestions: ${suggestions.joinToString(",")}"))
        }

        return suitableResult
    }

    /**
     * Validates that the package name is a valid Java identifier.
     */
    private fun String.isValidPackageName(): Boolean {
        // default package
        val packageName = this
        if (packageName.isEmpty()) {
            return true
        }
        return packageName.split('.').all { part ->
            part.isNotEmpty() && part[0].isJavaIdentifierStart() && part.all { it.isJavaIdentifierPart() }
        }
    }

    companion object {
        const val ID = "move-file-to-ai-suggested-directory-transformation"
    }
}



