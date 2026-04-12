package com.github.pderakhshanfar.codecocoonplugin.components.transformations.renaming

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.SelfManagedTransformation
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.document
import com.github.pderakhshanfar.codecocoonplugin.java.JavaTransformation
import com.github.pderakhshanfar.codecocoonplugin.memory.Memory
import com.github.pderakhshanfar.codecocoonplugin.memory.PsiSignatureGenerator
import com.github.pderakhshanfar.codecocoonplugin.transformation.requireOrDefault
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.collections.emptyList


class RenameMethodTransformation(
    override val config: Map<String, Any>
) : JavaTransformation, SelfManagedTransformation() {
    override val id: String = ID
    override val description: String =
        "Renames all methods that match the criteria, including their usages and references."
    private val logger = thisLogger().withStdout()

    override fun accepts(context: FileContext): Boolean {
        return super.accepts(context)
    }

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile,
        memory: Memory<String, String>?
    ): TransformationResult {
        val result = try {
            val useMemory = config.requireOrDefault<Boolean>("useMemory", defaultValue = false)
            val generateWhenNotInMemory = config.requireOrDefault<Boolean>("generateWhenNotInMemory", defaultValue = false)

            val document = IntelliJAwareTransformation.withReadAction { psiFile.document() }
            val modifiedFiles = mutableSetOf<PsiFile>()
            val value = if (document != null) {
                val publicMethods: List<PsiMethod> = IntelliJAwareTransformation.withReadAction {
                    findAllValidMethods(psiFile)
                }

                if (publicMethods.isEmpty()) {
                    return TransformationResult.Skipped("No matching methods found in ${virtualFile.name}")
                }

                logger.info("  ⏲ Generating rename suggestions for ${publicMethods.size} methods...")

                val renaming = runBlocking {
                    if (useMemory) {
                        extractRenamesFromMemory(publicMethods, memory, generateWhenNotInMemory)
                    } else {
                        generateRenames(publicMethods)
                    }
                }

                // memory is updated when either we generate renames anew
                // or when we POTENTIALLY generated renames for missing entries
                val saveRenamesInMemory = !useMemory || generateWhenNotInMemory


                // Try renaming each method with suggestions until one succeeds
                val successfulRenames = publicMethods.mapNotNull { method ->
                    val methodName = IntelliJAwareTransformation.withReadAction { method.name }
                    val suggestions = renaming.suggestions[method] ?: return@mapNotNull null

                    // Generate signature BEFORE renaming
                    val signature = IntelliJAwareTransformation.withReadAction {
                        PsiSignatureGenerator.generateSignature(method)
                    }
                    if (signature == null) {
                        logger.warn("  ⊘ Could not generate signature for method $methodName")
                        return@mapNotNull null
                    }

                    // Try each suggestion until one succeeds (no conflicts)
                    for (suggestion in suggestions) {
                        val files = tryRenameMethodAndUsages(psiFile.project, method, suggestion)
                        if (files != null) {
                            modifiedFiles.addAll(files)
                            if (saveRenamesInMemory) {
                                memory?.put(signature, suggestion)
                                logger.info("      ✓ Stored rename in memory: `$signature` -> `$suggestion`")
                            }
                            return@mapNotNull method to suggestion
                        }
                    }
                    // No valid suggestion worked
                    logger.info("  ⊘ Skipped renaming method `$methodName` (suggestions: $suggestions)")
                    null
                }

                val renamedCount = successfulRenames.size
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

    @Serializable
    private data class MethodNameSuggestions(val suggestions: List<String>)

    private data class MethodContext(
        val methodName: String,
        val methodBody: String?,
        val className: String?
    )

    /**
     * Extracts rename suggestions from memory for methods.
     *
     * When [generateWhenNotInMemory] is true, generates new suggestions
     * for all methods whose suggestions are missing in memory.
     */
    private suspend fun extractRenamesFromMemory(
        methods: List<PsiMethod>,
        memory: Memory<String, String>?,
        generateWhenNotInMemory: Boolean,
    ): Renaming<PsiMethod> {
        val methodsWithMissingSuggestions = mutableListOf<PsiMethod>()

        val suggestions = methods.associateWith { method ->
            val signature = IntelliJAwareTransformation.withReadAction {
                PsiSignatureGenerator.generateSignature(method)
            }
            if (signature == null) {
                logger.warn("Could not generate signature for method")
                return@associateWith emptyList()
            }

            val cachedName = memory?.get(signature)
            if (cachedName != null) {
                logger.info("  ↳ Using cached rename: $signature -> $cachedName")
                listOf(cachedName)
            } else {
                logger.warn("  ⊘ Signature not found in memory: $signature")
                if (generateWhenNotInMemory) {
                    methodsWithMissingSuggestions.add(method)
                }
                emptyList()
            }
        }

        val finalSuggestions = if (generateWhenNotInMemory && methodsWithMissingSuggestions.isNotEmpty()) {
            logger.info("  ↳ Generating missing rename suggestions for ${methodsWithMissingSuggestions.size} methods (i.e., generateWhenNotInMemory=true)...")
            val generated = generateRenames(methodsWithMissingSuggestions)
            buildMap {
                for (method in methods) {
                    val suggestionsA = suggestions[method] ?: emptyList()
                    val suggestionsB = generated.suggestions[method] ?: emptyList()
                    put(method, suggestionsA + suggestionsB)
                }
            }
        } else {
            suggestions
        }

        return Renaming(finalSuggestions)
    }

    /**
     * Generates rename suggestions for all methods using LLM.
     */
    private suspend fun generateRenames(methods: List<PsiMethod>): Renaming<PsiMethod> {
        val suggestions = methods.associateWith { method ->
            generateNewMethodNames(method)
        }
        return Renaming(suggestions)
    }

    private suspend fun generateNewMethodNames(method: PsiMethod, count: Int = DEFAULT_SUGGESTED_NAMES_SIZE): List<String> {
        // Extract all PSI data in a read action before building the prompt
        val context = readAction {
            MethodContext(
                methodName = method.name,
                methodBody = method.body?.text,
                className = method.containingClass?.name
            )
        }

        val methodRenamePrompt = prompt("method-rename-prompt") {
            system {
                +"You are an agent that proposes semantically similar Java method names."
                +"Your output is used in a metamorphic transformation pipeline."
                +"Your output will be parsed into JSON; strictly follow the required structure."
            }
            user {
                +"The current method name is: ${context.methodName}"
                +"The method body is: ${context.methodBody}"
                +"The containing class name is: ${context.className}"
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

    private fun tryRenameMethodAndUsages(
        project: Project, method: PsiMethod, newName: String
    ): MutableSet<PsiFile>? {
        return try {
            val oldName = method.name
            val renameProcessor = IntelliJAwareTransformation.withReadAction {
                RenameProcessor(
                    /* project = */ project,
                    /* element = */ method,
                    /* newName = */ newName,
                    /* isSearchInComments= */ true,
                    /* isSearchTextOccurrences = */ false
                )
            }

            ApplicationManager.getApplication().invokeAndWait {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                renameProcessor.run()
            }

            val modifiedFiles = IntelliJAwareTransformation.withReadAction {
                val files = mutableSetOf<PsiFile>()
                renameProcessor.findUsages().forEach { usageInfo ->
                    usageInfo.file?.let { files.add(it) }
                }
                method.containingFile?.let { files.add(it) }
                files
            }
            logger.info("    • Renamed `$oldName` to `$newName` in ${modifiedFiles.size} files")
            modifiedFiles
        } catch (e: ProcessCanceledException) {
            // Must rethrow control flow exceptions
            logger.warn("Rename method and usage cancelled: ${e.message}")
            throw e
        } catch (e: Exception) {
            // Rename failed (conflicts, PSI errors, etc.) - return null to try the next suggestion
            logger.info("    • Skipped ${method.name}:\n      (Reason: ${e.message})")
            null
        }
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