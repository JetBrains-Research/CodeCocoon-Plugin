package com.github.pderakhshanfar.codecocoonplugin.components.transformations.renaming

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.SelfManagedTransformation
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.allowedAnnotationsOnly
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.document
import com.github.pderakhshanfar.codecocoonplugin.intellij.vfs.relativeToRootOrAbsPath
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
            // list of allowed method annotations, e.g. ["NotNull"]
            val whitelistedAnnotations = config.requireOrDefault<List<String>>("whitelistedAnnotations", defaultValue = emptyList())
            val searchInComments = config.requireOrDefault<Boolean>("searchInComments", defaultValue = false)

            val document = IntelliJAwareTransformation.withReadAction { psiFile.document() }
            val modifiedFiles = mutableSetOf<PsiFile>()
            val value = if (document != null) {
                val publicMethods: List<PsiMethod> = IntelliJAwareTransformation.withReadAction {
                    findAllValidMethods(
                        psiFile = psiFile,
                        whitelistedMethodAnnotations = whitelistedAnnotations
                    )
                }

                if (publicMethods.isEmpty()) {
                    return TransformationResult.Skipped("No matching methods found in ${virtualFile.name}")
                }

                // Group methods into overload families to ensure overloaded methods get the same name
                val overloadFamilies = IntelliJAwareTransformation.withReadAction {
                    groupMethodsByOverloads(publicMethods)
                }

                logger.info("  ⏲ Generating rename suggestions for ${publicMethods.size} methods (${overloadFamilies.size} overload families)...")

                // Generate suggestions for each overload family (not individual methods)
                val familySuggestions = runBlocking {
                    if (useMemory) {
                        extractRenamesFromMemoryForFamilies(overloadFamilies, memory, generateWhenNotInMemory)
                    } else {
                        generateRenamesForFamilies(overloadFamilies)
                    }
                }

                // memory is updated when either we generate renames anew
                // or when we POTENTIALLY generated renames for missing entries
                val saveRenamesInMemory = !useMemory || generateWhenNotInMemory

                // Track successful renames across all families
                var renamedMethodCount = 0

                // Try renaming each overload family
                for (family in overloadFamilies) {
                    val suggestions = familySuggestions[family] ?: continue
                    val familyName = IntelliJAwareTransformation.withReadAction { family.methodName }

                    // Generate signatures BEFORE renaming for all methods in the family
                    val methodSignatures = if (saveRenamesInMemory) {
                        family.methods.associateWith { method ->
                            IntelliJAwareTransformation.withReadAction {
                                PsiSignatureGenerator.generateSignature(method)
                            }
                        }
                    } else {
                        emptyMap()
                    }

                    // Try each suggestion until one succeeds for ALL methods in the family
                    var familyRenamed = false
                    for (suggestion in suggestions) {
                        // Skip if suggestion is the same as the original name (no-op rename)
                        if (suggestion == familyName) {
                            continue
                        }

                        // Attempt to rename all methods in the family to the same name
                        val allSucceeded = family.methods.all { method ->
                            val files = tryRenameMethodAndUsages(psiFile.project, method, suggestion, searchInComments)
                            if (files != null) {
                                modifiedFiles.addAll(files)

                                // Store in memory if needed (using pre-generated signature)
                                if (saveRenamesInMemory) {
                                    val signature = methodSignatures[method]
                                    if (signature != null) {
                                        memory?.put(signature, suggestion)
                                        logger.info("      ✓ Stored rename in memory: `$signature` -> `$suggestion`")
                                    } else {
                                        logger.warn("      ⊘ Could not generate signature for method before renaming")
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }

                        if (allSucceeded) {
                            renamedMethodCount += family.methods.size
                            val methodCountInfo = if (family.methods.size > 1) " (${family.methods.size} overloads)" else ""
                            logger.info("    • Renamed `$familyName` to `$suggestion`$methodCountInfo")
                            familyRenamed = true
                            break
                        }
                    }

                    if (!familyRenamed) {
                        logger.info("  ⊘ Skipped renaming method $familyName, suggestions: $suggestions")
                    }
                }

                val totalCandidates = publicMethods.size
                val skipped = totalCandidates - renamedMethodCount

                TransformationResult.Success(
                    message = "Renamed ${renamedMethodCount}/${totalCandidates} methods in ${virtualFile.name}${if (skipped > 0) " (skipped: $skipped)" else ""}",
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
     * Represents a family of overloaded methods (same name, same containing class).
     */
    private data class OverloadFamily(
        val methodName: String,
        val containingClass: PsiClass,
        val methods: List<PsiMethod>
    ) {
        /**
         * Returns a representative method for generating rename suggestions.
         * Prefers methods with bodies (non-abstract) for better context.
         */
        fun getRepresentative(): PsiMethod {
            return methods.firstOrNull { it.body != null } ?: methods.first()
        }
    }

    /**
     * Groups methods into overload families.
     * Methods with the same name in the same containing class are grouped together.
     */
    private fun groupMethodsByOverloads(methods: List<PsiMethod>): List<OverloadFamily> {
        val grouped = methods.groupBy { method ->
            val className = method.containingClass?.qualifiedName ?: ""
            val methodName = method.name
            "$className.$methodName"
        }

        return grouped.map { (_, methodsInFamily) ->
            val representative = methodsInFamily.first()
            OverloadFamily(
                methodName = representative.name,
                containingClass = representative.containingClass!!,
                methods = methodsInFamily
            )
        }
    }

    /**
     * Extracts rename suggestions from memory for overload families.
     * Returns the same suggestion for all methods in a family.
     * Checks ALL methods in the family to find cached names.
     *
     * When [generateWhenNotInMemory] is true, generates new suggestions
     * for all families whose suggestions are missing in memory.
     */
    private suspend fun extractRenamesFromMemoryForFamilies(
        families: List<OverloadFamily>,
        memory: Memory<String, String>?,
        generateWhenNotInMemory: Boolean,
    ): Map<OverloadFamily, List<String>> {
        val familiesWithMissingSuggestions = mutableListOf<OverloadFamily>()

        val suggestions = families.associateWith { family ->
            // Check all methods in the family (not just the representative)
            // This handles cases where methods were stored in different orders
            for (method in family.methods) {
                val signature = IntelliJAwareTransformation.withReadAction {
                    PsiSignatureGenerator.generateSignature(method)
                }

                if (signature == null) {
                    logger.warn("Could not generate signature for method ${family.methodName}")
                    continue
                }

                val cachedName = memory?.get(signature)
                if (cachedName != null) {
                    logger.info("  ↳ Using cached rename: $signature -> $cachedName")
                    return@associateWith listOf(cachedName)
                }
            }

            // No cached name found for any method in the family
            logger.warn("  ⊘ Signature not found in memory: ${family.methodName}")
            if (generateWhenNotInMemory) {
                familiesWithMissingSuggestions.add(family)
            }
            emptyList()
        }

        val finalSuggestions = if (generateWhenNotInMemory && familiesWithMissingSuggestions.isNotEmpty()) {
            logger.info("  ↳ Generating missing rename suggestions for ${familiesWithMissingSuggestions.size} method families (i.e., generateWhenNotInMemory=true)...")
            val generated = generateRenamesForFamilies(familiesWithMissingSuggestions)
            buildMap {
                for (family in families) {
                    val suggestionsA = suggestions[family] ?: emptyList()
                    val suggestionsB = generated[family] ?: emptyList()
                    put(family, suggestionsA + suggestionsB)
                }
            }
        } else {
            suggestions
        }

        return finalSuggestions
    }

    /**
     * Generates rename suggestions for overload families using LLM.
     * Returns the same suggestions for all methods in a family.
     */
    private suspend fun generateRenamesForFamilies(families: List<OverloadFamily>): Map<OverloadFamily, List<String>> {
        return families.associateWith { family ->
            // Generate suggestions based on the representative method
            val representative = family.getRepresentative()
            generateNewMethodNames(representative)
        }
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
        project: Project,
        method: PsiMethod,
        newName: String,
        searchInComments: Boolean,
    ): MutableSet<PsiFile>? {
        return try {
            val oldName = method.name
            val renameProcessor = IntelliJAwareTransformation.withReadAction {
                RenameProcessor(
                    /* project = */ project,
                    /* element = */ method,
                    /* newName = */ newName,
                    /* isSearchInComments= */ searchInComments,
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

    /**
     * @param psiFile The PSI file to search for methods
     * @param whitelistedMethodAnnotations A list of method annotations that are allowed to be present on the method.
     */
    private fun findAllValidMethods(
        psiFile: PsiFile,
        whitelistedMethodAnnotations: List<String>,
    ): List<PsiMethod> {
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
            // either no method annotations or whitelisted ones only
            val annotationsFilter = method.annotations.isEmpty()
                    || method.annotations.toList().allowedAnnotationsOnly(whitelistedMethodAnnotations)

            annotationsFilter &&
                    !method.isConstructor &&
                    method.name !in DISALLOWED_METHOD_NAMES &&
                    !method.name.startsWith("get") &&
                    !method.name.startsWith("set") &&
                    !method.name.startsWith("is")
        }

        if (filteredMethods.isNotEmpty()) {
            // prettify filepath attempting to make it relative to the project root
            val filepath = psiFile.virtualFile?.let { psiFile.project.relativeToRootOrAbsPath(it) } ?: "<in-memory>"
            logger.info("  ↳ Found ${filteredMethods.size} matching methods in '$filepath'")
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