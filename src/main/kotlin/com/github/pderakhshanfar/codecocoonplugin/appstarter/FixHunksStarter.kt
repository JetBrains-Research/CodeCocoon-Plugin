package com.github.pderakhshanfar.codecocoonplugin.appstarter

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.suggestions.impl.FixHunksInput
import com.github.pderakhshanfar.codecocoonplugin.suggestions.impl.runImportHunkFixerAgent
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * Application starter for reverting unwanted import-hunks via a Koog agent.
 *
 * Reads a JSON file describing import_reorder / wildcard_import_removal hunks
 * inside a Java repo and runs an agent that surgically reverts each hunk in
 * the corresponding source file. Hunks are processed in batches to keep each
 * agent invocation focused.
 *
 * Entry point when the IDE is launched with the 'agent-fix-hunks' command.
 */
class FixHunksStarter : ApplicationStarter {
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT
    private val logger = thisLogger().withStdout()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override fun main(args: List<String>) {
        val inputFile = System.getProperty("fix.inputFile") ?: ""
        val batchSize = System.getProperty("fix.batchSize")?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_BATCH_SIZE
        val maxAgentIterations = System.getProperty("fix.maxAgentIterations")?.toIntOrNull()?.takeIf { it > 0 }
            ?: DEFAULT_MAX_AGENT_ITERATIONS

        if (inputFile.isEmpty()) {
            logger.error("[FixHunks] Missing required parameter: -Pinput=<path-to-hunks.json>")
            exitProcess(1)
        }

        val token = System.getenv("GRAZIE_TOKEN")
        if (token == null) {
            logger.error("[FixHunks] GRAZIE_TOKEN environment variable not set")
            exitProcess(1)
        }

        val inputJsonText = try {
            File(inputFile).readText()
        } catch (e: Exception) {
            logger.error("[FixHunks] Cannot read input file '$inputFile': ${e.message}", e)
            exitProcess(1)
        }

        val input = try {
            json.decodeFromString(FixHunksInput.serializer(), inputJsonText)
        } catch (e: Exception) {
            logger.error("[FixHunks] Failed to parse input JSON '$inputFile': ${e.message}", e)
            exitProcess(1)
        }

        val repoRoot = File(input.repoRoot).absoluteFile
        if (!repoRoot.isDirectory) {
            logger.error("[FixHunks] repo_root does not exist or is not a directory: ${input.repoRoot}")
            exitProcess(1)
        }

        if (input.hunks.isEmpty()) {
            logger.warn("[FixHunks] No hunks to revert; exiting successfully")
            exitProcess(0)
        }

        val batches = input.hunks.chunked(batchSize)
        logger.info("[FixHunks] Reverting ${input.hunks.size} hunks across ${batches.size} batch(es) of <= $batchSize")
        logger.info("[FixHunks] Repo root: ${repoRoot.absolutePath}")
        logger.info("[FixHunks] Description: ${input.description}")

        var failedBatches = 0
        runBlocking {
            for ((index, batch) in batches.withIndex()) {
                val batchNum = index + 1
                val fileCount = batch.map { it.file }.distinct().size
                logger.info("[FixHunks] Batch $batchNum/${batches.size}: ${batch.size} hunks across $fileCount file(s)")
                try {
                    val result = runImportHunkFixerAgent(
                        token = token,
                        model = OpenAIModels.Chat.GPT5Mini,
                        repoRoot = repoRoot.absolutePath,
                        batchDescription = input.description,
                        hunks = batch,
                        maxAgentIterations = maxAgentIterations,
                    )
                    result.fold(
                        onSuccess = { agentReport ->
                            logger.info("[FixHunks] Batch $batchNum/${batches.size} done")
                            logger.info("[FixHunks] Agent report:\n$agentReport")
                        },
                        onFailure = { e ->
                            failedBatches++
                            logger.error("[FixHunks] Batch $batchNum/${batches.size} FAILED: ${e.message}", e)
                        },
                    )
                } catch (e: Exception) {
                    failedBatches++
                    logger.error("[FixHunks] Batch $batchNum/${batches.size} threw: ${e.message}", e)
                }
            }
        }

        if (failedBatches > 0) {
            logger.error("[FixHunks] Completed with $failedBatches failed batch(es) out of ${batches.size}")
            exitProcess(1)
        }
        logger.info("[FixHunks] SUCCESS: all ${batches.size} batches applied")
        exitProcess(0)
    }

    companion object {
        private const val DEFAULT_BATCH_SIZE = 5
        private const val DEFAULT_MAX_AGENT_ITERATIONS = 60
    }
}
