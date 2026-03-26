package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation.Companion.withReadAction
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

            val document = withReadAction { psiFile.document() }
            val modifiedFiles = mutableSetOf<PsiFile>()
            val value = if (document != null) {
                val eligibleVariables: List<PsiVariable> = withReadAction { findAllValidVariables(psiFile) }

                if (eligibleVariables.isEmpty()) {
                    return TransformationResult.Skipped("No matching variables found in ${virtualFile.name}")
                }

                logger.info("  ⏲ Generating rename suggestions for ${eligibleVariables.size} variables...")

                val renameSuggestions = if (useMemory) {
                    extractRenamesFromMemory(eligibleVariables, memory)
                } else {
                    runBlocking { generateRenames(eligibleVariables) }
                }

                // Try renaming each variable with suggestions until one succeeds
                val successfulRenames = eligibleVariables.mapNotNull { psiVar ->
                    val varName = withReadAction { psiVar.name }
                    val suggestions = renameSuggestions[psiVar] ?: return@mapNotNull null

                    // Generate signature BEFORE renaming
                    val signature = withReadAction { PsiSignatureGenerator.generateSignature(psiVar) }
                    if (signature == null) {
                        logger.warn("    ⊘ Could not generate signature for variable $varName")
                        return@mapNotNull null
                    }

                    // Try each suggestion until one succeeds (no conflicts)
                    for (suggestion in suggestions) {
                        val files = tryRenameVariableAndUsages(psiFile.project, psiVar, suggestion)
                        if (files != null) {
                            modifiedFiles.addAll(files)
                            if (!useMemory) {
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
     */
    private fun extractRenamesFromMemory(
        variables: List<PsiVariable>,
        memory: Memory<String, String>?
    ): Map<PsiVariable, List<String>> {
        return variables.associateWith { psiVar ->
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
                logger.info("  ⊘ Signature not found in memory: $signature")
                emptyList()
            }
        }
    }

    /**
     * Generates rename suggestions for all variables using LLM.
     * Uses a single batch LLM call for efficiency.
     */
    private suspend fun generateRenames(variables: List<PsiVariable>): Map<PsiVariable, List<String>> {
        val batchRenamings = generateNewVariableNames(variables)
        return variables.associateWith { psiVar ->
            val varName = withReadAction { psiVar.name }
            val renaming = batchRenamings.find { it.originalName == varName }
            renaming?.suggestions?.let {
                withReadAction { buildSuggestionList(it, psiVar.project) }
            } ?: emptyList()
        }
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
        project: Project, psiVariable: PsiVariable, newName: String
    ): MutableSet<PsiFile>? {
        return try {
            val oldName = withReadAction { psiVariable.name } ?: return null
            // isSearchInComments needs to be false. If true, it would breaks functionality by changing string literals.
            // example would be mappings of `PathVariable` from Spring.
            // `@param [paramName]` definitions in the Javadocs are still being renamed.
            val renameProcessor = withReadAction { RenameProcessor(
                    /* project = */ project,
                    /* element = */ psiVariable,
                    /* newName = */ newName,
                    /* isSearchInComments= */ false,
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
     * Identifies and filters valid variables from the provided PSI file based on specific criteria.
     * The filtering logic excludes variables in test sources, enum constants, variables annotated with `@Column`,
     * variables from library or compiled code, and public/protected fields that could cause external breaking changes.
     *
     * @param psiFile The PSI file to traverse and analyze for variables.
     * @return A list of PSI variables matching all filtering criteria.
     */
    private fun findAllValidVariables(psiFile: PsiFile): List<PsiVariable> {
        val variables = mutableListOf<PsiVariable>()

        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element is PsiVariable) {
                    variables.add(element)
                }
            }
        })

        val fileIndex = ProjectFileIndex.getInstance(psiFile.project)

        val filteredVariables = variables.filter { v ->
            // 1. Exclude Test Sources
            if (fileIndex.isInTestSourceContent(psiFile.virtualFile)) return@filter false

            // 2. Exclude Enum Constants
            if (v is PsiEnumConstant) return@filter false

            // 3. Exclude @Column annotated variables
            if (v.annotations.any { it.qualifiedName?.contains("Column") == true }) return@filter false

            // 4. Exclude Library/Compiled Code
            if (v !is PsiCompiledElement && v.isPhysical) {
                // 5. Overrides Check (for fields/parameters)
                // If a field overrides a superclass field, renaming it might break polymorphism or hide fields.
                // Simple heuristic: Only rename private/package-private fields or local vars to stay safe.
                if (v is PsiField) {
                    if (v.hasModifierProperty(PsiModifier.PUBLIC) || v.hasModifierProperty(PsiModifier.PROTECTED)) {
                        // Skip public/protected fields to avoid breaking external consumers or overrides
                        return@filter false
                    }
                }
                true
            } else {
                false
            }
        }

        if (filteredVariables.isNotEmpty()) {
            logger.info("  ↳ Found ${filteredVariables.size} matching variables in ${psiFile.virtualFile?.path}")
        }
        return filteredVariables
    }

    companion object {
        const val ID = "rename-variable-transformation"
        private const val DEFAULT_SUGGESTED_NAMES_SIZE = 3
    }
}