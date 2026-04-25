package com.github.pderakhshanfar.codecocoonplugin.appstarter

import com.github.pderakhshanfar.codecocoonplugin.services.TextBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Helpers shared by [TransformTextsStarter] and [RewriteProblemStatementStarter] for
 * reading/writing benchmark-record JSON files of the schema:
 *
 * ```json
 * {
 *   "title": "...",
 *   "body": "...",
 *   "resolved_issues": [
 *     { "number": 1, "title": "...", "body": "..." }
 *   ]
 * }
 * ```
 *
 * The starters mutate a [JsonObject] in place rather than round-tripping through a
 * typed data class so any extra keys present in the benchmark record (or inside each
 * resolved issue) are preserved verbatim on output.
 *
 * Processing happens in BLOCKS — one transform call per `{title, body}` pair — so a
 * single LLM round-trip can keep title and body internally consistent. The main record
 * is one block; each resolved issue is another.
 */
internal object BenchmarkInstanceIO {

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Walks [obj], applying [transform] once to the main `{title, body}` block and once
     * to each `resolved_issues[i].{title, body}` block. When [transform] returns null
     * for a block, the original block is kept (so a single failure does not sink the
     * record).
     *
     * Keys other than `title`, `body`, and `resolved_issues` (and, inside each issue,
     * keys other than `title` / `body`) pass through verbatim. Output title/body are
     * only emitted when the corresponding key was present in the input.
     */
    suspend fun transformInstance(
        obj: JsonObject,
        transform: suspend (TextBlock) -> TextBlock?,
    ): JsonObject {
        val hasTitle = obj.containsKey("title")
        val hasBody = obj.containsKey("body")
        val mainBlock = TextBlock(
            title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
            body = obj["body"]?.jsonPrimitive?.contentOrNull ?: "",
        )
        val mainResult = transform(mainBlock) ?: mainBlock

        val newResolvedIssues = obj["resolved_issues"]?.jsonArray?.let { arr ->
            buildJsonArray {
                for (element in arr) {
                    val issue = element.jsonObject
                    val issueHasTitle = issue.containsKey("title")
                    val issueHasBody = issue.containsKey("body")
                    val issueBlock = TextBlock(
                        title = issue["title"]?.jsonPrimitive?.contentOrNull ?: "",
                        body = issue["body"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                    val issueResult = transform(issueBlock) ?: issueBlock

                    add(buildJsonObject {
                        for ((k, v) in issue) {
                            when (k) {
                                "title" -> if (issueHasTitle) put(k, JsonPrimitive(issueResult.title))
                                "body" -> if (issueHasBody) put(k, JsonPrimitive(issueResult.body))
                                else -> put(k, v)
                            }
                        }
                    })
                }
            }
        }

        return buildJsonObject {
            for ((k, v) in obj) {
                when (k) {
                    "title" -> if (hasTitle) put(k, JsonPrimitive(mainResult.title))
                    "body" -> if (hasBody) put(k, JsonPrimitive(mainResult.body))
                    "resolved_issues" -> put(k, newResolvedIssues ?: v)
                    else -> put(k, v)
                }
            }
        }
    }
}
