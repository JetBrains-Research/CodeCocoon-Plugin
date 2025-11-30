package com.github.pderakhshanfar.codecocoonplugin.services

import com.github.pderakhshanfar.codecocoonplugin.config.CodeCocoonConfig
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation
import com.github.pderakhshanfar.codecocoonplugin.transformation.TransformationRegistry
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
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
     * @param config The loaded CodeCocoon configuration controlling the run
     * @throws Exception if any step fails
     */
    suspend fun executeTransformations(project: Project, config: CodeCocoonConfig) {

        // Step 1: List project files according to config
        val files = listProjectFiles(project, config.projectRoot, includeOnly = config.files)

        // Step 2: Print files to the console
        printFiles(files)

        val transformations = mapToTransformations(config)
        // TODO: Step 3: Apply transformations

        logger.info("[TransformationService] Transformation pipeline completed successfully")
        println("[TransformationService] Transformation pipeline completed successfully")
    }

    /**
     * Lists all files in the project, returning their paths relative to project root.
     * Uses [smartReadAction] to safely access virtual file system.
     * If [includeOnly] is non-empty, only include those exact relative paths.
     */
    private suspend fun listProjectFiles(project: Project, rootPath: String?, includeOnly: List<String>): List<String> {
        logger.info("[TransformationService] Discovering project files...")
        // It is good practice to log which path is being used
        if (rootPath != null) logger.info("[TransformationService] Using provided root path: $rootPath")

        val files: List<String> = smartReadAction(project) {
            val projectRoot: VirtualFile = rootPath?.let { path ->
                LocalFileSystem.getInstance().findFileByPath(path)
            } ?: project.guessProjectDir()
            ?: throw IllegalStateException("Project base directory is null")

            val includeSet = includeOnly.toSet()

            buildList {
                // Visit all files recursively
                VfsUtilCore.visitChildrenRecursively(projectRoot, object : VirtualFileVisitor<Unit>() {
                    override fun visitFile(file: VirtualFile): Boolean {
                        if (!file.isDirectory) {
                            // Get path relative to the resolved project root
                            val relativePath = VfsUtilCore.getRelativePath(file, projectRoot)
                            if (relativePath != null && relativePath.startsWith("src")) {
                                if (includeSet.isEmpty()) {
                                    add(relativePath)
                                } else if (relativePath in includeSet
                                    || relativePath.substringAfterLast('/') in includeSet
                                ) {
                                    add(relativePath)
                                }
                            }
                        }
                        return true
                    }
                })
            }.sorted()
        }

        logger.info("[TransformationService] Found ${files.size} files in project")
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

        val plan = result.joinToString(", ") { it.id }
        logger.info("[TransformationService] Planned transformations: [$plan]")
        return result
    }

    /*suspend fun applyTransformation(project: Project, strategy: TransformationStrategy) {
        TODO(implement)
    }*/
}
