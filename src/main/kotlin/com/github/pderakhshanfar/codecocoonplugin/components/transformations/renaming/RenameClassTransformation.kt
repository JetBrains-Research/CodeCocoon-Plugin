package com.github.pderakhshanfar.codecocoonplugin.components.transformations.renaming

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation.Companion.withReadAction
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

/**
 * Renames Java classes to an LLM-suggested, semantically similar name and updates usages/overrides.
 *
 * Skips: classes referenced from non-Java files, test class names and annotated classes.
 */
class RenameClassTransformation(
    override val config: Map<String, Any>
) : JavaTransformation, SelfManagedTransformation() {
    override val id: String = ID
    override val description: String = "Renames a class and all of its usages/references"
    private val logger = thisLogger().withStdout()

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile,
        memory: Memory<String, String>?
    ): TransformationResult {
        val result = try {
            val useMemory = config.requireOrDefault<Boolean>("useMemory", defaultValue = false)
            val generateWhenNotInMemory = config.requireOrDefault<Boolean>("generateWhenNotInMemory", defaultValue = false)

            val document = withReadAction { psiFile.document() }
            val modifiedFiles = mutableSetOf<PsiFile>()
            val value = if (document != null) {
                val eligibleClasses: List<PsiClass> = withReadAction { findAllValidClasses(psiFile) }

                if (eligibleClasses.isEmpty()) {
                    return TransformationResult.Skipped("No matching classes found in ${virtualFile.name}")
                }

                logger.info("  ⏲ Generating rename suggestions for ${eligibleClasses.size} classes...")

                val renaming = runBlocking {
                    if (useMemory) {
                        extractRenamesFromMemory(eligibleClasses, memory, generateWhenNotInMemory)
                    } else {
                        generateRenames(eligibleClasses)
                    }
                }

                // memory is updated when either we generate renames anew
                // or when we POTENTIALLY generated renames for missing entries
                val saveRenamesInMemory = !useMemory || generateWhenNotInMemory


                val successfulRenames = eligibleClasses.mapNotNull { psiClass ->
                    val className = withReadAction { psiClass.name }
                    val suggestions = renaming.suggestions[psiClass] ?: return@mapNotNull null

                    // Generate signature before renaming
                    val signature = withReadAction { PsiSignatureGenerator.generateSignature(psiClass) }
                    if (signature == null) {
                        logger.warn("  ⊘ Could not generate signature for class $className")
                        return@mapNotNull null
                    }

                    // Try each suggestion until one succeeds
                    for (suggestion in suggestions) {
                        val files = tryRenameClassAndUsages(psiFile.project, psiClass, suggestion)
                        if (files != null) {
                            modifiedFiles.addAll(files)
                            if (saveRenamesInMemory) {
                                memory?.put(signature, suggestion)
                                logger.info("      ✓ Stored rename in memory: `$signature` -> `$suggestion`")
                            }
                            return@mapNotNull psiClass to suggestion
                        }
                    }
                    // No valid suggestion worked
                    logger.info("  ⊘ Skipped renaming class $className, with suggestions: $suggestions")
                    null
                }

                val renamedCount = successfulRenames.size
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

    private data class ClassContext(
        val classType: String,
        val className: String,
        val methodNames: List<String>,
        val fieldNames: List<String>
    )

    /**
     * Extracts rename suggestions from memory for classes.
     *
     * When [generateWhenNotInMemory] is true, generates new suggestions
     * for all classes whose suggestions are missing in memory.
     */
    private suspend fun extractRenamesFromMemory(
        classes: List<PsiClass>,
        memory: Memory<String, String>?,
        generateWhenNotInMemory: Boolean,
    ): Renaming<PsiClass> {
        val classesWithMissingSuggestions = mutableListOf<PsiClass>()

        val suggestions = classes.associateWith { psiClass ->
            val signature = withReadAction { PsiSignatureGenerator.generateSignature(psiClass) }
            if (signature == null) {
                logger.warn("Could not generate signature for class")
                return@associateWith emptyList()
            }

            val cachedName = memory?.get(signature)
            if (cachedName != null) {
                logger.info("  ↳ Using cached rename: $signature -> $cachedName")
                listOf(cachedName)
            } else {
                logger.warn("  ⊘ Signature not found in memory: $signature")
                if (generateWhenNotInMemory) {
                    classesWithMissingSuggestions.add(psiClass)
                }
                emptyList()
            }
        }

        val finalSuggestions = if (generateWhenNotInMemory && classesWithMissingSuggestions.isNotEmpty()) {
            logger.info("  ↳ Generating missing rename suggestions for ${classesWithMissingSuggestions.size} classes (i.e., generateWhenNotInMemory=true)...")
            val generated = generateRenames(classesWithMissingSuggestions)
            buildMap {
                for (clazz in classes) {
                    val suggestionsA = suggestions[clazz] ?: emptyList()
                    val suggestionsB = generated.suggestions[clazz] ?: emptyList()
                    put(clazz, suggestionsA + suggestionsB)
                }
            }
        } else {
            suggestions
        }

        return Renaming(finalSuggestions)
    }

    /**
     * Generates rename suggestions for all classes using LLM.
     */
    private suspend fun generateRenames(classes: List<PsiClass>): Renaming<PsiClass> {
        val suggestions = classes.associateWith { psiClass ->
            generateNewClassNames(psiClass)
        }
        return Renaming(suggestions)
    }

    private suspend fun generateNewClassNames(psiClass: PsiClass, count: Int = DEFAULT_SUGGESTED_NAMES_SIZE): List<String> {
        val context = readAction {
            val type = when {
                psiClass.isInterface -> "interface"
                psiClass.isEnum -> "enum class"
                psiClass.hasModifierProperty(PsiModifier.ABSTRACT) -> "abstract class"
                else -> "class"
            }
            val name = psiClass.name ?: "Unknown"
            val methods = psiClass.methods.mapNotNull { it.name }
            val fields = psiClass.allFields.mapNotNull { it.name }

            ClassContext(type, name, methods, fields)
        }

        val classRenamePrompt = prompt("class-rename-prompt") {
            system {
                +"You are an agent that proposes semantically similar Java class names."
                +"Your output is used in a metamorphic transformation pipeline."
                +"Your output will be parsed into JSON; strictly follow the required structure."
            }
            user {
                +"The name of the ${context.classType} is: ${context.className}"
                if (context.methodNames.isNotEmpty()) +"The methods in the class are: ${context.methodNames.joinToString(", ")}"
                if (context.fieldNames.isNotEmpty()) +"All fields in the class are: ${context.fieldNames.joinToString(", ")}"
                +"Return a JSON object with field 'suggestions' which is an ordered array of $count Java identifiers, from most to least fitting."
                +"Example structure:"
                +"{\"suggestions\": [\"BestFittingRename\", \"SecondBestFittingRename\", ... ]}"
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

        val firstSuggestion = normalized.firstOrNull() ?: return emptyList()
        val internalFallback = "${firstSuggestion}Internal"

        return if (normalized.contains(internalFallback)) normalized else normalized + internalFallback
    }

    private fun tryRenameClassAndUsages(
        project: Project, psiClass: PsiClass, newName: String
    ): MutableSet<PsiFile>? {
        return try {
            val oldName = psiClass.name ?: return null
            // Creating RenameProcessor requires read access for PSI validation
            val renameProcessor = withReadAction {
                RenameProcessor(
                    /* project = */ project,
                    /* element = */ psiClass,
                    /* newName = */ newName,
                    /* isSearchInComments= */ true,
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
                psiClass.containingFile?.let { files.add(it) }
                files
            }
            logger.info("    • Renamed `$oldName` to `$newName`")
            modifiedFiles
        } catch (e: ProcessCanceledException) {
            // Must rethrow control flow exceptions
            logger.warn("Rename cancelled:\n${e.message}")
            throw e
        } catch (e: Exception) {
            // Rename failed (conflicts, PSI errors, etc.) - return null to try the next suggestion
            logger.info("Rename failed for ${psiClass.name} with error:\n${e.message}")
            null
        }
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
            val className = cls.name
            // We need to check for `cls.name.length` > 1 to filter out raw Type classes
            (className != null) && (className.length > 1) && cls.annotations.isEmpty()
        }

        if (filteredClasses.isNotEmpty()) {
            logger.info("  ↳ Found ${filteredClasses.size} matching classes in ${psiFile.virtualFile?.path}")
        }
        return filteredClasses
    }

    companion object {
        const val ID = "rename-class-transformation"
        private const val DEFAULT_SUGGESTED_NAMES_SIZE = 3
    }
}