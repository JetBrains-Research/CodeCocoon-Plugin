package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.intellij.vfs.refreshAndFindVirtualFile
import com.github.pderakhshanfar.codecocoonplugin.transformation.require
import com.github.pderakhshanfar.codecocoonplugin.transformation.requireOrDefault
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * TODO: write descr
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
    override val description: String = "Places the given file into a different location"

    private val logger = thisLogger().withStdout()

    // params
    private val destination = config.require<String>("destination")
    private val createWhenMissing = config.requireOrDefault<Boolean>("createWhenMissing", true)

    override fun accepts(context: FileContext): Boolean = context.language == Language.JAVA

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult {
        val project = psiFile.project
        val requestor = this

        logger.info("Attempting to move ${virtualFile.canonicalPath} to $destination directory...")

        val newParent = destination.refreshAndFindVirtualFile() ?: when (createWhenMissing) {
            true -> WriteCommandAction.runWriteCommandAction<VirtualFile>(project) {
                // create the destination directory if it doesn't exist
                logger.info("Destination directory doesn't exist. Creating it...")
                VfsUtil.createDirectories(destination)
            }
            false -> {
                logger.info("Destination directory doesn't exist. Creation on missing directory is disallowed " +
                        "(see `createWhenMissing` in transformation config)")
                null
            }
        }

        return when {
            newParent == null -> TransformationResult.Failure(
                "Cannot move ${virtualFile.canonicalPath}. Destination directory not found: $destination")
            !newParent.isDirectory -> TransformationResult.Failure(
                "Cannot move ${virtualFile.canonicalPath}. Destination is not a directory: $destination")
            else -> {
                WriteCommandAction.runWriteCommandAction(project) {
                    // moving the file into a new directory
                    virtualFile.move(requestor, newParent)
                }
                TransformationResult.Success("Moved ${virtualFile.canonicalPath} to $destination directory")
            }
        }
    }


    companion object {
        const val ID = "move-file-to-new-directory-transformation"
    }
}