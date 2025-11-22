package com.github.pderakhshanfar.codecocoonplugin.appstarter

import com.github.pderakhshanfar.codecocoonplugin.services.TransformationService
import com.github.pderakhshanfar.codecocoonplugin.components.JvmProjectConfigurator
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

    override fun main(args: List<String>) {
        val logger = thisLogger()

        // Validate arguments
        if (args.size < 2) {
            logger.error("[CodeCocoon Starter] Missing project path argument")
            System.err.println("Usage: codecocoon <project-path>")
            exitProcess(1)
        }

        val projectPath = args[1]
        logger.info("[CodeCocoon Starter] Starting with project path: $projectPath")

        // Clean .idea folder to ensure fresh indexing
        cleanIdeaFolder(projectPath)

        // Use runBlocking to run coroutine-based code
        runBlocking {
            val disposable = Disposer.newDisposable()
            try {
                // Open and resolve the project
                val project = openProject(projectPath, disposable)

                // Execute a transformation pipeline using the service
                val transformationService = service<TransformationService>()
                transformationService.executeTransformations(project, projectPath)

                // Success - clean exit
                logger.info("[CodeCocoon Starter] Execution completed successfully")

                withContext(Dispatchers.EDT) {
                    ProjectManager.getInstance().closeAndDispose(project)
                    logger.info("[CodeCocoon Starter] Project is closed successfully")
                    println("[CodeCocoon Starter] Project is closed successfully")
                }

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
            thisLogger().info("[CodeCocoon Starter] Removing existing .idea folder")
            ideaFolder.deleteRecursively()
        }
    }

    private suspend fun openProject(projectPath: String, disposable: Disposable) = try {
        thisLogger().info("[CodeCocoon Starter] Opening project: $projectPath")

        val project = JvmProjectConfigurator().openProject(
            Paths.get(projectPath),
            parentDisposable = disposable,
            fullResolveRequired = true,
        )

        thisLogger().info("[CodeCocoon Starter] Project opened successfully: ${project.name}")
        project
    } catch (e: Throwable) {
        thisLogger().error("[CodeCocoon Starter] Failed to open project", e)
        throw e
    }
}