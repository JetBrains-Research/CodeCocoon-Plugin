package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.document
import com.github.pderakhshanfar.codecocoonplugin.java.JavaTransformation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch

class RenameMethodTransformation(
    override val config: Map<String, Any>
) : JavaTransformation, IntelliJAwareTransformation {
    override val id: String = ID
    override val description: String =
        "Renames all public methods in the given file (excluding constructors, getters, setters, toString)"

    override fun accepts(context: FileContext): Boolean {
        return super.accepts(context)
    }

    override fun apply(
        psiFile: PsiFile, virtualFile: VirtualFile
    ): TransformationResult {
        val result = try {
            val document = psiFile.document()
            val modifiedFiles = mutableSetOf<PsiFile>()
            val value = if (document != null) {
                val publicMethods: List<PsiMethod> = findAllValidMethods(psiFile)

                if (publicMethods.isEmpty()) {
                    return TransformationResult.Skipped("No public methods found in ${virtualFile.name}")
                }

                val newNames = publicMethods.map { method ->
                    method to "${method.name}_renamed"
                }

                // Rename each method and all its usages across the project
                for ((method, newName) in newNames) {
                    val files = renameMethodAndUsages(psiFile.project, method, newName)
                    modifiedFiles.addAll(files)
                }

                TransformationResult.Success(
                    message = "Renamed ${publicMethods.size} public methods in ${virtualFile.name}",
                    filesModified = modifiedFiles.size
                )
            } else {
                TransformationResult.Failure(
                    "Could not get document for file ${psiFile.name}"
                )
            }
            value
        } catch (e: Exception) {
            TransformationResult.Failure("Failed to rename methods in file ${virtualFile.name}", e)
        }
        return result
    }

    companion object {
        const val ID = "rename-method-transformation"
    }
}


/**
 * Renames a method and all its usages across the entire project.
 * Uses IntelliJ's ReferencesSearch API to find all method usages.
 * @return Number of modified files
 */
private fun renameMethodAndUsages(project: Project, method: PsiMethod, newName: String): MutableSet<PsiFile> {
    val searchScope = GlobalSearchScope.projectScope(project)
    val allReferences = ReferencesSearch.search(method, searchScope).findAll().toList()
    val allOverrides = OverridingMethodsSearch.search(method, searchScope, true).findAll().toList()
    val modifiedFiles = mutableSetOf<PsiFile>()

    method.name = newName
    method.containingFile?.let { modifiedFiles.add(it) }

    for (reference in allReferences) {
        try {
            reference.handleElementRename(newName)
            reference.element.containingFile?.let { modifiedFiles.add(it) }
        } catch (_: Exception) {
            throw Exception("Could not rename reference at ${reference.element.containingFile?.virtualFile?.path}:${reference.element.textOffset}")
        }
    }

    for (override in allOverrides) {
        try {
            override.name = newName
            override.containingFile?.let { modifiedFiles.add(it) }
        } catch (_: Exception) {
            throw Exception("Could not rename override at ${override.containingFile?.virtualFile?.path}:${override.textOffset}")
        }
    }

    return modifiedFiles
}

private fun findAllValidMethods(psiFile: PsiFile): List<PsiMethod> {
    val methods = mutableListOf<PsiMethod>()
    psiFile.accept(object : PsiRecursiveElementVisitor() {
        override fun visitElement(element: PsiElement) {
            super.visitElement(element)
            if (element is PsiMethod) {
                methods.add(element)
            }
        }
    })

    val filteredMethods = methods.filter { method ->
        val psiClass = method.containingClass ?: return@filter false
        val project = method.project
        val fileIndex = ProjectFileIndex.getInstance(project)

        // If our interface extends a library interface, skip it.
        if (psiClass.isInterface) {
            val extendsLibraryInterface = psiClass.supers.any { superInterface ->
                superInterface.containingFile?.virtualFile?.let { fileIndex.isInLibrary(it) } == true
            }
            if (extendsLibraryInterface) return@filter false
        }

        // 3. Inheritance Guard:
        // Catch methods that override library-defined methods (e.g., toString, or SDK methods)
        if (method.findSuperMethods().isNotEmpty()) return@filter false

        // 4. Non-Code Usage Guard (Your existing logic)
        val references = ReferencesSearch.search(method).findAll()
        val usedInNonJavaFile = references.any { ref ->
            val fileType = ref.element.containingFile.fileType.name
            fileType != "JAVA" && fileType != "Kotlin"
        }
        if (usedInNonJavaFile) return@filter false

        // 5. Public API Guard
//        if (method.hasModifierProperty(PsiModifier.PUBLIC) && references.isEmpty()) {
//            return@filter false
//        }

        // 6. Basic Filters
        method.annotations.isEmpty() &&
                !method.isConstructor &&
                method.name != "toString" &&
                !method.name.startsWith("get") &&
                !method.name.startsWith("set") &&
                !method.name.startsWith("is")
    }

    if (filteredMethods.isNotEmpty()) {
        println("  ↳ Found ${filteredMethods.size} matching methods in ${psiFile.virtualFile?.path}")
        filteredMethods.forEach { println("    • ${it.name}") }
    }
    return filteredMethods
}
