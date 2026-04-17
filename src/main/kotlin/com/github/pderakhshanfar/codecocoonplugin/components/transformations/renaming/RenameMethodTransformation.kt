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
            val searchInComments = config.requireOrDefault<Boolean>("searchInComments", defaultValue = false)

            // Annotation filtering configuration
            val whitelistedAnnotations = config.requireOrDefault<List<String>>("whitelistedAnnotations", defaultValue = emptyList())
            val blacklistedAnnotationsRaw = config.requireOrDefault<List<String>>("blacklistedAnnotations", defaultValue = emptyList())

            // Process blacklist: merge defaults if "_default" or "default" is present
            val blacklistedAnnotations = if (blacklistedAnnotationsRaw.any { it.equals("_default", ignoreCase = true) || it.equals("default", ignoreCase = true) }) {
                logger.info("  ↳ Include default blacklisted annotations ALONG with the custom ones (i.e., '_default' or 'default' keyword in the list)")

                val customAnnotations = blacklistedAnnotationsRaw.filter { !it.equals("_default", ignoreCase = true) && !it.equals("default", ignoreCase = true) }
                (DEFAULT_BLACKLISTED_METHOD_ANNOTATIONS + customAnnotations).toList()
            } else {
                // Warn if using blacklist mode without defaults
                if (blacklistedAnnotationsRaw.isNotEmpty()) {
                    logger.warn("  ⚠ Blacklist provided without '_default' keyword - framework annotations will NOT be automatically excluded")
                }
                blacklistedAnnotationsRaw
            }

            // Auto-detect mode: if whitelistedAnnotations is provided, use whitelist mode; otherwise blacklist
            val annotationFilterMode = config.requireOrDefault<String>(
                "annotationFilterMode",
                defaultValue = if (whitelistedAnnotations.isNotEmpty()) "whitelist" else "blacklist"
            )

            val document = IntelliJAwareTransformation.withReadAction { psiFile.document() }
            val modifiedFiles = mutableSetOf<PsiFile>()
            val value = if (document != null) {
                // Find all valid method families (already grouped and filtered)
                val overloadFamilies: List<OverloadFamily> = IntelliJAwareTransformation.withReadAction {
                    findAllValidMethodFamilies(
                        psiFile = psiFile,
                        annotationFilterMode = annotationFilterMode,
                        whitelistedMethodAnnotations = whitelistedAnnotations,
                        blacklistedMethodAnnotations = blacklistedAnnotations
                    )
                }

                if (overloadFamilies.isEmpty()) {
                    return TransformationResult.Skipped("No matching method families found in ${virtualFile.name}")
                }

                val totalMethods = overloadFamilies.sumOf { it.methods.size }
                logger.info("  ⏲ Generating rename suggestions for $totalMethods methods (${overloadFamilies.size} overload families)...")

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

                // Group families by class for organized logging
                val familiesByClass = overloadFamilies.groupBy {
                    IntelliJAwareTransformation.withReadAction {
                        it.containingClass.qualifiedName ?: it.containingClass.name ?: "<anonymous>"
                    }
                }

                logger.info("  ↳ Renaming methods in ${familiesByClass.size} class(es)...")

                // Try renaming each overload family, grouped by class
                for ((className, classFamilies) in familiesByClass) {
                    logger.info("    ◆ Processing class: `$className` (${classFamilies.size} overload families):")

                    for ((familyIndex, family) in classFamilies.withIndex()) {
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
                            logger.info("       • ${familyIndex + 1}) Renaming `$familyName` overload family to `$suggestion` (${family.methods.size} overloads):")
                            val allSucceeded = family.methods.all { method ->
                                val files = tryRenameMethodAndUsages(psiFile.project, method, suggestion, searchInComments)
                                if (files != null) {
                                    modifiedFiles.addAll(files)

                                    // Store in memory if needed (using pre-generated signature)
                                    if (saveRenamesInMemory) {
                                        val signature = methodSignatures[method]
                                        if (signature != null) {
                                            memory?.put(signature, suggestion)
                                            logger.info("          ✓ Stored rename in memory: `$signature` -> `$suggestion`")
                                        } else {
                                            logger.warn("          ⊘ Could not generate signature for method before renaming")
                                        }
                                    }
                                    true
                                } else {
                                    false
                                }
                            }

                            if (allSucceeded) {
                                renamedMethodCount += family.methods.size
                                familyRenamed = true
                                break
                            }
                        }

                        if (!familyRenamed) {
                            logger.info("      ⊘ Skipped renaming method `$familyName`, suggestions: $suggestions")
                        }
                    }
                }

                val skipped = totalMethods - renamedMethodCount

                TransformationResult.Success(
                    message = "Renamed ${renamedMethodCount}/${totalMethods} methods in ${virtualFile.name}${if (skipped > 0) " (skipped: $skipped)" else ""}",
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
     * Static and instance methods are kept in separate families even if they share the same name.
     */
    private fun groupMethodsByOverloads(methods: List<PsiMethod>): List<OverloadFamily> {
        val grouped = methods.groupBy { method ->
            Triple(
                method.containingClass?.qualifiedName ?: "",
                method.name,
                method.hasModifierProperty(PsiModifier.STATIC)
            )
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
            logger.info("          • Renamed `$oldName` to `$newName` in ${modifiedFiles.size} files")
            modifiedFiles
        } catch (e: ProcessCanceledException) {
            // Must rethrow control flow exceptions
            logger.warn("Rename method and usage cancelled: ${e.message}")
            throw e
        } catch (e: Exception) {
            // Rename failed (conflicts, PSI errors, etc.) - return null to try the next suggestion
            logger.info("          ⊘ Skipped ${method.name}:\n      (Reason: ${e.message})")
            null
        }
    }

    /**
     * Checks if annotations pass the configured filter mode (whitelist or blacklist).
     *
     * @param annotations List of annotations to check
     * @param filterMode "whitelist" or "blacklist"
     * @param whitelistedAnnotations Annotations to allow (when mode = whitelist)
     * @param blacklistedAnnotations Annotations to forbid (when mode = blacklist)
     * @return true if annotations pass the filter, false otherwise
     */
    private fun passesAnnotationFilter(
        annotations: List<PsiAnnotation>,
        filterMode: String,
        whitelistedAnnotations: List<String>,
        blacklistedAnnotations: List<String>,
    ): Boolean {
        if (annotations.isEmpty()) {
            return true
        }

        return when (filterMode.lowercase()) {
            "whitelist" -> {
                // All annotations must be in the whitelist
                annotations.all { annotation ->
                    val qualifiedName = annotation.qualifiedName
                    val simpleName = qualifiedName?.substringAfterLast('.')
                    (qualifiedName != null) && (qualifiedName in whitelistedAnnotations || simpleName in whitelistedAnnotations)
                }
            }
            "blacklist" -> {
                // No annotations can be in the blacklist
                annotations.none { annotation ->
                    val qualifiedName = annotation.qualifiedName
                    val simpleName = qualifiedName?.substringAfterLast('.')
                    qualifiedName in blacklistedAnnotations || simpleName in blacklistedAnnotations
                }
            }
            else -> {
                logger.warn("    ⚠ Unknown annotation filter mode: '$filterMode', defaulting to blacklist")
                // Default to blacklist mode with empty list (allow all)
                true
            }
        }
    }

    /**
     * Collects all methods from the PSI file without any filtering.
     */
    private fun collectAllMethods(psiFile: PsiFile): List<PsiMethod> {
        val methods = mutableListOf<PsiMethod>()
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element is PsiMethod) {
                    methods.add(element)
                }
            }
        })
        return methods
    }

    /**
     * Checks if a single method passes all filtering criteria.
     * Returns true if the method should be included, false otherwise.
     */
    private fun passesMethodFilters(
        method: PsiMethod,
        psiFile: PsiFile,
        annotationFilterMode: String,
        whitelistedMethodAnnotations: List<String>,
        blacklistedMethodAnnotations: List<String>,
    ): Boolean {
        val psiClass = method.containingClass
        if (psiClass == null) {
            logger.info("      ⊘ Method `${method.name}` - skipped (no containing class)")
            return false
        }

        val project = method.project
        val fileIndex = ProjectFileIndex.getInstance(project)

        // If our interface extends a library interface, skip it.
        // FIX: Filter out java.lang.Object which is implicitly extended by all interfaces
        if (psiClass.isInterface) {
            val extendsLibraryInterface = psiClass.supers.any { superInterface ->
                val qualifiedName = superInterface.qualifiedName
                // Skip java.lang.Object (implicitly extended by all interfaces)
                if (qualifiedName == "java.lang.Object") {
                    return@any false
                }
                superInterface.containingFile?.virtualFile?.let { fileIndex.isInLibrary(it) } == true
            }
            if (extendsLibraryInterface) {
                logger.info("      ⊘ Method `${method.name}` - skipped (interface extends library interface)")
                return false
            }
        }

        // Note: Override check is now handled in findAllValidMethodFamilies() BEFORE grouping
        // This prevents override methods from contaminating overload families with static methods

        // Non-Code Usage Guard
        val references = ReferencesSearch.search(method).findAll()
        val usedInNonJavaFile = references.any { ref ->
            val fileType = ref.element.containingFile.fileType.name
            fileType != "JAVA" && fileType != "Kotlin"
        }
        if (usedInNonJavaFile) {
            logger.info("      ⊘ Method `${method.name}` - skipped (used in non-Java file)")
            return false
        }

        // Is not a test
        if (fileIndex.isInTestSourceContent(psiFile.virtualFile)) {
            logger.info("      ⊘ Method `${method.name}` - skipped (in test source)")
            return false
        }

        // Annotation filter
        val methodAnnotations = method.annotations.toList()
        val annotationsPass = passesAnnotationFilter(
            methodAnnotations,
            annotationFilterMode,
            whitelistedMethodAnnotations,
            blacklistedMethodAnnotations
        )

        // Log annotation filtering for methods with annotations
        if (methodAnnotations.isNotEmpty()) {
            val annotationNames = methodAnnotations.mapNotNull { it.qualifiedName?.substringAfterLast('.') }
            if (annotationsPass) {
                val modeText = if (annotationFilterMode == "whitelist") "whitelisted" else "allowed"
                logger.info("      ✓ Method `${method.name}` with annotations [${annotationNames.joinToString(", ")}] - $modeText")
            } else {
                val modeText = if (annotationFilterMode == "whitelist") "not whitelisted" else "blacklisted"
                logger.info("      ⊘ Method `${method.name}` with annotations [${annotationNames.joinToString(", ")}] - skipped ($modeText)")
                return false
            }
        }

        // Constructor check
        if (method.isConstructor) {
            logger.info("      ⊘ Method `${method.name}` - skipped (is constructor)")
            return false
        }

        // Disallowed method names
        if (method.name in DISALLOWED_METHOD_NAMES) {
            logger.info("      ⊘ Method `${method.name}` - skipped (disallowed method name)")
            return false
        }

        // Getter/setter/is prefix check
        if (method.name.startsWith("get") || method.name.startsWith("set") || method.name.startsWith("is")) {
            logger.info("      ⊘ Method `${method.name}` - skipped (getter/setter/is prefix)")
            return false
        }

        return true
    }

    /**
     * Filters overload families where ALL methods in the family pass the filters.
     * If any method in a family fails a filter, the entire family is excluded.
     */
    private fun filterValidFamilies(
        families: List<OverloadFamily>,
        psiFile: PsiFile,
        annotationFilterMode: String,
        whitelistedMethodAnnotations: List<String>,
        blacklistedMethodAnnotations: List<String>
    ): List<OverloadFamily> {
        return families.filter { family ->
            // Check if ALL methods in the family pass filters
            val allMethodsValid = family.methods.all { method ->
                passesMethodFilters(
                    method,
                    psiFile,
                    annotationFilterMode,
                    whitelistedMethodAnnotations,
                    blacklistedMethodAnnotations,
                )
            }

            if (!allMethodsValid) {
                logger.info("    ⊘ Overload family `${family.methodName}` (${family.methods.size} methods) - skipped (one or more methods filtered out)")
            }

            allMethodsValid
        }
    }

    /**
     * Finds all valid method families in the PSI file.
     * Returns overload families where ALL methods pass filtering criteria.
     *
     * @param psiFile The PSI file to search for methods
     * @param annotationFilterMode "whitelist" or "blacklist"
     * @param whitelistedMethodAnnotations A list of method annotations that are allowed (whitelist mode)
     * @param blacklistedMethodAnnotations A list of method annotations that are forbidden (blacklist mode)
     * @return List of valid overload families
     */
    private fun findAllValidMethodFamilies(
        psiFile: PsiFile,
        annotationFilterMode: String,
        whitelistedMethodAnnotations: List<String>,
        blacklistedMethodAnnotations: List<String>,
    ): List<OverloadFamily> {
        // Log annotation filter configuration
        logger.info("  ↳ Annotation filter mode: $annotationFilterMode")
        when (annotationFilterMode.lowercase()) {
            "whitelist" -> {
                if (whitelistedMethodAnnotations.isNotEmpty()) {
                    logger.info("  ↳ Whitelisted method annotations: ${whitelistedMethodAnnotations.joinToString(", ")}")
                } else {
                    logger.info("  ↳ Whitelist mode active with empty list (only non-annotated methods allowed)")
                }
            }
            "blacklist" -> {
                if (blacklistedMethodAnnotations.isNotEmpty()) {
                    logger.info("  ↳ Blacklisted method annotations: [\n${blacklistedMethodAnnotations.joinToString(",\n") { "\t$it" } }\n]")
                } else {
                    logger.info("  ↳ Blacklist mode active with empty list (all annotations allowed)")
                }
            }
        }

        // Step 1: Collect all methods without filtering
        val allMethods = collectAllMethods(psiFile)

        // Step 2: Filter out override methods BEFORE grouping
        // Override methods must keep their original names to maintain inheritance contracts
        val nonOverrideMethods = allMethods.filter { method ->
            val superMethods = method.findSuperMethods()
            if (superMethods.isNotEmpty()) {
                val signature = IntelliJAwareTransformation.withReadAction {
                    PsiSignatureGenerator.generateSignature(method)
                }
                logger.info("    ⊘ Method `${method.name}` ($signature) - skipped (overrides super method from `${superMethods.firstOrNull()?.containingClass?.qualifiedName}`)")
                false
            } else {
                true
            }
        }

        logger.info("  ↳ Found ${allMethods.size} total methods, ${nonOverrideMethods.size} non-override methods")

        // Step 3: Group into overload families (now without overrides)
        val allFamilies = groupMethodsByOverloads(nonOverrideMethods)

        // Analyze classes involved
        val uniqueClasses = allFamilies.map { it.containingClass }.distinctBy { it.qualifiedName }
        val classCount = uniqueClasses.size

        logger.info("  ↳ Grouped into ${allFamilies.size} overload families from $classCount class(es) (static/instance separate)")

        // Print family details grouped by class
        if (allFamilies.isNotEmpty()) {
            logger.info("  ↳ Overload families by class:")

            // Group families by containing class
            val familiesByClass = allFamilies.groupBy { it.containingClass.qualifiedName ?: it.containingClass.name ?: "<anonymous>" }

            for ((className, families) in familiesByClass) {
                val totalMethods = families.sumOf { it.methods.size }
                logger.info("    ◆ Class: $className (${families.size} families, $totalMethods methods)")

                for (family in families) {
                    val isStatic = family.methods.firstOrNull()?.hasModifierProperty(PsiModifier.STATIC)
                    val modifier = when (isStatic) {
                        null -> "unknown"
                        true -> "static"
                        else -> "instance"
                    }
                    logger.info("      • ${family.methodName} [$modifier, ${family.methods.size} overload(s)]:")

                    val signatures = IntelliJAwareTransformation.withReadAction {
                        family.methods.mapNotNull { method ->
                            PsiSignatureGenerator.generateSignature(method)
                        }
                    }

                    val displayLimit = 10
                    signatures.take(displayLimit).forEach { signature ->
                        logger.info("          $signature")
                    }

                    if (signatures.size > displayLimit) {
                        val remaining = signatures.size - displayLimit
                        logger.info("          ... ($remaining more, ${signatures.size} total)")
                    }
                }
            }
        }

        // Step 4: Filter families (all methods in family must pass remaining filters)
        val validFamilies = filterValidFamilies(
            allFamilies,
            psiFile,
            annotationFilterMode,
            whitelistedMethodAnnotations,
            blacklistedMethodAnnotations,
        )

        if (validFamilies.isNotEmpty()) {
            val validMethodCount = validFamilies.sumOf { it.methods.size }
            val filepath = psiFile.virtualFile?.let { psiFile.project.relativeToRootOrAbsPath(it) } ?: "<in-memory>"
            logger.info("  ↳ After filtering: ${validFamilies.size} valid families with $validMethodCount methods in '$filepath'")
        }

        return validFamilies
    }

    companion object {
        const val ID = "rename-method-transformation"

        private val DISALLOWED_METHOD_NAMES = setOf(
            "equals", "hashCode", "toString", "getClass",
            "clone", "finalize", "wait", "notify", "notifyAll"
        )

        /**
         * Default blacklisted method annotations (framework/infrastructure annotations).
         * These annotations typically indicate methods that are called by frameworks/containers,
         * so renaming them could break runtime behavior.
         */
        val DEFAULT_BLACKLISTED_METHOD_ANNOTATIONS = setOf(
            // JPA/Hibernate Lifecycle
            "javax.persistence.PrePersist",
            "javax.persistence.PostPersist",
            "javax.persistence.PreUpdate",
            "javax.persistence.PostUpdate",
            "javax.persistence.PreRemove",
            "javax.persistence.PostRemove",
            "javax.persistence.PostLoad",

            // Spring Framework
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.transaction.annotation.Transactional",
            "org.springframework.scheduling.annotation.Scheduled",
            "org.springframework.cache.annotation.Cacheable",
            "org.springframework.cache.annotation.CacheEvict",
            "org.springframework.context.event.EventListener",
            "org.springframework.jmx.export.annotation.ManagedOperation",

            // JAX-RS (REST APIs)
            "javax.ws.rs.GET",
            "javax.ws.rs.POST",
            "javax.ws.rs.PUT",
            "javax.ws.rs.DELETE",
            "javax.ws.rs.Path",
            "jakarta.ws.rs.GET",
            "jakarta.ws.rs.POST",
            "jakarta.ws.rs.PUT",
            "jakarta.ws.rs.DELETE",
            "jakarta.ws.rs.Path",

            // Jackson (JSON)
            "com.fasterxml.jackson.annotation.JsonGetter",
            "com.fasterxml.jackson.annotation.JsonSetter",
            "com.fasterxml.jackson.annotation.JsonProperty",
            "com.fasterxml.jackson.annotation.JsonCreator",

            // JavaFX/Swing
            "javafx.fxml.FXML",

            // JUnit/Testing Lifecycle
            "org.junit.jupiter.api.BeforeEach",
            "org.junit.jupiter.api.AfterEach",
            "org.junit.jupiter.api.BeforeAll",
            "org.junit.jupiter.api.AfterAll",
            "org.junit.Test",
            "org.junit.Before",
            "org.junit.After",
            "org.junit.BeforeClass",
            "org.junit.AfterClass"
        )

        private const val DEFAULT_SUGGESTED_NAMES_SIZE = 5
    }
}