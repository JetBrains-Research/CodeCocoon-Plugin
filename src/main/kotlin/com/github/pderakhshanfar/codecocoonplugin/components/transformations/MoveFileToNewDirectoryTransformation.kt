package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.common.TransformationStepFailed
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.transformation.require
import com.github.pderakhshanfar.codecocoonplugin.transformation.requireOrDefault
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolute

/**
 * Moves the given [VirtualFile] into a new directory specified in the transformation config (see below).
 * BY default, the destination directory gets created if it doesn't exist (configurable via `createWhenMissing` param).
 *
 * TODO: Only Java is supported!
 *
 * The destination directory must be pre-defined; when it should be resolved in runtime,
 * use [MoveFileToAiSuggestedDirectoryTransformation] that calls AI to suggest
 * a new destination directory based on the project structure.
 *
 * Expected config schema:
 * ```yaml
 * config:
 *   destination: string # required, a new directory where the file should be moved to
 *   createWhenMissing: boolean (default: true) # optional, whether to create the destination directory if it doesn't exist
 * ```
 */
class MoveFileToNewDirectoryTransformation(
    override val config: Map<String, Any>
) : IntelliJAwareTransformation {
    override val id: String = ID
    override val description: String = "Places the given file into a directory specified by the config"

    private val logger = thisLogger().withStdout()
    private val impl = MoveFileToNewDirectoryTransformationImpl()

    // params
    private val destination = config.require<String>("destination")
    private val createWhenMissing = config.requireOrDefault<Boolean>("createWhenMissing", true)

    override fun accepts(context: FileContext): Boolean = context.language == Language.JAVA

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult {
        return try {
            val project = psiFile.project
            val requestor = this

            logger.info("Attempting to move ${virtualFile.path} to $destination directory...")

            val targetDirectory = VfsUtil.findFile(Path.of(destination).absolute(), true)
                    ?: if (createWhenMissing) VfsUtil.createDirectories(destination) else null

            if (targetDirectory == null) {
                return TransformationResult.Failure(
                    "Cannot move ${virtualFile.canonicalPath}. Destination directory not found: $destination")
            } else if (!targetDirectory.isDirectory) {
                return TransformationResult.Failure(
                    "Cannot move ${virtualFile.canonicalPath}. Destination is not a directory: $destination")
            }

            // Calculate new package name relative to source root
            val result = createPackageName(project, targetDirectory)
            if (result.isFailure) {
                return TransformationResult.Failure(result.exceptionOrNull()?.message ?: "")
            }
            val newPackageName = result.getOrThrow()

            return impl.apply(
                psiFile,
                virtualFile,
                where = targetDirectory,
                newPackageName,
                requestor,
            )
        } catch (e: Exception) {
            logger.error("Failed to move file ${virtualFile.name}", e)
            TransformationResult.Failure("Failed to move file ${virtualFile.path}: ${e.message}", e)
        }
    }

    private fun createPackageName(project: Project, targetDirectory: VirtualFile): Result<String> {
        val sourceRoot = ProjectFileIndex.getInstance(project).getSourceRootForFile(targetDirectory)
            ?: return Result.failure(TransformationStepFailed(
                "Target directory $targetDirectory doesn't belong to any project's source roots"))

        val newPackageName = if (targetDirectory.path.startsWith(sourceRoot.path)) {
            // trim the source root path from the target directory to build a new package
            val relativePath = File(targetDirectory.path)
                .relativeTo(File(sourceRoot.path)).path

            if (relativePath.isEmpty()) null else relativePath.replace(File.separatorChar, '.')
        } else {
            null
        }

        return if (newPackageName == null) {
            Result.failure(TransformationStepFailed(
                "Cannot create package for the target directory: ${targetDirectory.path}"))
        } else {
            Result.success(newPackageName)
        }
    }

    companion object {
        const val ID = "move-file-to-new-directory-transformation"
    }
}