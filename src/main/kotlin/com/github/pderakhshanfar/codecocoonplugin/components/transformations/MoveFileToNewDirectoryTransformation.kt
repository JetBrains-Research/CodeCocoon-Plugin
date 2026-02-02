package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.intellij.vfs.refreshAndFindVirtualFile
import com.github.pderakhshanfar.codecocoonplugin.transformation.require
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * TODO: write descr
 *
 * Expected config schema:
 * ```yaml
 * config:
 *   destination: string # a new directory where the file should be moved to
 * ```
 */
class MoveFileToNewDirectoryTransformation(
    override val config: Map<String, Any>
) : IntelliJAwareTransformation {
    override val id: String = ID
    override val description: String = "Places the given file into a different location"

    private val logger = thisLogger().withStdout()

    private val destination: String = config.require<String>("destination")

    override fun accepts(context: FileContext): Boolean = context.language == Language.JAVA

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult {
        val requestor = this
        val newParent = destination.refreshAndFindVirtualFile()

        logger.info("Attempting to move ${virtualFile.canonicalPath} to ${newParent?.canonicalPath ?: destination} directory...")

        return when {
            newParent == null -> TransformationResult.Failure(
                "Cannot move ${virtualFile.canonicalPath}. Destination directory not found: $destination")
            !newParent.isDirectory -> TransformationResult.Failure(
                "Cannot move ${virtualFile.canonicalPath}. Destination is not a directory: $destination")
            else -> {
                // TODO: create destination if doesn't exist (newParent.exists)
                // moving the file into a new directory
                virtualFile.move(requestor, newParent)
                TransformationResult.Success("Moved ${virtualFile.canonicalPath} to $destination directory")
            }
        }
    }


    companion object {
        const val ID = "move-file-to-new-directory-transformation"
    }
}