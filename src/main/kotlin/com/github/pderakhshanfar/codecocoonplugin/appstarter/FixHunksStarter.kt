package com.github.pderakhshanfar.codecocoonplugin.appstarter

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.suggestions.impl.FixHunksInput
import com.github.pderakhshanfar.codecocoonplugin.suggestions.impl.runImportHunkFixerAgent
import com.github.pderakhshanfar.codecocoonplugin.suggestions.impl.unappliedSummary
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
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
        val outputFile = System.getProperty("fix.outputFile") ?: ""
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
        val typeCounts = input.hunks.groupingBy { it.hunkType }.eachCount()
        logger.info("[FixHunks] Reverting ${input.hunks.size} hunks across ${batches.size} batch(es) of <= $batchSize")
        logger.info("[FixHunks] Repo root: ${repoRoot.absolutePath}")
        logger.info("[FixHunks] Patch label: ${input.patchLabel.ifEmpty { "<none>" }}")
        logger.info("[FixHunks] Hunk types: $typeCounts")
        logger.info("[FixHunks] Description: ${input.description}")

        var failedBatches = 0
        val allFixedIds = mutableListOf<String>()
        val allUnfixed = mutableListOf<UnfixedRecord>()
        val batchSummaries = mutableListOf<BatchSummary>()
        val startedAtMs = System.currentTimeMillis()

        runBlocking {
            for ((index, batch) in batches.withIndex()) {
                val batchNum = index + 1
                val fileCount = batch.map { it.file }.distinct().size
                val batchStartedMs = System.currentTimeMillis()
                logger.info("[FixHunks] Batch $batchNum/${batches.size}: ${batch.size} hunks across $fileCount file(s)")
                val result = try {
                    runImportHunkFixerAgent(
                        token = token,
                        model = OpenAIModels.Chat.GPT5Mini,
                        repoRoot = repoRoot.absolutePath,
                        batchDescription = input.description,
                        hunks = batch,
                        maxAgentIterations = maxAgentIterations,
                    )
                } catch (e: Exception) {
                    failedBatches++
                    logger.error("[FixHunks] Batch $batchNum/${batches.size} threw: ${e.message}", e)
                    batch.forEach { allUnfixed += UnfixedRecord(it.id, it.file, it.hunkType, "agent threw: ${e.message}") }
                    batchSummaries += BatchSummary(
                        batchNum = batchNum,
                        hunkIds = batch.map { it.id },
                        applied = emptyList(),
                        unapplied = batch.map { UnfixedRecord(it.id, it.file, it.hunkType, "agent threw: ${e.message}") },
                        agentReport = null,
                        outcome = "agent_threw",
                        errorMessage = e.message,
                        elapsedMs = System.currentTimeMillis() - batchStartedMs,
                    )
                    null
                }

                if (result != null) {
                    val unappliedRecords = result.verifications
                        .filterNot { it.applied }
                        .map { UnfixedRecord(it.hunkId, it.file, it.hunkType, it.reason) }
                    allFixedIds += result.appliedHunkIds
                    allUnfixed += unappliedRecords
                    val appliedCount = result.verifications.count { it.applied }
                    val outcome = when {
                        result.isFullySuccessful -> "success"
                        result.error != null -> "agent_failed"
                        else -> "verification_failed"
                    }

                    when (outcome) {
                        "success" -> logger.info("[FixHunks] Batch $batchNum/${batches.size} done ($appliedCount/${batch.size} applied)")
                        "agent_failed" -> {
                            failedBatches++
                            val err = result.error!!
                            logger.error(
                                "[FixHunks] Batch $batchNum/${batches.size} agent FAILED " +
                                    "($appliedCount/${batch.size} hunks applied before failure): ${err.message}",
                                err,
                            )
                        }
                        else -> {
                            failedBatches++
                            logger.error(
                                "[FixHunks] Batch $batchNum/${batches.size} verification failed " +
                                    "($appliedCount/${batch.size} hunks applied). Unapplied:\n${result.unappliedSummary()}",
                            )
                        }
                    }
                    result.agentReport?.let { logger.info("[FixHunks] Agent report:\n$it") }

                    batchSummaries += BatchSummary(
                        batchNum = batchNum,
                        hunkIds = batch.map { it.id },
                        applied = result.appliedHunkIds,
                        unapplied = unappliedRecords,
                        agentReport = result.agentReport,
                        outcome = outcome,
                        errorMessage = result.error?.message,
                        elapsedMs = System.currentTimeMillis() - batchStartedMs,
                    )
                }
            }
        }

        val elapsedMs = System.currentTimeMillis() - startedAtMs

        if (outputFile.isNotEmpty()) {
            writeOutputJson(
                path = outputFile,
                fixedIds = allFixedIds,
                input = input,
                batchSummaries = batchSummaries,
                allUnfixed = allUnfixed,
                failedBatches = failedBatches,
                totalBatches = batches.size,
                batchSize = batchSize,
                elapsedMs = elapsedMs,
            )
        }

        logger.info("[FixHunks] Total fixed hunks across all batches: ${allFixedIds.size}/${input.hunks.size}")
        if (allUnfixed.isNotEmpty()) {
            logger.warn("[FixHunks] Unfixed hunks (${allUnfixed.size}):")
            allUnfixed.forEach { (id, file, _, reason) ->
                val tag = if (id.isNotEmpty()) "$id " else ""
                logger.warn("[FixHunks]   - $tag$file: $reason")
            }
        }

        if (failedBatches > 0) {
            logger.error("[FixHunks] Completed with $failedBatches failed batch(es) out of ${batches.size}")
            exitProcess(1)
        }
        logger.info("[FixHunks] SUCCESS: all ${batches.size} batches applied")
        exitProcess(0)
    }

    private data class UnfixedRecord(
        val id: String,
        val file: String,
        val hunkType: String,
        val reason: String,
    )

    private data class BatchSummary(
        val batchNum: Int,
        val hunkIds: List<String>,
        val applied: List<String>,
        val unapplied: List<UnfixedRecord>,
        val agentReport: String?,
        val outcome: String,            // "success" | "verification_failed" | "agent_failed" | "agent_threw"
        val errorMessage: String?,
        val elapsedMs: Long,
    )

    private fun writeOutputJson(
        path: String,
        fixedIds: List<String>,
        input: FixHunksInput,
        batchSummaries: List<BatchSummary>,
        allUnfixed: List<UnfixedRecord>,
        failedBatches: Int,
        totalBatches: Int,
        batchSize: Int,
        elapsedMs: Long,
    ) {
        try {
            val deduped = fixedIds.distinct()
            val obj: JsonObject = buildJsonObject {
                put("fixed", buildJsonArray { deduped.forEach { add(JsonPrimitive(it)) } })
                put("unfixed", buildJsonArray {
                    allUnfixed.forEach { rec ->
                        add(buildJsonObject {
                            put("id", JsonPrimitive(rec.id))
                            put("file", JsonPrimitive(rec.file))
                            put("hunk_type", JsonPrimitive(rec.hunkType))
                            put("reason", JsonPrimitive(rec.reason))
                        })
                    }
                })
                put("summary", buildJsonObject {
                    put("total_hunks", JsonPrimitive(input.hunks.size))
                    put("fixed_count", JsonPrimitive(deduped.size))
                    put("unfixed_count", JsonPrimitive(allUnfixed.size))
                    put("total_batches", JsonPrimitive(totalBatches))
                    put("failed_batches", JsonPrimitive(failedBatches))
                    put("batch_size", JsonPrimitive(batchSize))
                    put("elapsed_ms", JsonPrimitive(elapsedMs))
                    put("repo_root", JsonPrimitive(input.repoRoot))
                    put("patch_label", JsonPrimitive(input.patchLabel))
                })
                put("agent", buildJsonObject {
                    put("model", JsonPrimitive(OpenAIModels.Chat.GPT5Mini.id))
                    put("batches", buildJsonArray {
                        batchSummaries.forEach { b ->
                            add(buildJsonObject {
                                put("batch_num", JsonPrimitive(b.batchNum))
                                put("outcome", JsonPrimitive(b.outcome))
                                put("elapsed_ms", JsonPrimitive(b.elapsedMs))
                                put("hunk_ids", buildJsonArray { b.hunkIds.forEach { add(JsonPrimitive(it)) } })
                                put("applied", buildJsonArray { b.applied.forEach { add(JsonPrimitive(it)) } })
                                put("unapplied", buildJsonArray {
                                    b.unapplied.forEach { rec ->
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive(rec.id))
                                            put("file", JsonPrimitive(rec.file))
                                            put("hunk_type", JsonPrimitive(rec.hunkType))
                                            put("reason", JsonPrimitive(rec.reason))
                                        })
                                    }
                                })
                                b.errorMessage?.let { put("error", JsonPrimitive(it)) }
                                b.agentReport?.let { put("report", JsonPrimitive(it)) }
                            })
                        }
                    })
                })
            }
            File(path).also { it.parentFile?.mkdirs() }.writeText(json.encodeToString(JsonObject.serializer(), obj))
            logger.info("[FixHunks] Wrote ${deduped.size} fixed hunk id(s) + agent reports to: $path")
        } catch (e: Exception) {
            logger.error("[FixHunks] Failed to write output file '$path': ${e.message}", e)
        }
    }

    companion object {
        private const val DEFAULT_BATCH_SIZE = 8
        private const val DEFAULT_MAX_AGENT_ITERATIONS = 70
    }
}
