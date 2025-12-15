package com.github.pderakhshanfar.codecocoonplugin.appstarter

import com.github.pderakhshanfar.codecocoonplugin.components.transformations.TransformationRegistry
import com.github.pderakhshanfar.codecocoonplugin.config.CodeCocoonConfig
import com.github.pderakhshanfar.codecocoonplugin.config.ConfigLoader
import com.github.pderakhshanfar.codecocoonplugin.intellij.JvmProjectConfigurator
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.services.TransformationService
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Application starter for running CodeCocoon in headless mode.
 * This is the entry point when the IDE is launched with the 'codecocoon' command.
 */
class HeadlessModeStarter : ApplicationStarter {
    /** Sets the main (start) thread for the IDE in headless as not EDT. */
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT
    private val logger = thisLogger().withStdout()

    override fun main(args: List<String>) {
        val config = ConfigLoader.load()
        val projectPath = config.projectRoot
        if (projectPath.isNullOrBlank()) {
            logger.error("[CodeCocoon Starter] Missing project path. Set 'projectRoot' in codecocoon.yml")
            exitProcess(1)
        }

        logger.info("[CodeCocoon Starter] Starting with project path: $projectPath")

        // Clean .idea folder to ensure fresh indexing
        cleanIdeaFolder(projectPath)

        // Use runBlocking to run coroutine-based code
        runBlocking {
            val disposable = Disposer.newDisposable()
            try {
                // Open and resolve the project
                val project = openProject(projectPath, disposable)

                val transformations = mapToTransformations(config)

                val plan = transformations.joinToString(", ") { it.id }
                logger.info("[TransformationService] Planned transformations: [$plan]")

                // Execute a transformation pipeline using the service
                runCatching {
                    val transformationService = service<TransformationService>()
                    // TODO: add `AddCommentTransformation` into config yaml
                    transformationService.executeTransformations(project, config, transformations)
                }.onFailure { err ->
                    logger.error("[CodeCocoon Starter] Transformation Service failed with exception", err)
                    err.printStackTrace(System.err)
                }.onSuccess {
                    logger.info("[CodeCocoon Starter] Transformation Service completed successfully")
                }

                // close project and exit
                logger.info("[CodeCocoon Starter] Execution completed")

                withContext(Dispatchers.EDT) {
                    ProjectManager.getInstance().closeAndDispose(project)
                    logger.info("[CodeCocoon Starter] Project is closed successfully")
                }
                Disposer.dispose(disposable)
                exitProcess(0)
            } catch (e: Throwable) {
                logger.error("[CodeCocoon Starter] Execution failed with exception", e)
                e.printStackTrace(System.err)
                Disposer.dispose(disposable)
                exitProcess(1)
            }
        }
    }

    private fun cleanIdeaFolder(projectPath: String) {
        val ideaFolderPath = "$projectPath${File.separator}.idea"
        val ideaFolder = File(ideaFolderPath)
        if (ideaFolder.exists()) {
            logger.info("[CodeCocoon Starter] Removing existing .idea folder")
            ideaFolder.deleteRecursively()
        }
    }

    private suspend fun openProject(projectPath: String, disposable: Disposable) = try {
        logger.info("[CodeCocoon Starter] Opening project: $projectPath")

        val project = JvmProjectConfigurator().openProject(
            Paths.get(projectPath),
            parentDisposable = disposable,
            fullResolveRequired = true,
        )

        logger.info("[CodeCocoon Starter] Project opened successfully: ${project.name}")
        project
    } catch (e: Throwable) {
        logger.error("[CodeCocoon Starter] Failed to open project", e)
        throw e
    }

    /**
     * Resolves transformation ids from YAML to concrete Transformation instances via the registry.
     * - Preserves the original order from the config.
     * - Enforces uniqueness: throws on duplicate ids.
     * - Throws on unknown ids and lists known ids to help configuration.
     */
    private fun mapToTransformations(config: CodeCocoonConfig): List<Transformation> {
        if (config.transformations.isEmpty()) return emptyList()

        val seen = LinkedHashSet<String>()
        val result = mutableListOf<Transformation>()

        for (t in config.transformations) {
            val id = t.id
            if (!seen.add(id)) {
                throw IllegalArgumentException("Duplicate transformation id='$id' in codecocoon.yml. Ids must be unique.")
            }
            val instance = TransformationRegistry.create(id, t.config) ?: run {
                val known = TransformationRegistry.knownIds().sorted().joinToString(", ")
                throw IllegalArgumentException("Unknown transformation id='$id'. Known ids: [$known]")
            }

            result.add(instance)
        }

        return result
    }
}
