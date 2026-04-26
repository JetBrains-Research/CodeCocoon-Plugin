package com.github.pderakhshanfar.codecocoonplugin.components.transformations.structural

import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.SelfManagedTransformation
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.document
import com.github.pderakhshanfar.codecocoonplugin.java.JavaTransformation
import com.github.pderakhshanfar.codecocoonplugin.memory.Memory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiRecursiveElementVisitor

/**
 * Reorders methods in a class in a *reverse alphabetic order* (Z -> A).
 */
class ReorderClassMethodsTransformation(
    override val config: Map<String, Any>
) : JavaTransformation, SelfManagedTransformation() {
    override val id: String = ID
    override val description: String = "Reorders methods in a class in reverse alphabetic order (Z -> A)"
    private val logger = thisLogger().withStdout()

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile,
        memory: Memory<String, String>?
    ): TransformationResult {
        return try {
            if (psiFile !is PsiJavaFile) {
                return TransformationResult.Skipped("File ${virtualFile.name} is not a Java file")
            }

            val project = psiFile.project
            val classes = IntelliJAwareTransformation.withReadAction { collectAllClasses(psiFile) }

            if (classes.isEmpty()) {
                return TransformationResult.Skipped("No classes found in ${virtualFile.name}")
            }

            var reorderedClassCount = 0
            var totalMethodsTouched = 0

            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project, "Reorder Class Methods", null, {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()

                    for (psiClass in classes) {
                        val methods = psiClass.methods.toList()
                        if (methods.size < 2) {
                            logger.warn("    ⊘ Class `${psiClass.name}` - has ${methods.size} methods (skipping)")
                            continue
                        }

                        val sortedMethods = reorderMethods(methods)

                        if (sortedMethods.map { it.name } == methods.map { it.name }) {
                            logger.info("    ⊘ Class `${psiClass.name}` - methods already in desired order")
                            continue
                        }

                        val rBrace = psiClass.rBrace
                        if (rBrace == null) {
                            logger.warn("    ⊘ Class `${psiClass.name}` - no closing brace, skipping")
                            continue
                        }

                        // add sorted methods into class
                        for (method in sortedMethods) {
                            psiClass.addBefore(method.copy(), rBrace)
                        }
                        // remove original methods
                        for (method in methods) {
                            method.delete()
                        }

                        reorderedClassCount += 1
                        totalMethodsTouched += methods.size
                        logger.info("    ✓ Class `${psiClass.name}` - reordered ${methods.size} methods")
                    }

                    val document = psiFile.document()
                    if (document != null) {
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                        FileDocumentManager.getInstance().saveDocument(document)
                    } else {
                        logger.warn("  ⚠ Could not get document for ${virtualFile.name}; changes may not be flushed to disk")
                    }
                })
            }

            if (reorderedClassCount == 0) {
                TransformationResult.Skipped("Nothing to reorder in ${virtualFile.name}")
            } else {
                TransformationResult.Success(
                    message = "Reordered $totalMethodsTouched methods across $reorderedClassCount class(es) in ${virtualFile.name}",
                    filesModified = 1,
                )
            }
        }
        catch (err: ProcessCanceledException) {
            throw err
        }
        catch (e: Exception) {
            TransformationResult.Failure("Failed to reorder methods in ${virtualFile.name}", e)
        }
    }

    /**
     * Returns methods in the desired order. Reverse-alphabetical (Z → A) for now.
     * Future config params will be wired here to switch strategies.
     */
    private fun reorderMethods(methods: List<PsiMethod>): List<PsiMethod> =
        methods.sortedByDescending { it.name }

    private fun collectAllClasses(psiFile: PsiFile): List<PsiClass> {
        val classes = mutableListOf<PsiClass>()
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element is PsiClass) {
                    classes.add(element)
                }
            }
        })
        return classes
    }

    companion object {
        const val ID = "reorder-class-methods-transformation"
    }
}
