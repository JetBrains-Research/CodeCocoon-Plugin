package com.github.pderakhshanfar.codecocoonplugin.intellij.psi

import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiImportList
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiImportStaticStatement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.runBlocking

/**
 * One-shot pre-processing utility that replaces wildcard imports with explicit
 * single imports for symbols actually used in the file.
 *
 * Why this exists: IntelliJ's `RenameProcessor` invokes
 * `JavaCodeStyleManager.shortenClassReferences()` on every file whose references
 * it rewrites. That call can also strip `import static X.*;` lines on the
 * touched files even when those lines are load-bearing — observed on
 * fastjson2 PR-82 where the rename of `JSON` → `JsonCodec` removed
 * `import static junit.framework.TestCase.*;` from test files, breaking
 * `assertNull(...)` resolution. There is no documented IntelliJ toggle for
 * this side effect (`IDEABKL-3561`).
 *
 * Pre-expanding wildcards into explicit single imports defangs the optimizer:
 * each remaining import points at a name PSI sees as referenced, so the
 * optimizer cannot drop it as "unused".
 *
 * Run once per project, before any transformation, across every Java file in
 * project scope (not just files we transform — RenameProcessor can touch
 * cross-module references in files we never enumerate).
 *
 * **Static-inheritance attribution:** `import static X.*;` legitimately exposes
 * any static defined on `X` OR any of its supers (e.g. `TestCase` exposes
 * `assertNull` declared on `Assert`). PSI's resolver returns the *declaring*
 * class, not the imported one. We therefore attribute usage by querying the
 * target class's visible (inherited) members instead of comparing
 * `containingClass`.
 *
 * **PSI-stability discipline:** Each `WriteCommandAction` that mutates the
 * import list invalidates other `PsiImportStatement` siblings even when they
 * weren't the deleted one. We therefore never hold a `PsiImportStatement` /
 * `PsiImportStaticStatement` reference across mutations — instead we capture
 * the wildcards' target FQNs once, then re-locate the matching wildcard inside
 * each rewrite by scanning the (now-fresh) import list.
 *
 * **Best-effort:** All PSI operations are wrapped in `try/catch (Throwable)`,
 * rethrowing only `ProcessCanceledException` / `InterruptedException`. A
 * single bad file or wildcard never aborts the pipeline.
 */
object WildcardImportExpander {
    private val logger = thisLogger().withStdout()

    data class Stats(
        val filesScanned: Int,
        val wildcardsExpanded: Int,
        val wildcardsKept: Int,
        val filesFailed: Int,
        val wildcardsFailed: Int,
    )

    fun expandAll(project: Project): Stats {
        val files = safeReadAction(emptyList()) {
            FileTypeIndex.getFiles(JavaFileType.INSTANCE, GlobalSearchScope.projectScope(project)).toList()
        }
        val psiManager = PsiManager.getInstance(project)

        var scanned = 0
        var expanded = 0
        var kept = 0
        var filesFailed = 0
        var wildcardsFailed = 0

        for (vf in files) {
            val path = vf.path
            try {
                val psiFile = safeReadAction(null) { psiManager.findFile(vf) as? PsiJavaFile } ?: continue
                scanned++
                val (e, k, wf) = expandInFile(project, psiFile)
                expanded += e
                kept += k
                wildcardsFailed += wf
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (t: Throwable) {
                filesFailed++
                logger.warn("[WildcardImportExpander] Failed to process file '$path': ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        logger.info(
            "[WildcardImportExpander] Pre-processed $scanned files; expanded $expanded; kept $kept conservative; " +
                "failed $filesFailed file(s) / $wildcardsFailed wildcard(s)"
        )
        return Stats(scanned, expanded, kept, filesFailed, wildcardsFailed)
    }

    /** Per-file static-reference summary built in a single PSI walk. */
    private data class StaticRefSummary(
        val resolvedNames: Set<String>,
        val unresolvedNames: Set<String>,
    )

    /** @return Triple(expanded, kept, wildcardsFailed) for the file. */
    private fun expandInFile(project: Project, psiFile: PsiJavaFile): Triple<Int, Int, Int> {
        // Capture by FQN, NOT by PsiElement reference — element references can
        // be invalidated by sibling mutations during rewrite.
        data class Plan(
            val staticTargetFqns: List<String>,
            val regularPackageFqns: List<String>,
            val staticSummary: StaticRefSummary,
        )

        val plan = safeReadAction(null) {
            val importList = psiFile.importList ?: return@safeReadAction null
            val statics = importList.importStaticStatements
                .filter { it.isOnDemand && it.isValid }
                .mapNotNull { it.importReference?.qualifiedName }
                .toList()
            val regulars = importList.importStatements
                .filter { it.isOnDemand && it.isValid }
                .mapNotNull { it.importReference?.qualifiedName }
                .toList()
            if (statics.isEmpty() && regulars.isEmpty()) null
            else Plan(statics, regulars, summarizeStaticRefs(psiFile))
        } ?: return Triple(0, 0, 0)

        var expanded = 0
        var kept = 0
        var failed = 0
        for (fqn in plan.staticTargetFqns) {
            try {
                val (e, k) = rewriteStaticWildcard(project, psiFile, fqn, plan.staticSummary)
                expanded += e
                kept += k
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (t: Throwable) {
                failed++
                logger.warn("[WildcardImportExpander] Failed to expand static wildcard '$fqn' in '${psiFile.virtualFile?.path}': ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        for (fqn in plan.regularPackageFqns) {
            try {
                expanded += rewriteRegularWildcard(project, psiFile, fqn)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (t: Throwable) {
                failed++
                logger.warn("[WildcardImportExpander] Failed to expand regular wildcard '$fqn' in '${psiFile.virtualFile?.path}': ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return Triple(expanded, kept, failed)
    }

    private fun summarizeStaticRefs(psiFile: PsiJavaFile): StaticRefSummary {
        val resolved = LinkedHashSet<String>()
        val unresolved = LinkedHashSet<String>()
        try {
            psiFile.accept(object : JavaRecursiveElementVisitor() {
                override fun visitReferenceExpression(expr: PsiReferenceExpression) {
                    super.visitReferenceExpression(expr)
                    try {
                        if (expr.qualifierExpression != null) return
                        val name = expr.referenceName ?: return
                        val target = expr.resolve()
                        when {
                            target is PsiMember -> resolved.add(name)
                            target == null -> unresolved.add(name)
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (_: Throwable) {
                        // ignore — best-effort summarisation
                    }
                }
            })
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Throwable) {
            // ignore — return what we have
        }
        return StaticRefSummary(resolved, unresolved)
    }

    /** @return Pair(expanded, kept). */
    private fun rewriteStaticWildcard(
        project: Project,
        psiFile: PsiJavaFile,
        targetFqn: String,
        summary: StaticRefSummary,
    ): Pair<Int, Int> {
        val targetClass = safeReadAction(null) {
            JavaPsiFacade.getInstance(project)
                .findClass(targetFqn, GlobalSearchScope.allScope(project))
        } ?: return 0 to 0

        val usedNames = safeReadAction(emptyList<String>()) {
            summary.resolvedNames.filter { name -> targetClassExposes(targetClass, name) }
        }
        val coversUnresolved = safeReadAction(false) {
            summary.unresolvedNames.any { name -> targetClassExposes(targetClass, name) }
        }

        if (usedNames.isEmpty() && coversUnresolved) {
            return 0 to 1
        }

        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    val importList = psiFile.importList ?: return@runWriteCommandAction
                    val factory = JavaPsiFacade.getElementFactory(project)
                    val freshTargetClass = JavaPsiFacade.getInstance(project)
                        .findClass(targetFqn, GlobalSearchScope.allScope(project))
                        ?: return@runWriteCommandAction
                    // Re-locate the wildcard fresh — sibling mutations may have
                    // invalidated whatever element we saw earlier.
                    val wildcard = importList.importStaticStatements.firstOrNull { stmt ->
                        stmt.isValid && stmt.isOnDemand &&
                            runCatching { stmt.importReference?.qualifiedName }.getOrNull() == targetFqn
                    } ?: return@runWriteCommandAction

                    for (name in usedNames) {
                        importList.add(factory.createImportStaticStatement(freshTargetClass, name))
                    }
                    if (wildcard.isValid) wildcard.delete()
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("[WildcardImportExpander] WriteCommandAction failed for static '$targetFqn': ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return 1 to 0
    }

    private fun targetClassExposes(targetClass: PsiClass, name: String): Boolean {
        return try {
            if (targetClass.findMethodsByName(name, /* checkBases = */ true).isNotEmpty()) return true
            if (targetClass.findFieldByName(name, /* checkBases = */ true) != null) return true
            if (targetClass.findInnerClassByName(name, /* checkBases = */ true) != null) return true
            false
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    private fun rewriteRegularWildcard(
        project: Project,
        psiFile: PsiJavaFile,
        packageFqn: String,
    ): Int {
        val pkg = safeReadAction(null) {
            JavaPsiFacade.getInstance(project).findPackage(packageFqn)
        } ?: return 0

        val usedClasses = safeReadAction(emptyList<PsiClass>()) {
            collectClassUses(psiFile, pkg).toList()
        }

        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    val importList = psiFile.importList ?: return@runWriteCommandAction
                    val factory = JavaPsiFacade.getElementFactory(project)
                    val wildcard = importList.importStatements.firstOrNull { stmt ->
                        stmt.isValid && stmt.isOnDemand &&
                            runCatching { stmt.importReference?.qualifiedName }.getOrNull() == packageFqn
                    } ?: return@runWriteCommandAction

                    for (cls in usedClasses) {
                        if (cls.isValid) importList.add(factory.createImportStatement(cls))
                    }
                    if (wildcard.isValid) wildcard.delete()
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                logger.warn("[WildcardImportExpander] WriteCommandAction failed for regular '$packageFqn': ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return 1
    }

    private fun collectClassUses(psiFile: PsiJavaFile, pkg: PsiPackage): Set<PsiClass> {
        val classes = LinkedHashSet<PsiClass>()
        val pkgFqn = pkg.qualifiedName
        try {
            psiFile.accept(object : JavaRecursiveElementVisitor() {
                override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                    super.visitReferenceElement(reference)
                    try {
                        if (reference.qualifier != null) return
                        val resolved = reference.resolve() as? PsiClass ?: return
                        val fqn = resolved.qualifiedName ?: return
                        if (fqn.substringBeforeLast('.', "") == pkgFqn) {
                            classes.add(resolved)
                        }
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (_: Throwable) {
                        // ignore
                    }
                }
            })
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Throwable) {
            // ignore
        }
        return classes
    }

    /**
     * Run a read-action computation, swallowing any non-control-flow throwable
     * and returning [fallback]. We deliberately do NOT rethrow PSI / IDE
     * exceptions here — they'd abort the pipeline. Cancellation must still
     * propagate, hence the explicit rethrow for `ProcessCanceledException` /
     * `InterruptedException`.
     */
    private fun <T> safeReadAction(fallback: T, block: () -> T): T {
        return try {
            runBlocking { readAction { block() } }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (t: Throwable) {
            logger.warn("[WildcardImportExpander] readAction failed: ${t.javaClass.simpleName}: ${t.message}")
            fallback
        }
    }
}
