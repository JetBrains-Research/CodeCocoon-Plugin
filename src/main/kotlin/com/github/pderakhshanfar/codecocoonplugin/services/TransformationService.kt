package com.github.pderakhshanfar.codecocoonplugin.services

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.common.VirtualFileNotFound
import com.github.pderakhshanfar.codecocoonplugin.components.executor.IntelliJTransformationExecutor
import com.github.pderakhshanfar.codecocoonplugin.config.CodeCocoonConfig
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.intellij.vfs.findVirtualFile
import com.github.pderakhshanfar.codecocoonplugin.intellij.vfs.relativeToRootOrAbsPath
import com.github.pderakhshanfar.codecocoonplugin.memory.PersistentMemory
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
import java.io.File

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
                            if (relativePath != null && relativePath.contains("src/main/")) {
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
        logger.info("\n=== Project Files (${files.size} total) ===")
        files.forEach { logger.info(it) }
        logger.info("=== End of File List ===\n")
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

        // Create a global memory instance for the entire project
        // Memory is automatically saved via .use {} when the block exits
        val projectName = project.basePath?.let { File(it).name } ?: project.name
        PersistentMemory(projectName, config.memoryDir).use { memory ->
            logger.info("[TransformationService] Created global memory for project '$projectName'")

            var successCount = 0
            var failureCount = 0
            var skippedCount = 0

            // collect and filter file context (together with virtual files
            logger.info("[TransformationService] Collecting file contexts for ${files.size} files...")
            val filteredFileContexts = buildList {
                for (filePath in files) {
                    val result = createFileContext(project, filePath)

                    if (result.isFailure) {
                        logger.error(
                            "  ✗ Failed to create file context for file: '$filePath'. Skipping this filepath.",
                            result.exceptionOrNull(),
                        )
                        continue
                    }

                    val context = result.getOrThrow()
                    // filter unwanted files
                    if (!fileFilter(context)) {
                        skippedCount++
                        continue
                    }
                    add(context)
                }
            }
            logger.info("[TransformationService] Successfully collected (and filtered) ${filteredFileContexts.size} file contexts")

            // for each file, apply all transformations
            for (context in filteredFileContexts) {
                val filepath = project.relativeToRootOrAbsPath(context.virtualFile)
                logger.info("[TransformationService] Applying ${transformations.size} transformations to '$filepath':")

                for (transformation in transformations) {
                    if (transformation.accepts(context)) {
                        // NOTE: a virtual file contains an update-to-date fs filepath;
                        // since some transformation may change the file location,
                        // making `context.relativePath` obsolete.
                        val actualFilepath = project.relativeToRootOrAbsPath(context.virtualFile)
                        logger.info("  ⏲ Applying ${transformation.id} to '$actualFilepath'" +
                                if (context.relativePath != actualFilepath) " (initially '${context.relativePath}' (likely renamed))" else "")

                        when (val result = executor.execute(transformation, context, memory)) {
                            is TransformationResult.Success -> {
                                logger.info("    ✓ ${result.message}")
                                successCount++
                            }
                            is TransformationResult.Failure -> {
                                logger.error("    ✗ ${result.error}", result.exception)
                                failureCount++
                            }
                            is TransformationResult.Skipped -> {
                                logger.info("    ⊘ Skipped: ${result.reason}")
                                skippedCount++
                            }
                        }
                    }
                }
            }

            logger.info("[TransformationService] Transformation summary: $successCount succeeded, $failureCount failed, $skippedCount skipped")
        }
    }

    private fun createFileContext(
        project: Project,
        relativePath: String,
    ): Result<FileContext> {
        val virtualFile = project.findVirtualFile(relativePath)
            ?: return Result.failure(VirtualFileNotFound(
                "Project '${project.name}' doesn't contain file: '$relativePath'"))

        val extension = relativePath.substringAfterLast('.', "")
        val context = FileContext(
            virtualFile = virtualFile,
            relativePath = relativePath,
            extension = extension,
            language = Language.fromExtension(extension)
        )

        return Result.success(context)
    }
}
