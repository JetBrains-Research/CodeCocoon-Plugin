package com.github.pderakhshanfar.codecocoonplugin.appstarter

import com.github.pderakhshanfar.codecocoonplugin.intellij.JvmProjectConfigurator
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.services.DependencyAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Application starter for analyzing Java file dependencies in headless mode.
 * This is the entry point when the IDE is launched with the 'analyze-dependencies' command.
 */
class AnalyzeDependenciesStarter : ApplicationStarter {
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT
    private val logger = thisLogger().withStdout()

    @Serializable
    data class AnalysisOutput(
        val seedFiles: List<String>,
        val neighboringFiles: List<String>,
        val depth: Int,
        val totalFilesAnalyzed: Int
    )

    override fun main(args: List<String>) {
        val projectPath = System.getProperty("analyze.projectPath") ?: ""
        val seedFilesJson = System.getProperty("analyze.seedFiles") ?: ""
        val outputFile = System.getProperty("analyze.outputFile") ?: ""
        val depthStr = System.getProperty("analyze.depth") ?: "1"

        // Validate parameters
        if (projectPath.isEmpty()) {
            logger.error("[AnalyzeDependencies] Missing required parameter: analyze.projectPath")
            exitProcess(1)
        }

        if (seedFilesJson.isEmpty()) {
            logger.error("[AnalyzeDependencies] Missing required parameter: analyze.seedFiles")
            exitProcess(1)
        }

        if (outputFile.isEmpty()) {
            logger.error("[AnalyzeDependencies] Missing required parameter: analyze.outputFile")
            exitProcess(1)
        }

        val depth = depthStr.toIntOrNull() ?: run {
            logger.error("[AnalyzeDependencies] Invalid depth parameter: $depthStr (must be integer)")
            exitProcess(1)
        }

        if (depth < 1) {
            logger.error("[AnalyzeDependencies] Depth must be at least 1")
            exitProcess(1)
        }

        logger.info("[AnalyzeDependencies] Starting dependency analysis")
        logger.info("[AnalyzeDependencies] Project path: $projectPath")
        logger.info("[AnalyzeDependencies] Depth: $depth")
        logger.info("[AnalyzeDependencies] Output file: $outputFile")

        runBlocking {
            val disposable = Disposer.newDisposable()
            try {
                // Parse seed files from JSON
                val seedFiles = try {
                    Json.decodeFromString<List<String>>(seedFilesJson)
                } catch (e: Exception) {
                    logger.error("[AnalyzeDependencies] Failed to parse seedFiles JSON: ${e.message}", e)
                    exitProcess(1)
                }

                if (seedFiles.isEmpty()) {
                    logger.warn("[AnalyzeDependencies] No seed files provided, returning empty result")
                    val emptyResult = AnalysisOutput(
                        seedFiles = emptyList(),
                        neighboringFiles = emptyList(),
                        depth = depth,
                        totalFilesAnalyzed = 0
                    )
                    writeOutput(outputFile, emptyResult)
                    exitProcess(0)
                }

                logger.info("[AnalyzeDependencies] Analyzing ${seedFiles.size} seed files")

                // Open and resolve the project
                val project = openProject(projectPath, disposable)

                // Create dependency analyzer
                val analyzer = DependencyAnalyzer(project)

                // Analyze dependencies
                val result = analyzer.analyzeDependencies(seedFiles, depth)

                // Convert to output format
                val output = AnalysisOutput(
                    seedFiles = result.seedFiles,
                    neighboringFiles = result.neighboringFiles,
                    depth = result.depth,
                    totalFilesAnalyzed = result.totalFilesAnalyzed
                )

                // Write results
                writeOutput(outputFile, output)

                logger.info("[AnalyzeDependencies] SUCCESS: Found ${output.neighboringFiles.size} neighboring files")
                logger.info("[AnalyzeDependencies] Results written to: $outputFile")

                exitProcess(0)
            } catch (e: Throwable) {
                logger.error("[AnalyzeDependencies] ERROR: ${e.message}", e)
                e.printStackTrace(System.err)
                Disposer.dispose(disposable)
                exitProcess(1)
            } finally {
                Disposer.dispose(disposable)
            }
        }
    }

    private suspend fun openProject(projectPath: String, disposable: Disposable) = try {
        logger.info("[AnalyzeDependencies] Opening project: $projectPath")

        val project = JvmProjectConfigurator().openProject(
            Paths.get(projectPath),
            parentDisposable = disposable,
            fullResolveRequired = true,
        )

        logger.info("[AnalyzeDependencies] Project opened successfully: ${project.name}")
        project
    } catch (e: Throwable) {
        logger.error("[AnalyzeDependencies] Failed to open project", e)
        throw e
    }

    private fun writeOutput(outputFile: String, result: AnalysisOutput) {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        val jsonString = json.encodeToString(result)
        File(outputFile).writeText(jsonString)
    }
}
