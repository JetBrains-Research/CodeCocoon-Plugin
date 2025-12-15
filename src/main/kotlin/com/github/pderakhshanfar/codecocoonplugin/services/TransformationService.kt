package com.github.pderakhshanfar.codecocoonplugin.services

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.components.executor.IntelliJTransformationExecutor
import com.github.pderakhshanfar.codecocoonplugin.config.CodeCocoonConfig
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

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
    private val logger = thisLogger().withStdout()

    /**
     * Executes the transformation pipeline for a given project.
     *
     * @param project The opened project to transform
     * @param config The loaded CodeCocoon configuration controlling the run
     * @throws Exception if any step fails
     */
    suspend fun executeTransformations(
        project: Project,
        config: CodeCocoonConfig,
        transformations: List<Transformation>,
        fileFilter: (FileContext) -> Boolean = { true },
    ) {
        logger.info("[TransformationService] Starting transformation pipeline for project: ${project.name}")

        // TODO: remove in the future
        // Step 1: List project files according to config
        val files = listProjectFiles(project, config.projectRoot, includeOnly = config.files)
        // Step 2: Print files to the console
        printFiles(files)

        // Step 3: Apply transformations to the project files
        applyTransformations(project, config, transformations, fileFilter)

        logger.info("[TransformationService] Transformation pipeline completed successfully")
    }

    /**
     * Lists all files in the project, returning their paths relative to the project root.
     * Uses [smartReadAction] to safely access a virtual file system.
     * If [includeOnly] is non-empty, only include those exact relative paths.
     */
    private suspend fun listProjectFiles(
        project: Project,
        rootPath: String?,
        includeOnly: List<String>
    ): List<String> {
        logger.info("[TransformationService] Discovering project files...")
        // It is good practice to log which path is being used
        if (rootPath != null) {
            logger.info("[TransformationService] Using provided root path: $rootPath")
        }

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
     * Prints the list of files to the console.
     */
    private fun printFiles(files: List<String>) {
        println("\n=== Project Files (${files.size} total) ===")
        files.forEach { println(it) }
        println("=== End of File List ===\n")
    }

    private suspend fun applyTransformations(
        project: Project,
        config: CodeCocoonConfig,
        transformations: List<Transformation>,
        fileFilter: (FileContext) -> Boolean = { true }
    ) {
        logger.info("[TransformationService] Applying ${transformations.size} transformations")

        val files = listProjectFiles(project, config.projectRoot, includeOnly = config.files)
        val executor = IntelliJTransformationExecutor(project)

        var successCount = 0
        var failureCount = 0
        var skippedCount = 0

        for (filePath in files) {
            val context = createFileContext(filePath)

            if (!fileFilter(context)) {
                skippedCount++
                continue
            }

            for (transformation in transformations) {
                if (transformation.accepts(context)) {
                    logger.info("Applying ${transformation.name} to $filePath")

                    when (val result = executor.execute(transformation, context)) {
                        is TransformationResult.Success -> {
                            logger.info("✓ ${result.message}")
                            successCount++
                        }
                        is TransformationResult.Failure -> {
                            logger.error("✗ ${result.error}", result.exception)
                            failureCount++
                        }
                        is TransformationResult.Skipped -> {
                            logger.info("⊘ Skipped: ${result.reason}")
                            skippedCount++
                        }
                    }
                }
            }
        }

        logger.info("[TransformationService] Transformation summary: $successCount succeeded, $failureCount failed, $skippedCount skipped")
    }

    private fun createFileContext(relativePath: String): FileContext {
        val extension = relativePath.substringAfterLast('.', "")
        return FileContext(
            relativePath = relativePath,
            extension = extension,
            language = Language.fromExtension(extension)
        )
    }
}
