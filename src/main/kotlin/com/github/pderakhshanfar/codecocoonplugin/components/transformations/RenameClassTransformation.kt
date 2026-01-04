package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.document
import com.github.pderakhshanfar.codecocoonplugin.java.JavaTransformation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

class RenameClassTransformation(
    override val config: Map<String, Any>
) : JavaTransformation, IntelliJAwareTransformation {
    override val id: String = ID
    override val description: String = "Renames a class and all of its usages/references"

    override fun apply(
        psiFile: PsiFile, virtualFile: VirtualFile
    ): TransformationResult {
        val result = try {
            val document = psiFile.document()
            val modifiedFiles = mutableSetOf<PsiFile>()
            val value = if (document != null) {
                val eligibleClasses: List<PsiClass> = findAllValidClasses(psiFile)

                if (eligibleClasses.isEmpty()) {
                    return TransformationResult.Skipped("No matching classes found in ${virtualFile.name}")
                }

                val newNames = eligibleClasses.mapNotNull { psiClass ->
                    runBlocking {
                        val suggestedName = getNewClassName(psiClass)

                        // Validation check
                        if (isNameAvailable(psiClass, suggestedName)) {
                            psiClass to suggestedName
                        } else {
                            // Fallback: try adding a suffix or skip
                            val fallbackName = "${suggestedName}Internal"
                            if (isNameAvailable(psiClass, fallbackName)) {
                                psiClass to fallbackName
                            } else {
                                null // Skip this class to avoid compilation errors
                            }
                        }
                    }
                }

                // Rename each class and all its usages across the project
                for ((psiClass, newName) in newNames) {
                    val files = renameClassAndUsages(psiFile.project, psiClass, newName)
                    modifiedFiles.addAll(files)
                }

                TransformationResult.Success(
                    message = "Renamed ${eligibleClasses.size} classes in ${virtualFile.name}",
                    filesModified = modifiedFiles.size
                )
            } else {
                TransformationResult.Failure(
                    "Could not get document for file ${psiFile.name}"
                )
            }
            value
        } catch (e: Exception) {
            TransformationResult.Failure("Failed to rename classes in file ${virtualFile.name}", e)
        }
        return result
    }

    @Serializable
    data class ClassName(val name: String)

    private suspend fun getNewClassName(psiClass: PsiClass): String {
        val classRenamePrompt = prompt("class-rename-prompt") {
            system {
                +"You are an agents used for finding semantically similar class names in Java codebases."
                +"Your output is used in a metamorphic transformation pipeline."
            }
            user {
                +"The class name is: ${psiClass.name}"
                +if (psiClass.isInterface) "The class is an interface." else if (psiClass.isEnum) "This is an ENUM." else ""
                +"The methods in the class are: ${psiClass.methods.joinToString(", ") { it.name }}"
                +"All fields in the class are: ${psiClass.allFields.joinToString(", ") { it.name }}"
                +"Return the new class name that is a valid Java identifier. The new name must be sematically similar to the old name."
            }
        }

        val llm = LLM.fromGrazie(OpenAIModels.Chat.GPT5Mini, System.getenv("GRAZIE_TOKEN"))
        val result = llm.structuredRequest<ClassName>(
            prompt = classRenamePrompt
        )

        return result!!.name
    }

    private fun isNameAvailable(psiClass: PsiClass, newName: String): Boolean {
        val project = psiClass.project
        val packageName = (psiClass.containingFile as? PsiJavaFile)?.packageName ?: ""

        val nameHelper = PsiNameHelper.getInstance(project)

        if (!nameHelper.isIdentifier(newName)) return false

        // Construct the full qualified name for the potential new class
        val newQualifiedName = if (packageName.isEmpty()) newName else "$packageName.$newName"

        // Check if a class with this qualified name already exists in the project
        val facade = JavaPsiFacade.getInstance(project)
        val existingClass = facade.findClass(newQualifiedName, GlobalSearchScope.allScope(project))

        if (existingClass != null) {
            return false // A class with this name already exists in this package
        }

        // 3. Check for Inner Class collisions
        // If the class is nested, check its siblings within the parent class
        val parent = psiClass.parent
        if (parent is PsiClass) {
            val siblingClass = parent.findInnerClassByName(newName, false)
            if (siblingClass != null) return false
        }

        return true
    }

    private fun renameClassAndUsages(
        project: Project, psiClass: PsiClass, newName: String
    ): MutableSet<PsiFile> {
        val searchScope = GlobalSearchScope.projectScope(project)
        val allReferences = ReferencesSearch.search(psiClass, searchScope).findAll().toList()

        val modifiedFiles = mutableSetOf<PsiFile>()

        psiClass.setName(newName)
        psiClass.containingFile?.let { modifiedFiles.add(it) }

        for (reference in allReferences) {
            var containingFile: String = reference.element.containingFile?.virtualFile?.path ?: "unknown"
            if (containingFile.contains("/src/")) {
                containingFile = containingFile.substringAfter("/src/")
            }
            println("      -> Renaming reference in $containingFile")
            try {
                reference.handleElementRename(newName)
                reference.element.containingFile?.let { modifiedFiles.add(it) }
            } catch (_: Exception) {
                throw Exception("Could not rename reference at ${reference.element.containingFile?.virtualFile?.path}:${reference.element.textOffset}")
            }
        }

        return modifiedFiles
    }


    private fun findAllValidClasses(psiFile: PsiFile): List<PsiClass> {
        val classes = mutableListOf<PsiClass>()
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element is PsiClass) {
                    classes.add(element)
                }
            }
        })


        val filteredClasses = classes.filter { cls ->
            val project = cls.project
            val fileIndex = ProjectFileIndex.getInstance(project)

            // Non-Code Usage Guard
            val references = ReferencesSearch.search(cls).findAll()
            val usedInNonJavaFile = references.any { ref ->
                val fileType = ref.element.containingFile.fileType.name
                fileType != "JAVA" && fileType != "Kotlin"
            }
            if (usedInNonJavaFile) return@filter false

            // Is not a test
            if (fileIndex.isInTestSourceContent(psiFile.virtualFile)) return@filter false

            // Basic Filters
            cls.annotations.isEmpty()
        }

        if (filteredClasses.isNotEmpty()) {
            println("  ↳ Found ${filteredClasses.size} matching classes in ${psiFile.virtualFile?.path}")
            filteredClasses.forEach { println("    • ${it.name}") }
        }
        return filteredClasses
    }

    companion object {
        const val ID = "rename-class-transformation"
    }
}