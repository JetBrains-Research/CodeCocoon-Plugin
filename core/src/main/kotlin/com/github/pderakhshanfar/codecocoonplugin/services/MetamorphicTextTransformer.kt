package com.github.pderakhshanfar.codecocoonplugin.services

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Transforms problem statements and interface descriptions for metamorphic test instances
 * by updating class and method names according to the rename memory.
 */
class MetamorphicTextTransformer(
    private val llm: LLM,
) {

    @Serializable
    data class TransformedTexts(
        val problemStatement: String,
        val interfaceDescription: String,
    )

    /**
     * Transforms both the problem statement and interface description using the memory file.
     *
     * @param problemStatement The original problem statement
     * @param interfaceDescription The original interface description
     * @param memoryFilePath Path to the memory JSON file containing class/method renames
     * @return TransformedTexts with updated names
     */
    suspend fun transformTexts(
        problemStatement: String,
        interfaceDescription: String,
        memoryFilePath: String,
    ): TransformedTexts? {
        // Load memory file
        val memoryFile = File(memoryFilePath)
        if (!memoryFile.exists()) {
            println("ERROR: Memory file not found: $memoryFilePath")
            return null
        }

        val memoryJson = Json.parseToJsonElement(memoryFile.readText())
        val entries = memoryJson.jsonObject["entries"]?.jsonObject

        if (entries == null) {
            println("ERROR: No 'entries' field found in memory file")
            return null
        }

        // Build a mapping of old names to new names
        val renameMap = mutableMapOf<String, String>()
        entries.entries.forEach { (oldName, newName) ->
            renameMap[oldName] = newName.jsonPrimitive.content
        }

        if (renameMap.isEmpty()) {
            println("WARNING: No rename entries found in memory file")
            return TransformedTexts(problemStatement, interfaceDescription)
        }

        // Create the prompt
        val prompt = createTransformationPrompt(
            problemStatement = problemStatement,
            interfaceDescription = interfaceDescription,
            renameMap = renameMap
        )

        // Call LLM
        val result = llm.structuredRequest<TransformedTexts>(
            prompt = prompt,
            maxRetries = 3,
            maxFixingAttempts = 2
        )

        return result
    }

    private fun createTransformationPrompt(
        problemStatement: String,
        interfaceDescription: String,
        renameMap: Map<String, String>
    ): Prompt {
        return Prompt.build("metamorphic-text-transformer") {
            system {
                text("""
                    You are a technical documentation assistant helping to update code descriptions
                    after refactoring transformations have been applied.

                    You will be given:
                    1. An original problem statement
                    2. An original interface description
                    3. A mapping of old class/method names to new names

                    Your task:
                    - Update the problem statement and interface description to use the NEW names
                    - Keep the meaning and structure exactly the same
                    - Only change the class, method, and variable names according to the mapping
                    - Preserve all formatting, punctuation, and sentence structure
                    - If a name doesn't appear in the mapping, leave it unchanged

                    Important:
                    - Do NOT add new information
                    - Do NOT remove information
                    - Do NOT rephrase or rewrite the content
                    - ONLY update the names that appear in the rename mapping
                """.trimIndent())
            }

            user {
                text("## Original Problem Statement\n")
                text(problemStatement)
                text("\n\n")

                text("## Original Interface Description\n")
                text(interfaceDescription)
                text("\n\n")

                text("## Rename Mapping (OldName -> NewName)\n")
                renameMap.forEach { (old, new) ->
                    // Extract simple class name from fully qualified name
                    val oldSimple = old.substringAfterLast('.')
                    val newSimple = new
                    text("- $oldSimple -> $newSimple\n")
                }

                text("\n")
                text("Now, update the problem statement and interface description with the new names.")
            }
        }
    }
}

/**
 * CLI entry point for transforming texts from command line.
 *
 * Usage:
 * ./gradlew transformMetamorphicTexts \
 *   -PproblemStatement="..." \
 *   -PinterfaceDesc="..." \
 *   -PmemoryFile="/path/to/memory.json" \
 *   -PoutputFile="/path/to/output.json"
 */
suspend fun main(args: Array<String>) {
    if (args.size != 4) {
        println("Usage: transformMetamorphicTexts <problemStatement> <interfaceDesc> <memoryFile> <outputFile>")
        return
    }

    val problemStatement = args[0]
    val interfaceDesc = args[1]
    val memoryFile = args[2]
    val outputFile = args[3]

    try {
        // Initialize LLM (using GPT-4 via Grazie)
        val token = System.getenv("GRAZIE_TOKEN") ?: run {
            println("ERROR: GRAZIE_TOKEN environment variable not set")
            return
        }

        val llm = LLM.fromGrazie(
            model = OpenAIModels.Chat.GPT5Mini,
            token = token
        )

        val transformer = MetamorphicTextTransformer(llm)

        println("Transforming texts using memory file: $memoryFile")
        val result = transformer.transformTexts(
            problemStatement = problemStatement,
            interfaceDescription = interfaceDesc,
            memoryFilePath = memoryFile
        )

        if (result != null) {
            // Write result to JSON file
            val json = Json.encodeToString(result)
            File(outputFile).writeText(json)
            println("SUCCESS: Transformed texts written to: $outputFile")
        } else {
            println("ERROR: Failed to transform texts")
        }
    } catch (e: Exception) {
        println("ERROR: ${e.message}")
        e.printStackTrace()
    }
}
