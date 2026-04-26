package com.github.pderakhshanfar.codecocoonplugin.services

import ai.koog.prompt.dsl.Prompt
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Updates a [TextBlock] (`{title, body}` pair) so class/method/variable names and
 * package references reflect a rename memory file produced by the renaming/file-moving
 * transformations. One LLM call per block keeps title and body internally consistent.
 */
class MetamorphicTextTransformer(
    private val llm: LLM,
) {
    private val logger = thisLogger().withStdout()

    @Serializable
    private data class TransformedBlock(
        val title: String,
        val body: String,
    )

    /**
     * Loads the rename map from [memoryFilePath]. Returns null when the file is missing
     * or malformed; an empty map when the file contains no entries.
     */
    fun loadRenameMap(memoryFilePath: String): Map<String, String>? {
        val memoryFile = File(memoryFilePath)
        if (!memoryFile.exists()) {
            logger.error("ERROR: Memory file not found: $memoryFilePath")
            return null
        }

        val memoryJson = Json.parseToJsonElement(memoryFile.readText())
        val entries = memoryJson.jsonObject["entries"]?.jsonObject ?: run {
            logger.error("ERROR: No 'entries' field found in memory file")
            return null
        }

        return entries.entries.associate { (oldName, newName) ->
            oldName to newName.jsonPrimitive.content
        }
    }

    /**
     * Updates [block]'s title and body together. Returns [block] verbatim when the
     * rename map is empty or both fields are blank. Returns null on LLM failure (the
     * caller decides whether to fall back).
     */
    suspend fun transformBlock(
        block: TextBlock,
        renameMap: Map<String, String>,
    ): TextBlock? {
        if (renameMap.isEmpty()) return block
        if (block.title.isBlank() && block.body.isBlank()) return block

        val prompt = createTransformationPrompt(block = block, renameMap = renameMap)
        logger.info("Created metamorphic prompt:\n'''$prompt\n'''")

        val result = llm.structuredRequest<TransformedBlock>(
            prompt = prompt,
            maxRetries = 3,
            maxFixingAttempts = 2,
        ) ?: return null

        return TextBlock(title = result.title, body = result.body)
    }

    private fun createTransformationPrompt(
        block: TextBlock,
        renameMap: Map<String, String>,
    ): Prompt {
        return Prompt.build("metamorphic-text-transformer") {
            system {
                text("""
                    You are a technical documentation assistant helping to update code
                    descriptions after refactoring transformations have been applied.

                    You will be given:
                    1. A title and a body of a single document block. The body may be
                       multiline markdown.
                    2. A mapping of old class/method/variable names AND old file paths
                       to their new names or new locations.

                    The mapping may contain two kinds of entries:
                    - Identifier renames: the value is a Java identifier (e.g.
                      `computeTotal`). Replace every occurrence of the old simple name
                      in the title and body with the new one wherever it appears (class
                      names, method calls, variable mentions, fully-qualified
                      references, etc.).
                    - File / package moves: the value looks like a filesystem path or
                      directory (contains `/` or `\`). The corresponding source file
                      was relocated, which typically changes its Java package. Update
                      any fully-qualified class references, `import` statements, or
                      package mentions in the title or body to reflect the new package
                      implied by the new directory.

                    Your task:
                    - Update the title AND the body to use the NEW names and NEW
                      packages.
                    - Apply the SAME rename decisions to title and body (they describe
                      the same change).
                    - Keep the meaning and structure exactly the same.
                    - Preserve all formatting, punctuation, and sentence structure.
                    - If a name doesn't appear in the mapping, leave it unchanged.

                    Important:
                    - Do NOT add new information.
                    - Do NOT remove information.
                    - Do NOT rephrase or rewrite the content.
                    - ONLY update the names and packages indicated by the rename
                      mapping.

                    Output: a JSON object with two fields, `title` and `body`, holding
                    the updated values:
                    ```json
                    { "title": "...", "body": "..." }
                    ```
                """.trimIndent())
            }

            user {
                text("## Original Title:")
                newline()
                text("'''")
                newline()
                text(block.title)
                newline()
                text("'''")
                text("\n\n")

                text("## Original Body:")
                newline()
                text("'''")
                newline()
                text(block.body)
                newline()
                text("'''")
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
                text("Now, update both the title and the body with the new names and packages.")
            }
        }
    }
}
