package com.github.pderakhshanfar.codecocoonplugin.appstarter

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.services.ParaphraseTextTransformer
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * Application starter for paraphrasing a problem statement in headless mode.
 * Entry point when the IDE is launched with the 'rewrite-problem-statement' command.
 */
class RewriteProblemStatementStarter : ApplicationStarter {
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT
    private val logger = thisLogger().withStdout()

    override fun main(args: List<String>) {
        val problemStatement = System.getProperty("rewrite.problemStatement") ?: ""
        val outputFile = System.getProperty("rewrite.outputFile") ?: ""

        if (problemStatement.isEmpty() || outputFile.isEmpty()) {
            logger.error("[RewriteProblemStatement] Missing required parameters")
            logger.error("[RewriteProblemStatement] Required system properties: rewrite.problemStatement, rewrite.outputFile")
            exitProcess(1)
        }

        val token = System.getenv("GRAZIE_TOKEN")
        if (token == null) {
            logger.error("[RewriteProblemStatement] GRAZIE_TOKEN environment variable not set")
            exitProcess(1)
        }

        runBlocking {
            try {
                logger.info("[RewriteProblemStatement] Starting problem-statement paraphrase")

                val llm = LLM.fromGrazie(
                    model = OpenAIModels.Chat.GPT5Mini,
                    token = token
                )

                val transformer = ParaphraseTextTransformer(llm)

                val result = transformer.rewrite(problemStatement)

                if (result != null) {
                    val json = Json.encodeToString(result)
                    File(outputFile).writeText(json)
                    logger.info("[RewriteProblemStatement] SUCCESS: Paraphrased problem statement written to: $outputFile")
                    exitProcess(0)
                } else {
                    logger.error("[RewriteProblemStatement] ERROR: Failed to paraphrase problem statement")
                    exitProcess(1)
                }
            } catch (e: Exception) {
                logger.error("[RewriteProblemStatement] ERROR: ${e.message}", e)
                e.printStackTrace(System.err)
                exitProcess(1)
            }
        }
    }
}
