package com.github.pderakhshanfar.codecocoonplugin.appstarter

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.services.MetamorphicTextTransformer
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * Application starter for running text transformation in headless mode.
 * This is the entry point when the IDE is launched with the 'transform-texts' command.
 */
class TransformTextsStarter : ApplicationStarter {
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT
    private val logger = thisLogger().withStdout()

    override fun main(args: List<String>) {
        val memoryFile = System.getProperty("transform.memoryFile") ?: ""
        val problemStatement = System.getProperty("transform.problemStatement") ?: ""
        val outputFile = System.getProperty("transform.outputFile") ?: ""

        if (memoryFile.isEmpty() || problemStatement.isEmpty() || outputFile.isEmpty()) {
            logger.error("[TransformTexts] Missing required parameters")
            logger.error("[TransformTexts] Required system properties: transform.memoryFile, transform.problemStatement, transform.outputFile")
            exitProcess(1)
        }

        val token = System.getenv("GRAZIE_TOKEN")
        if (token == null) {
            logger.error("[TransformTexts] GRAZIE_TOKEN environment variable not set")
            exitProcess(1)
        }

        runBlocking {
            try {
                logger.info("[TransformTexts] Starting text transformation")
                logger.info("[TransformTexts] Memory file: $memoryFile")

                val llm = LLM.fromGrazie(
                    model = OpenAIModels.Chat.GPT5Mini,
                    token = token
                )

                val transformer = MetamorphicTextTransformer(llm)

                val result = transformer.transformTexts(
                    problemStatement = problemStatement,
                    memoryFilePath = memoryFile
                )

                if (result != null) {
                    val json = Json.encodeToString(result)
                    File(outputFile).writeText(json)
                    logger.info("[TransformTexts] SUCCESS: Transformed texts written to: $outputFile")
                    exitProcess(0)
                } else {
                    logger.error("[TransformTexts] ERROR: Failed to transform texts")
                    exitProcess(1)
                }
            } catch (e: Exception) {
                logger.error("[TransformTexts] ERROR: ${e.message}", e)
                e.printStackTrace(System.err)
                exitProcess(1)
            }
        }
    }
}
