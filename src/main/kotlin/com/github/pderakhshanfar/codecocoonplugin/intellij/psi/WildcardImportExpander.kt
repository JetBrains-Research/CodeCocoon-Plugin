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
 */
object WildcardImportExpander {
    private val logger = thisLogger().withStdout()

    data class Stats(val filesScanned: Int, val wildcardsExpanded: Int)

    fun expandAll(project: Project): Stats {
        val scope = GlobalSearchScope.projectScope(project)
        val files = runBlocking { readAction { FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope) } }
        val psiManager = PsiManager.getInstance(project)

        var scanned = 0
        var expanded = 0
        for (vf in files) {
            val psiFile = runBlocking { readAction { psiManager.findFile(vf) as? PsiJavaFile } } ?: continue
            scanned++
            expanded += expandInFile(project, psiFile)
        }
        logger.info("[WildcardImportExpander] Pre-processed $scanned files; expanded $expanded wildcard import(s)")
        return Stats(scanned, expanded)
    }

    private fun expandInFile(project: Project, psiFile: PsiJavaFile): Int {
        data class Plan(
            val importList: PsiImportList,
            val staticWildcards: List<PsiImportStaticStatement>,
            val regularWildcards: List<PsiImportStatement>,
        )

        val plan = runBlocking {
            readAction {
                val importList = psiFile.importList ?: return@readAction null
                val statics = importList.importStaticStatements.filter { it.isOnDemand }.toList()
                val regulars = importList.importStatements.filter { it.isOnDemand }.toList()
                if (statics.isEmpty() && regulars.isEmpty()) null
                else Plan(importList, statics, regulars)
            }
        } ?: return 0

        var count = 0
        for (w in plan.staticWildcards) count += rewriteStaticWildcard(project, psiFile, plan.importList, w)
        for (w in plan.regularWildcards) count += rewriteRegularWildcard(project, psiFile, plan.importList, w)
        return count
    }

    private fun rewriteStaticWildcard(
        project: Project,
        psiFile: PsiJavaFile,
        importList: PsiImportList,
        wildcard: PsiImportStaticStatement,
    ): Int {
        val targetClass = runBlocking { readAction { wildcard.resolveTargetClass() } } ?: return 0
        val usedNames = runBlocking { readAction { collectStaticUses(psiFile, targetClass) } }

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
        return 1
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

    private fun collectStaticUses(psiFile: PsiJavaFile, targetClass: PsiClass): Set<String> {
        val names = LinkedHashSet<String>()
        psiFile.accept(object : JavaRecursiveElementVisitor() {
            override fun visitReferenceExpression(expr: PsiReferenceExpression) {
                super.visitReferenceExpression(expr)
                if (expr.qualifierExpression != null) return
                val resolved = expr.resolve()
                if (resolved is PsiMember && resolved.containingClass == targetClass) {
                    expr.referenceName?.let { names.add(it) }
                }
            }
        })
        return names
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
