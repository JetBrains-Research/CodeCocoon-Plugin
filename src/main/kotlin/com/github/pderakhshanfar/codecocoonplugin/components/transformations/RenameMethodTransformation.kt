package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.document
import com.github.pderakhshanfar.codecocoonplugin.java.JavaTransformation
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.collections.emptyList


class RenameMethodTransformation(
    override val config: Map<String, Any>
) : JavaTransformation, IntelliJAwareTransformation {
    override val id: String = ID
    override val description: String =
        "Renames all methods that match the criteria, including their usages and references."
    private val logger = thisLogger().withStdout()

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
                    return TransformationResult.Skipped("No matching methods found in ${virtualFile.name}")
                }

                val selectedRenames = publicMethods.mapNotNull { method ->
                    runBlocking {
                        val suggestions = getNewMethodNames(method)
                        val chosen = suggestions.firstOrNull { isNameAvailable(method, it) }
                        if (chosen == null) {
                            logger.warn("Skipping method ${method.name} in ${psiFile.virtualFile?.path} — no available suggestion out of ${suggestions.size} tried")
                            null
                        } else {
                            method to chosen
                        }
                    }
                }

                // Rename each method and all its usages across the project
                for ((method, newName) in selectedRenames) {
                    val files = renameMethodAndUsages(psiFile.project, method, newName)
                    modifiedFiles.addAll(files)
                }

                val renamedCount = selectedRenames.size
                val totalCandidates = publicMethods.size
                val skipped = totalCandidates - renamedCount
                TransformationResult.Success(
                    message = "Renamed ${renamedCount}/${totalCandidates} methods in ${virtualFile.name}${if (skipped > 0) " (skipped: $skipped)" else ""}",
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

    private fun isNameAvailable(method: PsiMethod, newName: String): Boolean {
        val psiClass = method.containingClass ?: return true

        // 1. Check the current class and all SUPER classes
        val existingMethods = psiClass.findMethodsByName(newName, true)
        for (existing in existingMethods) {
            if (haveSameSignature(method, existing)) return false
        }

        // 2. Check all subclasses
        val inheritors = ClassInheritorsSearch.search(psiClass).findAll()
        for (subClass in inheritors) {
            // Check if the subclass has a method that would collide with the new name
            val collisionInSub = subClass.findMethodsByName(newName, false)
            for (existing in collisionInSub) {
                if (haveSameSignature(method, existing)) return false
            }
        }

        return true
    }

    /**
     * Helper to check if two methods have the same parameter types.
     */
    private fun haveSameSignature(m1: PsiMethod, m2: PsiMethod): Boolean {
        return m1.manager.areElementsEquivalent(m1.parameterList, m2.parameterList)
    }

    @Serializable
    private data class MethodNameSuggestions(val suggestions: List<String>)

    private suspend fun getNewMethodNames(method: PsiMethod, count: Int = DEFAULT_SUGGESTED_NAMES_SIZE): List<String> {
        val methodRenamePrompt = prompt("method-rename-prompt") {
            system {
                +"You are an agent that proposes semantically similar Java method names."
                +"Your output is used in a metamorphic transformation pipeline."
                +"Your output will be parsed into JSON; strictly follow the required structure."
            }
            user {
                +"The current method name is: ${method.name}"
                +"The method body is: ${method.body?.text}"
                +"The containing class name is: ${method.containingClass?.name}"
                +"Return a JSON object with field 'suggestions' which is an ordered array of $count Java identifiers, from most to least fitting."
                +"Every suggestion must be a valid Java identifier and semantically similar to the original name."
            }
        }

        val llm = LLM.fromGrazie(OpenAIModels.Chat.GPT5Mini)
        val result = llm.structuredRequest<MethodNameSuggestions>(
            prompt = methodRenamePrompt
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
            logger.info("      -> Renaming reference in ${reference.element.containingFile?.virtualFile?.path}")
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

            // Inheritance Guard:
            // Catch methods that override methods
            if (method.findSuperMethods().isNotEmpty()) return@filter false

            // Non-Code Usage Guard
            val references = ReferencesSearch.search(method).findAll()
            val usedInNonJavaFile = references.any { ref ->
                val fileType = ref.element.containingFile.fileType.name
                fileType != "JAVA" && fileType != "Kotlin"
            }
            if (usedInNonJavaFile) return@filter false

            // Public API Guard
            if (method.hasModifierProperty(PsiModifier.PUBLIC) && references.isEmpty()) {
                return@filter false
            }

            // Is not a test
            if (fileIndex.isInTestSourceContent(psiFile.virtualFile)) return@filter false

            // Basic Filters
            method.annotations.isEmpty() &&
                    !method.isConstructor &&
                    method.name !in DISALLOWED_METHOD_NAMES &&
                    !method.name.startsWith("get") &&
                    !method.name.startsWith("set") &&
                    !method.name.startsWith("is")
        }

        if (filteredMethods.isNotEmpty()) {
            logger.info("  ↳ Found ${filteredMethods.size} matching methods in ${psiFile.virtualFile?.path}")
            filteredMethods.forEach { logger.info("    • ${it.name}") }
        }
        return filteredMethods
    }

    companion object {
        const val ID = "rename-method-transformation"

        private val DISALLOWED_METHOD_NAMES = setOf(
            "equals", "hashCode", "toString", "getClass",
            "clone", "finalize", "wait", "notify", "notifyAll"
        )

        private const val DEFAULT_SUGGESTED_NAMES_SIZE = 5
    }
}