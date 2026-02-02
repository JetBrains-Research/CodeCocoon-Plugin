package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile


class MoveFileToAiSuggestedDirectoryTransformation(
    override val config: Map<String, Any>
) : IntelliJAwareTransformation {
    override val id = ID
    override val description = "Places the given file into a directory suggested by AI"

    override fun accepts(context: FileContext): Boolean = context.language == Language.JAVA

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult {
        TODO("Not yet implemented")
    }

    companion object {
        const val ID = "move-file-to-ai-suggested-directory-transformation"
    }
}