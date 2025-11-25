package com.github.pderakhshanfar.codecocoonplugin.components.transformation

import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Interface for transformations that need access to IntelliJ Platform components (e.g., PSI, VFS, etc.).
 */
interface IntelliJAwareTransformation : Transformation {
    /**
     * Apply the transformation using IntelliJ PSI.
     *
     * @param psiFile The PSI representation of the file
     * @param virtualFile The virtual file being transformed
     * @return Result of the transformation
     */
    fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult
}