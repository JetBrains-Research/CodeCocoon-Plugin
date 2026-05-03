package com.github.pderakhshanfar.codecocoonplugin.intellij.psi

import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
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
 */
object WildcardImportExpander {
    private val logger = thisLogger().withStdout()

    data class Stats(val filesScanned: Int, val wildcardsExpanded: Int, val wildcardsKept: Int)

    fun expandAll(project: Project): Stats {
        val scope = GlobalSearchScope.projectScope(project)
        val files = runBlocking { readAction { FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope) } }
        val psiManager = PsiManager.getInstance(project)

        var scanned = 0
        var expanded = 0
        var kept = 0
        for (vf in files) {
            val psiFile = runBlocking { readAction { psiManager.findFile(vf) as? PsiJavaFile } } ?: continue
            scanned++
            val (e, k) = expandInFile(project, psiFile)
            expanded += e
            kept += k
        }
        logger.info("[WildcardImportExpander] Pre-processed $scanned files; expanded $expanded wildcard import(s); kept $kept wildcard(s) as conservative")
        return Stats(scanned, expanded, kept)
    }

    /**
     * Per-file static-reference summary built in a single PSI walk, so we can
     * attribute names to multiple wildcards without re-walking.
     */
    private data class StaticRefSummary(
        /** Names of unqualified references that resolved to some PsiMember. */
        val resolvedNames: Set<String>,
        /** Names of unqualified references whose resolution returned null. */
        val unresolvedNames: Set<String>,
    )

    /** @return (expanded, kept) counters for the file. */
    private fun expandInFile(project: Project, psiFile: PsiJavaFile): Pair<Int, Int> {
        data class Plan(
            val importList: PsiImportList,
            val staticWildcards: List<PsiImportStaticStatement>,
            val regularWildcards: List<PsiImportStatement>,
            val staticSummary: StaticRefSummary,
        )

        val plan = runBlocking {
            readAction {
                val importList = psiFile.importList ?: return@readAction null
                val statics = importList.importStaticStatements.filter { it.isOnDemand }.toList()
                val regulars = importList.importStatements.filter { it.isOnDemand }.toList()
                if (statics.isEmpty() && regulars.isEmpty()) null
                else Plan(importList, statics, regulars, summarizeStaticRefs(psiFile))
            }
        } ?: return 0 to 0

        var expanded = 0
        var kept = 0
        for (w in plan.staticWildcards) {
            val (e, k) = rewriteStaticWildcard(project, plan.importList, w, plan.staticSummary)
            expanded += e
            kept += k
        }
        for (w in plan.regularWildcards) {
            expanded += rewriteRegularWildcard(project, psiFile, plan.importList, w)
        }
        return expanded to kept
    }

    /**
     * Walk the file once. For every unqualified `PsiReferenceExpression`,
     * record its name as either resolved (resolves to some `PsiMember`) or
     * unresolved. Filtering per wildcard happens later by querying the target
     * class's visible members.
     */
    private fun summarizeStaticRefs(psiFile: PsiJavaFile): StaticRefSummary {
        val resolved = LinkedHashSet<String>()
        val unresolved = LinkedHashSet<String>()
        psiFile.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expr: PsiReferenceExpression) {
                super.visitReferenceExpression(expr)
                if (expr.qualifierExpression != null) return
                val name = expr.referenceName ?: return
                val target = expr.resolve()
                when {
                    target is PsiMember -> resolved.add(name)
                    target == null -> unresolved.add(name)
                }
            }
        })
        return StaticRefSummary(resolved, unresolved)
    }

    /**
     * Rewrite one static wildcard.
     *
     * @return (1, 0) on expansion, (0, 1) on conservative keep, (0, 0) on
     *         unresolvable target (no change).
     */
    private fun rewriteStaticWildcard(
        project: Project,
        importList: PsiImportList,
        wildcard: PsiImportStaticStatement,
        summary: StaticRefSummary,
    ): Pair<Int, Int> {
        val targetClass = runBlocking { readAction { wildcard.resolveTargetClass() } } ?: return 0 to 0

        // Names that target class exposes (incl. inherited) that are referenced
        // in this file. Same name may also be exposed by another wildcard's
        // target class — that's correct, we record it for both.
        val usedNames = runBlocking {
            readAction {
                summary.resolvedNames.filter { name -> targetClassExposes(targetClass, name) }
            }
        }

        // Conservative keep: if any unresolved reference matches a name this
        // target class would expose, the wildcard might be load-bearing for
        // a name PSI couldn't bind right now. Don't delete it.
        val coversUnresolved = runBlocking {
            readAction {
                summary.unresolvedNames.any { name -> targetClassExposes(targetClass, name) }
            }
        }

        if (usedNames.isEmpty() && coversUnresolved) {
            return 0 to 1
        }

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                if (!wildcard.isValid) return@runWriteCommandAction
                val factory = JavaPsiFacade.getElementFactory(project)
                for (name in usedNames) {
                    importList.add(factory.createImportStaticStatement(targetClass, name))
                }
                wildcard.delete()
            }
        }
        return 1 to 0
    }

    /**
     * Does `targetClass` expose a static member with this simple name through
     * its visible (inherited) members? Methods, fields, and inner classes all
     * count as importable via `import static X.*;`.
     */
    private fun targetClassExposes(targetClass: PsiClass, name: String): Boolean {
        if (targetClass.findMethodsByName(name, /* checkBases = */ true).isNotEmpty()) return true
        if (targetClass.findFieldByName(name, /* checkBases = */ true) != null) return true
        if (targetClass.findInnerClassByName(name, /* checkBases = */ true) != null) return true
        return false
    }

    private fun rewriteRegularWildcard(
        project: Project,
        psiFile: PsiJavaFile,
        importList: PsiImportList,
        wildcard: PsiImportStatement,
    ): Int {
        val pkg = runBlocking { readAction { wildcard.importReference?.resolve() as? PsiPackage } } ?: return 0
        val usedClasses = runBlocking { readAction { collectClassUses(psiFile, pkg) } }

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                if (!wildcard.isValid) return@runWriteCommandAction
                val factory = JavaPsiFacade.getElementFactory(project)
                for (cls in usedClasses) {
                    importList.add(factory.createImportStatement(cls))
                }
                wildcard.delete()
            }
        }
        return 1
    }

    private fun collectClassUses(psiFile: PsiJavaFile, pkg: PsiPackage): Set<PsiClass> {
        val classes = LinkedHashSet<PsiClass>()
        val pkgFqn = pkg.qualifiedName
        psiFile.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                super.visitReferenceElement(reference)
                if (reference.qualifier != null) return
                val resolved = reference.resolve() as? PsiClass ?: return
                val fqn = resolved.qualifiedName ?: return
                if (fqn.substringBeforeLast('.', "") == pkgFqn) {
                    classes.add(resolved)
                }
            }
        })
        return classes
    }
}
