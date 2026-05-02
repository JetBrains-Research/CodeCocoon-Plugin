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
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
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
                        try {
                            val allMethods = psiClass.methods.toList()
                            val methods = allMethods.filter { it.isPhysical && it !is PsiCompiledElement }
                            val droppedMethods = allMethods - methods.toSet()
                            if (droppedMethods.isNotEmpty()) {
                                val droppedNames = droppedMethods.joinToString(", ") {
                                    val reason = when {
                                        it is PsiCompiledElement -> "compiled"
                                        !it.isPhysical -> "non-physical"
                                        else -> "filtered"
                                    }
                                    "${it.name} ($reason)"
                                }
                                logger.info(
                                    "      ⊘ Class `${psiClass.className}` - dropped ${droppedMethods.size} method(s) " +
                                        "before reorder: $droppedNames"
                                )
                            }
                            if (methods.size < 2) {
                                logger.warn("    ⊘ Class `${psiClass.className}` - has ${methods.size} reorderable method(s) (skipping)")
                                continue
                            }

                            val sortedMethods = reorderMethods(methods)

                            if (sortedMethods.map { it.name } == methods.map { it.name }) {
                                logger.info("    ⊘ Class `${psiClass.className}` - methods already in desired order")
                                continue
                            }

                            val rBrace = psiClass.rBrace
                            if (rBrace == null) {
                                logger.warn("    ⊘ Class `${psiClass.className}` - no closing brace, skipping")
                                continue
                            }

                            // Pre-validate that every method can be copied. PsiElement.copy() returns null
                            // for non-copyable elements (synthetic / PsiAugmentProvider-injected light methods,
                            // etc.), and addBefore(null, ...) would crash with @NotNull violation. Doing this
                            // before any mutation avoids leaving the class in a half-deleted / half-added state.
                            val copies = sortedMethods.map { it to it.copy() as? PsiMethod }
                            val firstNullCopy = copies.firstOrNull { it.second == null }
                            if (firstNullCopy != null) {
                                logger.warn(
                                    "    ⊘ Class `${psiClass.className}` - method " +
                                        "`${firstNullCopy.first.name}` is non-copyable (likely synthetic / " +
                                        "augmented PSI); skipping class to avoid partial reorder"
                                )
                                continue
                            }

                            // add sorted methods into class
                            for ((_, copy) in copies) {
                                psiClass.addBefore(copy!!, rBrace)
                            }
                            // remove original methods
                            for (method in sortedMethods) {
                                method.delete()
                            }

                            reorderedClassCount += 1
                            totalMethodsTouched += methods.size
                            logger.info("    ✓ Class `${psiClass.className}` - reordered ${methods.size} methods")
                        }
                        catch (err: ProcessCanceledException) {
                            throw err
                        }
                        catch (e: Exception) {
                            logger.error("    ✗ Class `${psiClass.className}` - failed to reorder methods: ${e.message}", e)
                        }
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
                if (element !is PsiClass) {
                    return
                }
                // TODO(NOTE): don't skip anonymous classes for eval (possibly, more transformations with anonymous classes included)
                // Skip anonymous classes (visited inside method bodies) — reordering their
                // methods is not the user's intent and they often have non-copyable PSI.
                /*if (element is PsiAnonymousClass) {
                    logger.info("    ⊘ Skipping anonymous class inside ${psiFile.name}")
                    return
                }
                if (element.name == null) {
                    logger.info("    ⊘ Skipping unnamed class-like element in ${psiFile.name}")
                    return
                }*/
                // Skip compiled / non-physical classes (mirror RenameVariableTransformation).
                if (element is PsiCompiledElement) {
                    logger.info("    ⊘ Skipping compiled class `${element.name}` in ${psiFile.name}")
                    return
                }
                if (!element.isPhysical) {
                    logger.info("    ⊘ Skipping non-physical class `${element.name}` in ${psiFile.name}")
                    return
                }
                classes.add(element)
            }
        })
        return classes
    }

    private val PsiClass.className: String
        get() = this.name ?: "[anonymous-class]"

    companion object {
        const val ID = "reorder-class-methods-transformation"
    }
}
