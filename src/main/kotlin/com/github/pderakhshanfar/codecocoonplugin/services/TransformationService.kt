package com.github.pderakhshanfar.codecocoonplugin.services

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.waitForSmartMode
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Application-level service responsible for managing metamorphic transformations
 * on projects in headless mode.
 *
 * This service orchestrates the entire transformation lifecycle:
 * 1. Wait for project indexing to complete
 * 2. Discover and list project files
 * 3. Apply transformations (future: renaming, refactoring, etc.)
 */
@Service(Service.Level.APP)
class TransformationService {
    private val logger = thisLogger()

    /**
     * Executes the transformation pipeline for a given project.
     *
     * @param project The opened project to transform
     * @throws Exception if any step fails
     */
    suspend fun executeTransformations(project: Project, projectPath: String) {
        logger.info("[TransformationService] Starting transformation pipeline for project: ${project.name}")
        println("[TransformationService] Starting transformation pipeline for project: ${project.name}")

        // Step 1: Wait for indexing to complete
        // waitForIndexing(project)

        // Step 2: List all project files
        val files = listProjectFiles(project)

        // Step 3: Print files to the console
        printFiles(files)

        logger.info("[TransformationService] Transformation pipeline completed successfully")
        println("[TransformationService] Transformation pipeline completed successfully")
    }

    // TODO: delete the method? because of `smartReadAction`
    /**
     * Waits for the project to finish indexing using modern coroutine API.
     * This replaces the old callback-based DumbService.runWhenSmart approach.
     */
    private suspend fun waitForIndexing(project: Project) {
        logger.info("[TransformationService] Waiting for project indexing to complete...")
        println("[TransformationService] Waiting for project indexing to complete...")

        // Modern coroutine-based approach - suspends until indexing is done
        // DumbService.getInstance(project).waitForSmartMode() // .suspendUntilSmartMode()
        project.waitForSmartMode()

        logger.info("[TransformationService] Project indexing completed - smart mode active")
        println("[TransformationService] Project indexing completed - smart mode active")
    }

    /**
     * Lists all files in the project, returning their paths relative to project root.
     * Uses read action to safely access virtual file system.
     */
    private suspend fun listProjectFiles(project: Project): List<String> = withContext(Dispatchers.IO) {
        logger.info("[TransformationService] Discovering project files...")
        println("[TransformationService] Discovering project files...")

        val files: List<String> = smartReadAction(project) {
            // TODO: user must provide the project base path; `guessProjectDir` should serve as a fallback
            val projectRoot = project.guessProjectDir()
                ?: throw IllegalStateException("Project base directory is null")

            buildList {
                // Visit all files recursively
                VfsUtilCore.visitChildrenRecursively(projectRoot, object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (!file.isDirectory) {
                            // Get path relative to project root
                            val relativePath = VfsUtilCore.getRelativePath(file, projectRoot)
                            if (relativePath != null && relativePath.startsWith("src")) {
                                add(relativePath)
                            }
                        }
                        return true
                    }
                })
            }.sorted()
        }

        logger.info("[TransformationService] Found ${files.size} files in project")
        println("[TransformationService] Found ${files.size} files in project")

        files
    }

    /**
     * Prints the list of files to console.
     */
    private fun printFiles(files: List<String>) {
        println("\n=== Project Files (${files.size} total) ===")
        files.forEach { println(it) }
        println("=== End of File List ===\n")
    }

    // Future extension point for transformation strategies
    // suspend fun applyTransformation(project: Project, strategy: TransformationStrategy)
}
