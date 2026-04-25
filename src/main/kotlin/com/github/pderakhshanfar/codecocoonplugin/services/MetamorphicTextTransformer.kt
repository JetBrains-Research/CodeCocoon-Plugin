package com.github.pderakhshanfar.codecocoonplugin.services

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Transforms problem statements for metamorphic test instances by updating class, method,
 * and variable names — and package references when files were moved — according to the
 * rename memory.
 */
class MetamorphicTextTransformer(
    private val llm: LLM,
) {
    private val logger = thisLogger().withStdout()

    @Serializable
    data class TransformedTexts(
        val problemStatement: String,
    )

    /**
     * Transforms the problem statement using the memory file.
     *
     * @param problemStatement The original problem statement
     * @param memoryFilePath Path to the memory JSON file containing class/method renames
     *                       and (optionally) file-move entries
     * @return TransformedTexts with updated names
     */
    suspend fun transformTexts(
        problemStatement: String,
        memoryFilePath: String,
    ): TransformedTexts? {
        // Load memory file
        val memoryFile = File(memoryFilePath)
        if (!memoryFile.exists()) {
            logger.error("ERROR: Memory file not found: $memoryFilePath")
            return null
        }

        val memoryJson = Json.parseToJsonElement(memoryFile.readText())
        val entries = memoryJson.jsonObject["entries"]?.jsonObject

        if (entries == null) {
            logger.error("ERROR: No 'entries' field found in memory file")
            return null
        }

        // Build a mapping of old names to new names
        val renameMap = mutableMapOf<String, String>()
        entries.entries.forEach { (oldName, newName) ->
            renameMap[oldName] = newName.jsonPrimitive.content
        }

        if (renameMap.isEmpty()) {
            logger.warn("WARNING: No rename entries found in memory file")
            return TransformedTexts(problemStatement)
        }

        // Create the prompt
        val prompt = createTransformationPrompt(
            problemStatement = problemStatement,
            renameMap = renameMap
        )
        logger.info("Created prompt:\n'''$prompt\n'''")

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
        renameMap: Map<String, String>
    ): Prompt {
        return Prompt.build("metamorphic-text-transformer") {
            system {
                text("""
                    You are a technical documentation assistant helping to update code descriptions
                    after refactoring transformations have been applied.

                    You will be given:
                    1. An original problem statement
                    2. A mapping of old class/method/variable names AND old file paths to their new
                       names or new locations

                    The mapping may contain two kinds of entries:
                    - Identifier renames: the value is a Java identifier (e.g. `computeTotal`).
                      Replace every occurrence of the old simple name in the problem statement with
                      the new one wherever it appears (class names, method calls, variable mentions,
                      fully-qualified references, etc.).
                    - File / package moves: the value looks like a filesystem path or directory
                      (contains `/` or `\`). The corresponding source file was relocated, which
                      typically changes its Java package. Update any fully-qualified class
                      references, `import` statements, or package mentions in the problem statement
                      to reflect the new package implied by the new directory.

                    Your task:
                    - Update the problem statement to use the NEW names and NEW packages
                    - Keep the meaning and structure exactly the same
                    - Preserve all formatting, punctuation, and sentence structure
                    - If a name doesn't appear in the mapping, leave it unchanged

                    Important:
                    - Do NOT add new information
                    - Do NOT remove information
                    - Do NOT rephrase or rewrite the content
                    - ONLY update the names and packages indicated by the rename mapping
                """.trimIndent())
            }

            user {
                text("## Original Problem Statement\n")
                text(problemStatement)
                text("\n\n")

                val (moves, renames) = renameMap.entries.partition { (_, value) ->
                    value.contains('/') || value.contains('\\')
                }

                text("## Identifier renames (OldSimpleName -> NewSimpleName)\n")
                renames.forEach { (old, new) ->
                    val oldSimple = old.substringAfterLast('.')
                    text("- $oldSimple -> $new\n")
                }

                if (moves.isNotEmpty()) {
                    text("\n## File/package moves (OldPath -> NewDirectory)\n")
                    moves.forEach { (old, new) ->
                        text("- $old -> $new\n")
                    }
                }

                text("\n")
                text("Now, update the problem statement with the new names and packages.")
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
 *   -PmemoryFile="/path/to/memory.json" \
 *   -PoutputFile="/path/to/output.json"
 */
suspend fun main(args: Array<String>) {
    if (args.size != 3) {
        println("Usage: transformMetamorphicTexts <problemStatement> <memoryFile> <outputFile>")
        return
    }

    val problemStatement = args[0]
    val memoryFile = args[1]
    val outputFile = args[2]

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
