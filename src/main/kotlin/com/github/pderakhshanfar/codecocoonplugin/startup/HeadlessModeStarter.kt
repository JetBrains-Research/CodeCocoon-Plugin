package com.github.pderakhshanfar.codecocoonplugin.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File
import java.nio.file.Paths
import kotlin.system.exitProcess

class HeadlessModeStarter : ApplicationStarter {
    /** Sets the main (start) thread for the IDE in headless as not edt. */
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT

    @OptIn(ExperimentalSerializationApi::class)
    override fun main(args: List<String>) {

        // Project path
        val projectPath = args[1]

        // remove the `.idea` folder in the $projectPath if exists
        val ideaFolderPath = "$projectPath${File.separator}.idea"
        val ideaFolder = File(ideaFolderPath)
        if (ideaFolder.exists()) {
            ideaFolder.deleteRecursively()
        }

        println("[CodeCocoon Starter] Opening project")

        // open and resolve the project
        val project = try {
            JvmProjectConfigurator().openProject(
                Paths.get(projectPath),
                fullResolve = true,
                parentDisposable = Disposer.newDisposable(),
            )
        } catch (e: Throwable) {
            e.printStackTrace(System.err)
            exitProcess(1)
        }

        println("[CodeCocoon Starter] Project opened successfully: ${project}")

        ApplicationManager.getApplication().invokeAndWait {
            project.let {
                DumbService.getInstance(it).runWhenSmart {
                    try {
                        println("[CodeCocoon] Project is smart")
                    } catch (e: Throwable) {
                        thisLogger().error("[CodeCocoon Starter] Exiting the headless mode with an exception")

                        ProjectManager.getInstance().closeAndDispose(project)
                        e.printStackTrace(System.err)
                        exitProcess(1)
                    }
                    ProjectManager.getInstance().closeAndDispose(project)
                    exitProcess(0)
                }
            }
        }
        thisLogger().warn("[CodeCocoon Starter] Smart mode not initialized")
        ProjectManager.getInstance().closeAndDispose(project)
        exitProcess(1)
    }
}
