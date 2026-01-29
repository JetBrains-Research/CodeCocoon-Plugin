package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.document
import com.github.pderakhshanfar.codecocoonplugin.java.JavaTransformation
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
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
    private val logger = thisLogger().withStdout()

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

                val selectedRenames = eligibleClasses.mapNotNull { psiClass ->
                    runBlocking {
                        val suggestions = getNewClassNames(psiClass)
                        val chosen = suggestions.firstOrNull { isNameAvailable(psiClass, it) }
                        if (chosen == null) {
                            logger.warn("Skipping class ${psiClass.name} in ${psiFile.virtualFile?.path} — no available suggestion out of ${suggestions.size} tried")
                            null
                        } else {
                            psiClass to chosen
                        }
                    }
                }

                // Rename each class and all its usages across the project
                for ((psiClass, newName) in selectedRenames) {
                    val files = renameClassAndUsages(psiFile.project, psiClass, newName)
                    modifiedFiles.addAll(files)
                }

                val renamedCount = selectedRenames.size
                val totalCandidates = eligibleClasses.size
                val skipped = totalCandidates - renamedCount
                TransformationResult.Success(
                    message = "Renamed ${renamedCount}/${totalCandidates} classes in ${virtualFile.name}${if (skipped > 0) " (skipped: $skipped)" else ""}",
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
    private data class ClassNameSuggestions(val suggestions: List<String>)

    private suspend fun getNewClassNames(psiClass: PsiClass, count: Int = DEFAULT_SUGGESTED_NAMES_SIZE): List<String> {
        val classType = when {
            psiClass.isInterface -> "interface"
            psiClass.isEnum -> "enum class"
            psiClass.hasModifierProperty(PsiModifier.ABSTRACT) -> "abstract class"
            else -> "class"
        }
        val classRenamePrompt = prompt("class-rename-prompt") {
            system {
                +"You are an agent that proposes semantically similar Java class names."
                +"Your output is used in a metamorphic transformation pipeline."
                +"Your output will be parsed into JSON; strictly follow the required structure."
            }
            user {
                +"The name of the $classType is: ${psiClass.name}"
                if (psiClass.methods.isNotEmpty()) +"The methods in the class are: ${psiClass.methods.joinToString(", ") { it.name }}"
                if (psiClass.allFields.isNotEmpty()) +"All fields in the class are: ${psiClass.allFields.joinToString(", ") { it.name }}"
                +"Return a JSON object with field 'suggestions' which is an ordered array of $count Java identifiers, from most to least fitting."
                +"Every suggestion must be a valid Java identifier and semantically similar to the original name."
            }

        }

        val llm = LLM.fromGrazie(OpenAIModels.Chat.GPT5Mini)
        val result = llm.structuredRequest<ClassNameSuggestions>(
            prompt = classRenamePrompt
        )

        return if (result != null) buildSuggestionList(result.suggestions) else emptyList()
    }

    private fun buildSuggestionList(rawSuggestions: List<String>): List<String> {
        val normalized = rawSuggestions
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        if (normalized.isEmpty()) return emptyList()

        val firstSuggestion = normalized.first()
        val internalFallback = "${firstSuggestion}Internal"

        return if (normalized.contains(internalFallback)) normalized else normalized + internalFallback
    }

    private fun isNameAvailable(psiClass: PsiClass, newName: String): Boolean {
        val project = psiClass.project
        val packageName = (psiClass.containingFile as? PsiJavaFile)?.packageName ?: ""

        val nameHelper = PsiNameHelper.getInstance(project)

        if (!nameHelper.isIdentifier(newName)) return false

        // Construct the full qualified name for the potential new class
        val newQualifiedName = if (packageName.isEmpty()) newName else "$packageName.$newName"

        // Check if a class with this qualified name already exists in the project
        val existingClass = JavaPsiFacade.getInstance(project)
            .findClass(newQualifiedName, GlobalSearchScope.allScope(project))

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

    /**
     * Renames a class and updates all its usages within the project, ensuring consistency
     * across the codebase. The method modifies the class name and refactors all references
     * to reflect the new name. It also (automatically) renames the containing file.
     *
     * @param project IntelliJ project within which the class and its usages are being refactored.
     * @param psiClass The class to be renamed.
     * @param newName The new name to be assigned to the class.
     * @return A set of PsiFiles that were modified during the renaming process.
     */
    private fun renameClassAndUsages(
        project: Project, psiClass: PsiClass, newName: String
    ): MutableSet<PsiFile> {
        val searchScope = GlobalSearchScope.projectScope(project)
        val allReferences = ReferencesSearch.search(psiClass, searchScope).findAll().toList()

        val modifiedFiles = mutableSetOf<PsiFile>()

        val oldClassName = psiClass.name

        // This also renames the containing file
        psiClass.setName(newName)
        psiClass.containingFile?.let { modifiedFiles.add(it) }

        for (reference in allReferences) {
            var containingFile: String = reference.element.containingFile?.virtualFile?.path ?: "unknown"
            if (containingFile.contains("/src/")) {
                containingFile = containingFile.substringAfter("/src/")
            }
            logger.info("      -> Renaming reference in $containingFile")
            try {
                reference.handleElementRename(newName)
                reference.element.containingFile?.let { modifiedFiles.add(it) }
            } catch (_: Exception) {
                throw Exception("Could not rename reference of class ${oldClassName} to ${newName} at ${reference.element.containingFile?.virtualFile?.path}:${reference.element.textOffset}")
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
            cls.annotations.isEmpty() && cls.name!!.length > 1
        }

        if (filteredClasses.isNotEmpty()) {
            logger.info("  ↳ Found ${filteredClasses.size} matching classes in ${psiFile.virtualFile?.path}")
            filteredClasses.forEach { logger.info("    • ${it.name}") }
        }
        return filteredClasses
    }

    companion object {
        const val ID = "rename-class-transformation"
        private const val DEFAULT_SUGGESTED_NAMES_SIZE = 3
    }
}