package com.github.pderakhshanfar.codecocoonplugin.appstarter

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.services.MetamorphicTextTransformer
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Application starter for running text transformation in headless mode.
 * Reads a benchmark-record JSON file, applies rename/move sync to the main
 * `{title, body}` block and each `resolved_issues[i].{title, body}` block (one LLM
 * call per block so title and body stay coherent), and writes a same-schema JSON file.
 *
 * Entry point when the IDE is launched with the 'transform-texts' command.
 */
class TransformTextsStarter : ApplicationStarter {
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT
    private val logger = thisLogger().withStdout()

    override fun main(args: List<String>) {
        val memoryFile = System.getProperty("transform.memoryFile") ?: ""
        val inputFile = System.getProperty("transform.inputFile") ?: ""
        val outputFile = System.getProperty("transform.outputFile") ?: ""

        if (memoryFile.isEmpty() || inputFile.isEmpty() || outputFile.isEmpty()) {
            logger.error("[TransformTexts] Missing required parameters")
            logger.error("[TransformTexts] Required system properties: transform.memoryFile, transform.inputFile, transform.outputFile")
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
                logger.info("[TransformTexts] Input file:  $inputFile")

                val llm = LLM.fromGrazie(
                    model = OpenAIModels.Chat.GPT5Mini,
                    token = token,
                )
                val transformer = MetamorphicTextTransformer(llm)

                val renameMap = transformer.loadRenameMap(memoryFile)
                if (renameMap == null) {
                    logger.error("[TransformTexts] Failed to load rename map from '$memoryFile'")
                    exitProcess(1)
                }

                val inputJson = BenchmarkInstanceIO.json
                    .parseToJsonElement(File(inputFile).readText())
                    .jsonObject

                val outputJson = if (renameMap.isEmpty()) {
                    logger.warn("[TransformTexts] Rename map is empty; copying input verbatim")
                    inputJson
                } else {
                    BenchmarkInstanceIO.transformInstance(inputJson) { block ->
                        try {
                            transformer.transformBlock(block, renameMap)
                        } catch (e: Exception) {
                            logger.error("[TransformTexts] Block-level transformation failed; keeping original block: ${e.message}", e)
                            null
                        }
                    }
                }

                File(outputFile).writeText(BenchmarkInstanceIO.json.encodeToString(JsonObject.serializer(), outputJson))
                logger.info("[TransformTexts] SUCCESS: Transformed record written to: $outputFile")
                exitProcess(0)
            } catch (e: Exception) {
                logger.error("[TransformTexts] ERROR: ${e.message}", e)
                e.printStackTrace(System.err)
                exitProcess(1)
            }
        }
    }
}
