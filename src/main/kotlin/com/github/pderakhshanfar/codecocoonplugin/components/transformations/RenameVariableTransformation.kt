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
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

class RenameVariableTransformation(
    override val config: Map<String, Any>
) : JavaTransformation, IntelliJAwareTransformation {
    override val id: String = ID
    override val description: String = "Renames variables (fields, locals, parameters) and their usages"
    private val logger = thisLogger().withStdout()

    override fun apply(
        psiFile: PsiFile, virtualFile: VirtualFile
    ): TransformationResult {
        val result = try {
            val document = psiFile.document()
            val modifiedFiles = mutableSetOf<PsiFile>()
            val value = if (document != null) {
                val eligibleVariables: List<PsiVariable> = findAllValidVariables(psiFile)

                if (eligibleVariables.isEmpty()) {
                    return TransformationResult.Skipped("No matching variables found in ${virtualFile.name}")
                }

                logger.info("  ⏲ Generating rename suggestions for ${eligibleVariables.size} variables...")

                val renameSuggestions = runBlocking {
                    getAllVariableRenameSuggestions(eligibleVariables)
                }

                // Create a map from variable name to suggestions for easy lookup
                val suggestionMap = renameSuggestions.associateBy { it.originalName }

                // Process each variable and find the first available suggestion
                val newNames = eligibleVariables.mapNotNull { psiVar ->
                    val varName = psiVar.name ?: return@mapNotNull null
                    val suggestions = suggestionMap[varName]?.suggestions ?: return@mapNotNull null

                    // Normalize and validate suggestions
                    val validSuggestions = buildSuggestionList(suggestions, psiVar.project)

                    // Find the first available suggestion
                    val chosenName = validSuggestions.firstOrNull { isNameAvailable(psiVar, it) }

                    if (chosenName != null) {
                        psiVar to chosenName
                    } else {
                        null // Skip if no valid suggestions available
                    }
                }

                for ((psiVar, newName) in newNames) {
                    val files = renameVariableAndUsages(psiFile.project, psiVar, newName)
                    modifiedFiles.addAll(files)
                }

                val renamedCount = newNames.size
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

    private suspend fun getAllVariableRenameSuggestions(
        variables: List<PsiVariable>,
        count: Int = DEFAULT_SUGGESTED_NAMES_SIZE
    ): List<VariableRenaming> {
        if (variables.isEmpty()) return emptyList()

        val contexts = variables.map { buildVariableContext(it) }

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

    private fun isNameAvailable(variable: PsiVariable, newName: String): Boolean {
        val project = variable.project
        val nameHelper = PsiNameHelper.getInstance(project)

        // 1. Basic Identifier Check
        if (!nameHelper.isIdentifier(newName)) return false

        // 2. Scope Collision Check
        // We need to ensure 'newName' isn't already used in the same scope (e.g., same method block)
        val resolveHelper = JavaPsiFacade.getInstance(project).resolveHelper

        // Find the scope in which the variable is valid (Method, Loop, or Class)
        val scope = PsiTreeUtil.getParentOfType(variable, PsiElement::class.java) ?: return false

        val existingVariable = resolveHelper.resolveReferencedVariable(newName, scope)

        // If it resolves to something, we have a collision
        if (existingVariable != null) {
            return false
        }

        // 3. Sibling Check (specifically for fields to avoid duplicate field names)
        if (variable is PsiField) {
            val containingClass = variable.containingClass
            val existingField = containingClass?.findFieldByName(newName, false)
            if (existingField != null) return false
        }

        return true
    }

    private fun renameVariableAndUsages(
        project: Project, psiVariable: PsiVariable, newName: String
    ): MutableSet<PsiFile> {
        val searchScope = GlobalSearchScope.projectScope(project)
        val allReferences = ReferencesSearch.search(psiVariable, searchScope).findAll().toList()

        val modifiedFiles = mutableSetOf<PsiFile>()

        psiVariable.setName(newName)
        psiVariable.containingFile?.let { modifiedFiles.add(it) }

        for (reference in allReferences) {
            try {
                reference.handleElementRename(newName)
                reference.element.containingFile?.let { modifiedFiles.add(it) }
            } catch (_: Exception) {
                throw Exception("Could not rename reference at ${reference.element.containingFile?.virtualFile?.path}:${reference.element.textOffset}")
            }
        }

        return modifiedFiles
    }

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

            // 3. Exclude Library/Compiled Code
            if (v !is PsiCompiledElement && v.isPhysical) {
                // 4. Overrides Check (for fields/parameters)
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