package com.github.pderakhshanfar.codecocoonplugin.components.transformations.renaming

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation.Companion.withReadAction
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.SelfManagedTransformation
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
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
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

/**
 * Renames Java variables to an LLM-suggested, semantically similar name and updates usages.
 *
 * Skips: variables in test classes, enums, and those declared in library-files.
 */
class RenameVariableTransformation(
    override val config: Map<String, Any>
) : JavaTransformation, SelfManagedTransformation() {
    override val id: String = ID
    override val description: String = "Renames variables (fields, locals, parameters) and their usages"
    private val logger = thisLogger().withStdout()

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile,
        memory: Memory<String, String>?
    ): TransformationResult {
        val result = try {
            val useMemory = config.requireOrDefault<Boolean>("useMemory", defaultValue = false)
            val generateWhenNotInMemory = config.requireOrDefault<Boolean>("generateWhenNotInMemory", defaultValue = false)
            val searchInComments = config.requireOrDefault<Boolean>("searchInComments", defaultValue = false)

            // Annotation filtering configuration (blacklist only - no whitelist support)
            val blacklistedAnnotationsRaw = config.requireOrDefault<List<String>>("blacklistedAnnotations", defaultValue = emptyList())

            // Process blacklist: merge defaults if "_default" or "default" is present
            val blacklistedAnnotations = if (blacklistedAnnotationsRaw.any { it.equals("_default", ignoreCase = true) || it.equals("default", ignoreCase = true) }) {
                logger.info("  ↳ Include default blacklisted annotations ALONG with the custom ones (i.e., '_default' or 'default' keyword in the list)")

                val customAnnotations = blacklistedAnnotationsRaw.filter { !it.equals("_default", ignoreCase = true) && !it.equals("default", ignoreCase = true) }
                (DEFAULT_BLACKLISTED_VARIABLE_ANNOTATIONS + customAnnotations).toList()
            } else {
                // Warn if using blacklist mode without defaults
                if (blacklistedAnnotationsRaw.isNotEmpty()) {
                    logger.warn("  ⚠ Blacklist provided without '_default' keyword - framework annotations will NOT be automatically excluded")
                }
                blacklistedAnnotationsRaw
            }

            val document = withReadAction { psiFile.document() }
            val modifiedFiles = mutableSetOf<PsiFile>()
            val value = if (document != null) {
                val eligibleVariables: List<PsiVariable> = withReadAction {
                    findAllValidVariables(psiFile, blacklistedAnnotations)
                }

                if (eligibleVariables.isEmpty()) {
                    return TransformationResult.Skipped("No matching variables found in ${virtualFile.name}")
                }

                logger.info("  ⏲ Generating rename suggestions for ${eligibleVariables.size} variables...")

                val renaming = runBlocking {
                    if (useMemory) {
                        extractRenamesFromMemory(eligibleVariables, memory, generateWhenNotInMemory)
                    } else {
                        generateRenames(eligibleVariables)
                    }
                }

                // memory is updated when either we generate renames anew
                // or when we POTENTIALLY generated renames for missing entries
                val saveRenamesInMemory = !useMemory || generateWhenNotInMemory

                // Try renaming each variable with suggestions until one succeeds
                val successfulRenames = eligibleVariables.mapNotNull { psiVar ->
                    val varName = withReadAction { psiVar.name }
                    val suggestions = renaming.suggestions[psiVar] ?: return@mapNotNull null

                    // Generate signature BEFORE renaming
                    val signature = withReadAction { PsiSignatureGenerator.generateSignature(psiVar) }
                    if (signature == null) {
                        logger.warn("    ⊘ Could not generate signature for variable $varName")
                        return@mapNotNull null
                    }

                    // Try each suggestion until one succeeds (no conflicts)
                    for (suggestion in suggestions) {
                        val files = tryRenameVariableAndUsages(psiFile.project, psiVar, suggestion, searchInComments)
                        if (files != null) {
                            modifiedFiles.addAll(files)
                            if (saveRenamesInMemory) {
                                memory?.put(signature, suggestion)
                                logger.info("      ✓ Stored rename in memory: `$signature` -> `$suggestion`")
                            }
                            return@mapNotNull psiVar to suggestion
                        }
                    }
                    // No valid suggestion worked
                    logger.info("    ⊘ Skipped renaming variable $varName, with suggestions: $suggestions")
                    null
                }

                val renamedCount = successfulRenames.size
                val totalCandidates = eligibleVariables.size
                val skipped = totalCandidates - renamedCount

                TransformationResult.Success(
                    message = "Renamed ${renamedCount}/${totalCandidates} variables in ${virtualFile.name}${if (skipped > 0) " (skipped: $skipped)" else ""}",
                    filesModified = modifiedFiles.size
                )
            } else {
                TransformationResult.Failure(
                    "Could not get document for file ${psiFile.name}"
                )
            }
            value
        } catch (e: Exception) {
            TransformationResult.Failure("Failed to rename variables in file ${virtualFile.name}", e)
        }
        return result
    }

    /**
     * Extracts rename suggestions from memory for variables.
     *
     * When [generateWhenNotInMemory] is true, generates new suggestions
     * for all variables whose suggestions are missing in memory.
     */
    private suspend fun extractRenamesFromMemory(
        variables: List<PsiVariable>,
        memory: Memory<String, String>?,
        generateWhenNotInMemory: Boolean,
    ): Renaming<PsiVariable> {
        val variablesWithMissingSuggestions = mutableListOf<PsiVariable>()

        val suggestions = variables.associateWith { psiVar ->
            val signature = withReadAction { PsiSignatureGenerator.generateSignature(psiVar) }
            if (signature == null) {
                logger.warn("Could not generate signature for variable")
                return@associateWith emptyList()
            }

            val cachedName = memory?.get(signature)
            if (cachedName != null) {
                logger.info("  ↳ Using cached rename: $signature -> $cachedName")
                listOf(cachedName)
            } else {
                logger.warn("  ⊘ Signature not found in memory: $signature")
                if (generateWhenNotInMemory) {
                    variablesWithMissingSuggestions.add(psiVar)
                }
                emptyList()
            }
        }

        val finalSuggestions = if (generateWhenNotInMemory && variablesWithMissingSuggestions.isNotEmpty()) {
            logger.info("  ↳ Generating missing rename suggestions for ${variablesWithMissingSuggestions.size} variables (i.e., generateWhenNotInMemory=true)...")
            val generated = generateRenames(variablesWithMissingSuggestions)
            buildMap {
                for (variable in variables) {
                    val suggestionsA = suggestions[variable] ?: emptyList()
                    val suggestionsB = generated.suggestions[variable] ?: emptyList()
                    put(variable, suggestionsA + suggestionsB)
                }
            }
        } else {
            suggestions
        }

        return Renaming(finalSuggestions)
    }

    /**
     * Generates rename suggestions for all variables using LLM.
     * Uses a single batch LLM call for efficiency.
     */
    private suspend fun generateRenames(variables: List<PsiVariable>): Renaming<PsiVariable> {
        val batchRenamings = generateNewVariableNames(variables)
        val suggestions = variables.associateWith { psiVar ->
            val varName = withReadAction { psiVar.name }
            val renaming = batchRenamings.find { it.originalName == varName }
            renaming?.suggestions?.let {
                withReadAction { buildSuggestionList(it, psiVar.project) }
            } ?: emptyList()
        }
        return Renaming(suggestions)
    }

    @Serializable
    private data class VariableRenaming(
        val originalName: String,
        val suggestions: List<String>
    )

    @Serializable
    private data class VariableRenameSuggestions(
        val renamings: List<VariableRenaming>
    )

    private fun buildSuggestionList(rawSuggestions: List<String>, project: Project): List<String> {
        val nameHelper = PsiNameHelper.getInstance(project)

        return rawSuggestions
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && nameHelper.isIdentifier(it) }
            .distinct()
            .toList()
    }

    private data class VariableContext(
        val variable: PsiVariable,
        val name: String,
        val typeName: String,
        val context: String,
        val namingConvention: String,
        val assignedType: String?
    )

    private fun buildVariableContext(psiVariable: PsiVariable): VariableContext {
        val isFinal = psiVariable.hasModifierProperty(PsiModifier.FINAL)
        val isStatic = psiVariable.hasModifierProperty(PsiModifier.STATIC)
        val typeName = psiVariable.type.presentableText

        // Get the specific class name if it's an assignment (e.g., Cat cat = new Cat())
        val assignedType = (psiVariable as? PsiLocalVariable)
            ?.initializer
            ?.let { it as? PsiNewExpression }
            ?.classOrAnonymousClassReference
            ?.referenceName

        val context = when (psiVariable) {
            is PsiField -> {
                val type = if (isFinal && isStatic) "Constant (static final)" else "Field"
                "$type in class '${psiVariable.containingClass?.name ?: "Unknown"}'"
            }
            is PsiParameter -> {
                val method = PsiTreeUtil.getParentOfType(psiVariable, PsiMethod::class.java)
                val methodName = method?.name ?: "anonymous"
                val className = method?.containingClass?.name ?: "Unknown"
                "${if (isFinal) "Final " else ""}Parameter in method '$methodName' of class '$className'"
            }
            is PsiLocalVariable -> {
                val method = PsiTreeUtil.getParentOfType(psiVariable, PsiMethod::class.java)
                val container = if (method != null) "method '${method.name}'" else "initializer block"
                val assignmentInfo = if (assignedType != null) " (assigned to a new instance of '$assignedType')" else ""
                "${if (isFinal) "Final " else ""}Local variable in $container$assignmentInfo"
            }
            else -> "Variable"
        }

        val namingConvention = if (psiVariable is PsiField && isFinal && isStatic) "UPPER_SNAKE_CASE" else "camelCase"

        return VariableContext(
            variable = psiVariable,
            name = psiVariable.name ?: "unknown",
            typeName = typeName,
            context = context,
            namingConvention = namingConvention,
            assignedType = assignedType
        )
    }

    private suspend fun generateNewVariableNames(
        variables: List<PsiVariable>,
        count: Int = DEFAULT_SUGGESTED_NAMES_SIZE
    ): List<VariableRenaming> {
        if (variables.isEmpty()) return emptyList()

        val contexts = readAction { variables.map { buildVariableContext(it) } }

        val varRenamePrompt = prompt("variable-rename-batch-prompt") {
            system {
                +"You are an agent used for refactoring Java code for metamorphic testing."
                +"You specialize in generating semantically similar variable names."
                +"Your output will be parsed into JSON; strictly follow the required structure."
            }
            user {
                +"Generate $count semantically similar name suggestions for each of the following variables:"
                +""
                for (ctx in contexts) {
                    +"Variable: ${ctx.name}"
                    +"  Declared Type: ${ctx.typeName}"
                    +"  Context: ${ctx.context}"
                    if (ctx.assignedType != null && ctx.assignedType != ctx.typeName) {
                        +"  Note: This variable holds an instance of the specific subclass: ${ctx.assignedType}"
                    }
                    +"  Required Format: ${ctx.namingConvention}"
                    +"\n"
                }
                +"Return a JSON object with field 'renamings' which is an array of objects."
                +"Each object must have 'originalName' (the current variable name) and 'suggestions' (an array of $count valid Java identifiers)."
                +"Example schema: {\"renamings\": [{\"originalName\": \"oldName1\", \"suggestions\": [\"newName1\", \"newName2\", ...]},{\"originalName\": \"oldName2\", \"suggestions\":[\"otherNewName1\", \"otherNewName2\", ...]}]}"
                +"Every suggestion must be semantically similar to the original name and follow the specified naming convention."
                +"IMPORTANT: The 'originalName' field must exactly match the variable name provided above."
                +"IMPORTANT: Refrain from using the old variable name in the new name (e.g., do NOT propose `newOwnerId` for `ownerId`)."
            }
        }


        val llm = LLM.fromGrazie(OpenAIModels.Chat.GPT5Mini)
        val result = llm.structuredRequest<VariableRenameSuggestions>(
            prompt = varRenamePrompt
        )

        return result?.renamings ?: emptyList()
    }

    private fun tryRenameVariableAndUsages(
        project: Project,
        psiVariable: PsiVariable,
        newName: String,
        searchInComments: Boolean,
    ): MutableSet<PsiFile>? {
        return try {
            val oldName = withReadAction { psiVariable.name } ?: return null
            val renameProcessor = withReadAction { RenameProcessor(
                    /* project = */ project,
                    /* element = */ psiVariable,
                    /* newName = */ newName,
                    /* isSearchInComments= */ searchInComments,
                    /* isSearchTextOccurrences = */ false
                )
            }

            ApplicationManager.getApplication().invokeAndWait {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                renameProcessor.run()
            }

            val modifiedFiles = withReadAction {
                val files = mutableSetOf<PsiFile>()
                renameProcessor.findUsages().forEach { usageInfo ->
                    usageInfo.file?.let { files.add(it) }
                }
                psiVariable.containingFile?.let { files.add(it) }
                files
            }

            val fileCountString = if (modifiedFiles.size > 1) " in ${modifiedFiles.size} files" else ""
            logger.info("    • Renamed `$oldName` to `$newName`$fileCountString")
            modifiedFiles
        } catch (e: ProcessCanceledException) {
            // Must rethrow control flow exceptions
            logger.warn("Rename variable and usage cancelled:\n${e.message}")
            throw e
        } catch (e: Exception) {
            // Rename failed (conflicts, PSI errors, etc.) - return null to try the next suggestion
            logger.info("    • Skipped variable rename for ${psiVariable.name} (Reason:\n${e.message})")
            null
        }
    }

    /**
     * Checks if annotations pass the blacklist filter.
     * Variables renaming supports ONLY blacklist mode (no whitelist).
     *
     * @param annotations List of annotations to check
     * @param blacklistedAnnotations Annotations to forbid (blacklist mode)
     * @return true if annotations pass the filter (none are blacklisted), false otherwise
     */
    private fun passesAnnotationFilter(
        annotations: List<PsiAnnotation>,
        blacklistedAnnotations: List<String>,
    ): Boolean {
        if (annotations.isEmpty()) {
            return true
        }

        // Blacklist mode: No annotations can be in the blacklist
        return annotations.none { annotation ->
            val qualifiedName = annotation.qualifiedName
            val simpleName = qualifiedName?.substringAfterLast('.')
            qualifiedName in blacklistedAnnotations || simpleName in blacklistedAnnotations
        }
    }

    /**
     * Checks if a single variable passes all filtering criteria.
     * Returns true if the variable should be included, false otherwise.
     */
    private fun passesVariableFilters(
        variable: PsiVariable,
        psiFile: PsiFile,
        blacklistedVariableAnnotations: List<String>,
    ): Boolean {
        val fileIndex = ProjectFileIndex.getInstance(psiFile.project)

        // 1. Exclude Test Sources
        if (fileIndex.isInTestSourceContent(psiFile.virtualFile)) {
            logger.info("      ⊘ Variable `${variable.name}` - skipped (in test source)")
            return false
        }

        // 2. Exclude Enum Constants
        if (variable is PsiEnumConstant) {
            logger.info("      ⊘ Variable `${variable.name}` - skipped (is enum constant)")
            return false
        }

        // 3. Annotation filter (blacklist mode only)
        val variableAnnotations = variable.annotations.toList()
        val annotationsPass = passesAnnotationFilter(
            variableAnnotations,
            blacklistedVariableAnnotations
        )

        // Log annotation filtering for variables with annotations
        if (variableAnnotations.isNotEmpty()) {
            val annotationNames = variableAnnotations.mapNotNull { it.qualifiedName?.substringAfterLast('.') }
            if (annotationsPass) {
                logger.info("      ✓ Variable `${variable.name}` with annotations [${annotationNames.joinToString(", ")}] - allowed (not blacklisted)")
            } else {
                logger.info("      ⊘ Variable `${variable.name}` with annotations [${annotationNames.joinToString(", ")}] - skipped (blacklisted)")
                return false
            }
        }

        // 4. Exclude Library/Compiled Code
        if (variable is PsiCompiledElement || !variable.isPhysical) {
            logger.info("      ⊘ Variable `${variable.name}` - skipped (compiled or non-physical)")
            return false
        }

        // 5. Exclude public/protected fields (could cause external breaking changes)
        if (variable is PsiField) {
            if (variable.hasModifierProperty(PsiModifier.PUBLIC) || variable.hasModifierProperty(PsiModifier.PROTECTED)) {
                logger.info("      ⊘ Variable `${variable.name}` - skipped (public/protected field)")
                return false
            }
        }

        return true
    }

    /**
     * Identifies and filters valid variables from the provided PSI file based on specific criteria.
     * The filtering logic excludes variables in test sources, enum constants, blacklisted annotations,
     * variables from library or compiled code, and public/protected fields that could cause external breaking changes.
     *
     * @param psiFile The PSI file to traverse and analyze for variables.
     * @param blacklistedVariableAnnotations Annotations to exclude (blacklist mode).
     * @return A list of PSI variables matching all filtering criteria.
     */
    private fun findAllValidVariables(
        psiFile: PsiFile,
        blacklistedVariableAnnotations: List<String>,
    ): List<PsiVariable> {
        // Log annotation filter configuration
        if (blacklistedVariableAnnotations.isNotEmpty()) {
            logger.info("  ↳ Annotation filter mode: BLACKLIST")
            logger.info("  ↳ Blacklisted variable annotations: [\n${blacklistedVariableAnnotations.joinToString(",\n") { "\t$it" } }\n]")
        } else {
            logger.info("  ↳ Annotation filter mode: BLACKLIST (empty - all annotations allowed)")
        }

        val variables = mutableListOf<PsiVariable>()

        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element is PsiVariable) {
                    variables.add(element)
                }
            }
        })

        val filteredVariables = variables.filter { v ->
            passesVariableFilters(v, psiFile, blacklistedVariableAnnotations)
        }

        if (filteredVariables.isNotEmpty()) {
            // prettify filepath attempting to make it relative to the project root
            val filepath = psiFile.virtualFile?.let { psiFile.project.relativeToRootOrAbsPath(it) } ?: "<in-memory>"
            logger.info("  ↳ Found ${filteredVariables.size} matching variables in '$filepath'")
        }
        return filteredVariables
    }

    companion object {
        const val ID = "rename-variable-transformation"
        private const val DEFAULT_SUGGESTED_NAMES_SIZE = 3

        /**
         * Default blacklisted variable annotations (framework/infrastructure annotations).
         * These annotations typically indicate variables that are mapped to external systems,
         * so renaming them could break runtime behavior or data binding.
         */
        val DEFAULT_BLACKLISTED_VARIABLE_ANNOTATIONS = setOf(
            // JPA/Hibernate
            "javax.persistence.Column",
            "javax.persistence.Id",
            "javax.persistence.GeneratedValue",
            "javax.persistence.Version",
            "javax.persistence.Temporal",
            "javax.persistence.Enumerated",
            "javax.persistence.Lob",
            "javax.persistence.Basic",
            "javax.persistence.EmbeddedId",
            "javax.persistence.JoinColumn",
            "jakarta.persistence.Column",
            "jakarta.persistence.Id",
            "jakarta.persistence.GeneratedValue",
            "jakarta.persistence.Version",
            "jakarta.persistence.Temporal",
            "jakarta.persistence.Enumerated",
            "jakarta.persistence.Lob",
            "jakarta.persistence.Basic",
            "jakarta.persistence.EmbeddedId",
            "jakarta.persistence.JoinColumn",

            // Jackson (JSON)
            "com.fasterxml.jackson.annotation.JsonProperty",
            "com.fasterxml.jackson.annotation.JsonIgnore",
            "com.fasterxml.jackson.annotation.JsonAlias",

            // JAXB (XML)
            "javax.xml.bind.annotation.XmlElement",
            "javax.xml.bind.annotation.XmlAttribute",
            "javax.xml.bind.annotation.XmlTransient",
            "javax.xml.bind.annotation.XmlID",
            "jakarta.xml.bind.annotation.XmlElement",
            "jakarta.xml.bind.annotation.XmlAttribute",
            "jakarta.xml.bind.annotation.XmlTransient",
            "jakarta.xml.bind.annotation.XmlID",

            // Spring Framework
            "org.springframework.beans.factory.annotation.Value",
            "org.springframework.beans.factory.annotation.Autowired",
            "org.springframework.beans.factory.annotation.Qualifier",
            "javax.annotation.Resource",

            // Bean Validation
            "javax.validation.constraints.NotNull",
            "javax.validation.constraints.Size",
            "javax.validation.constraints.Min",
            "javax.validation.constraints.Max",
            "javax.validation.constraints.Pattern",
            "javax.validation.constraints.Email",
            "jakarta.validation.constraints.NotNull",
            "jakarta.validation.constraints.Size",
            "jakarta.validation.constraints.Min",
            "jakarta.validation.constraints.Max",
            "jakarta.validation.constraints.Pattern",
            "jakarta.validation.constraints.Email",

            // CDI
            "javax.inject.Inject",
            "javax.inject.Named",
            "jakarta.inject.Inject",
            "jakarta.inject.Named"
        )
    }
}