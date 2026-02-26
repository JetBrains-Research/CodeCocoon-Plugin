package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation.Companion.withReadAction
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.java.JavaTransformation
import com.github.pderakhshanfar.codecocoonplugin.suggestions.SuggestionsApi
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.usageView.UsageInfo
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture


/**
 * A transformation that moves a given file to a directory suggested by AI.
 *
 * This transformation analyzes the context of the provided file and relocates it to an AI-recommended
 * directory based on its content, usage, or other metadata. The target directory is determined dynamically based
 * on AI algorithms, making project organization more intuitive and efficient.
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
) : JavaTransformation, SelfManagedTransformation() {
    override val id = ID
    override val description = "Places the given Java file into a directory suggested by AI"

    private val logger = thisLogger().withStdout()

    fun tryToMoveFileIntoSuggestedDirectory(
        project: Project,
        fileToMove: PsiFile,
        suggestions: List<String>
    ): TransformationResult {
        val filename = withReadAction { fileToMove.name }

        if (suggestions.isEmpty()) {
            return TransformationResult.Failure("Cannot move $filename: No directory suggestions received")
        }

        for (suggestion in suggestions) {
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
            ApplicationManager.getApplication().invokeAndWait {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                processor.run()
            }

            // finish when moved successfully into the current suggestion
            if (successfullyMoved.join()) {
                val (filesModified, usageSummary) = withReadAction {
                    val usages = processor.foundUsages

                    val modifiedFiles = usages.values.flatten().toSet().size
                    val summary = buildString {
                        for ((index, entry) in usages.entries.withIndex()) {
                            val (which, where) = entry

                            appendLine("  ${index+1}) ${which.name} has ${where.size} usages:")
                            for ((usageIndex, usage) in where.withIndex()) {
                                appendLine("    ${usageIndex+1}) ${usage.virtualFile?.name}")
                            }
                        }
                    }

                    modifiedFiles to summary
                }

                return TransformationResult.Success(
                    message = "Successfully moved $filename into $suggestion. Usage Summary:\n$usageSummary",
                    filesModified,
                )
            }
        }

        // no suggestions fit
        return TransformationResult.Failure(
            "Failed to move $filename into any of ${suggestions.size} suggested directories:\n${suggestions.joinToString("\n") { "  - $it" }}")
    }

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult {
        val project = psiFile.project
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
                    content = {
                        withReadAction { psiFile.text }
                    },
                    existingOnly = false,
                )
            }

            return tryToMoveFileIntoSuggestedDirectory(
                project,
                psiFile,
                suggestedDirectories,
            )
        } catch (e: Exception) {
            logger.error("Failed to move file ${virtualFile.name}", e)
            TransformationResult.Failure("Failed to move file ${virtualFile.path}: ${e.message}", e)
        }
    }

    companion object {
        const val ID = "move-file-to-ai-suggested-directory-transformation"
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
