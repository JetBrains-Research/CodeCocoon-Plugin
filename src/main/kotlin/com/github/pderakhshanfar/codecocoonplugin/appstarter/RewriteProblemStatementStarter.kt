package com.github.pderakhshanfar.codecocoonplugin.appstarter

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.services.ParaphraseTextTransformer
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Application starter for paraphrasing the textual fields of a benchmark record in
 * headless mode. Reads a benchmark-record JSON file, paraphrases the main
 * `{title, body}` block and each `resolved_issues[i].{title, body}` block (one LLM
 * call per block so title and body stay coherent), and writes a same-schema JSON file.
 *
 * Entry point when the IDE is launched with the 'rewrite-problem-statement' command.
 */
class RewriteProblemStatementStarter : ApplicationStarter {
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT
    private val logger = thisLogger().withStdout()

    override fun main(args: List<String>) {
        val inputFile = System.getProperty("rewrite.inputFile") ?: ""
        val outputFile = System.getProperty("rewrite.outputFile") ?: ""

        if (inputFile.isEmpty() || outputFile.isEmpty()) {
            logger.error("[RewriteProblemStatement] Missing required parameters")
            logger.error("[RewriteProblemStatement] Required system properties: rewrite.inputFile, rewrite.outputFile")
            exitProcess(1)
        }

        val token = System.getenv("GRAZIE_TOKEN")
        if (token == null) {
            logger.error("[RewriteProblemStatement] GRAZIE_TOKEN environment variable not set")
            exitProcess(1)
        }

        runBlocking {
            try {
                logger.info("[RewriteProblemStatement] Starting paraphrase")
                logger.info("[RewriteProblemStatement] Input file: $inputFile")

                val llm = LLM.fromGrazie(
                    model = OpenAIModels.Chat.GPT5Mini,
                    token = token,
                )
                val transformer = ParaphraseTextTransformer(llm)

                val inputJson = BenchmarkInstanceIO.json
                    .parseToJsonElement(File(inputFile).readText())
                    .jsonObject

                val outputJson = BenchmarkInstanceIO.transformInstance(inputJson) { block ->
                    try {
                        transformer.rewriteBlock(block)
                    } catch (e: Exception) {
                        logger.error("[RewriteProblemStatement] Block-level paraphrase failed; keeping original block: ${e.message}", e)
                        null
                    }
                }

                File(outputFile).writeText(BenchmarkInstanceIO.json.encodeToString(JsonObject.serializer(), outputJson))
                logger.info("[RewriteProblemStatement] SUCCESS: Paraphrased record written to: $outputFile")
                exitProcess(0)
            } catch (e: Exception) {
                logger.error("[RewriteProblemStatement] ERROR: ${e.message}", e)
                e.printStackTrace(System.err)
                exitProcess(1)
            }
        }
    }
}
