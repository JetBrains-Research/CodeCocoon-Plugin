package com.github.pderakhshanfar.codecocoonplugin.services

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
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
 * 1. Discover and list project files
 * 1. Apply transformations (renaming, refactoring, etc.)
 */
@Service(Service.Level.APP)
class TransformationService {
    private val logger = thisLogger()

    /**
     * Executes the transformation pipeline for a given project.
     *
     * @param project The opened project to transform
     * @param projectPath The path to the project's root directory (usually user-provided)
     * @throws Exception if any step fails
     */
    suspend fun executeTransformations(project: Project, projectPath: String) {
        logger.info("[TransformationService] Starting transformation pipeline for project: ${project.name}")
        println("[TransformationService] Starting transformation pipeline for project: ${project.name}")

        // Step 1: List all project files
        val files = listProjectFiles(project)

        // Step 2: Print files to the console
        printFiles(files)

        logger.info("[TransformationService] Transformation pipeline completed successfully")
        println("[TransformationService] Transformation pipeline completed successfully")
    }

    /**
     * Lists all files in the project, returning their paths relative to project root.
     * Uses [smartReadAction] to safely access virtual file system.
     */
    private suspend fun listProjectFiles(project: Project): List<String> {
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

        return files
    }

    /**
     * Prints the list of files to console.
     */
    private fun printFiles(files: List<String>) {
        println("\n=== Project Files (${files.size} total) ===")
        files.forEach { println(it) }
        println("=== End of File List ===\n")
    }

    /*suspend fun applyTransformation(project: Project, strategy: TransformationStrategy) {
        TODO(implement)
    }*/
}
