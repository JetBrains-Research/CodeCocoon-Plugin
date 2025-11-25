package com.github.pderakhshanfar.codecocoonplugin.intellij.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.editor.Document

/**
 * Retrieves the corresponding [PsiFile] for the given [VirtualFile] within this project.
 *
 * @param virtualFile [VirtualFile] for which [PsiFile] is to be retrieved
 * @return [PsiFile] associated with the given [VirtualFile], or `null` if no such PSI file was found
 *
 * @see [PsiManager.findFile]
 */
fun Project.psiFile(virtualFile: VirtualFile): PsiFile? {
    return PsiManager.getInstance(this).findFile(virtualFile)
}

/**
 * Returns the document for the specified PSI file.
 *
 * @see [PsiDocumentManager.getDocument]
 */
fun PsiFile.document(): Document? = this.let { file ->
    PsiDocumentManager.getInstance(file.project).getDocument(file)
}