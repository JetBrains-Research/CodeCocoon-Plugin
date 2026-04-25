package com.github.pderakhshanfar.codecocoonplugin.appstarter

import com.github.pderakhshanfar.codecocoonplugin.components.transformations.AddCommentTransformation
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.structural.MoveFileIntoSuggestedDirectoryTransformation
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.TransformationRegistry
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.renaming.RenameClassTransformation
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.renaming.RenameMethodTransformation
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.renaming.RenameVariableTransformation
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.structural.ReorderClassMethodsTransformation
import com.github.pderakhshanfar.codecocoonplugin.config.CodeCocoonConfig
import com.github.pderakhshanfar.codecocoonplugin.config.ConfigLoader
import com.github.pderakhshanfar.codecocoonplugin.intellij.JvmProjectConfigurator
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.services.TransformationService
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation
import com.intellij.application.options.CodeStyle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.codeStyle.JavaCodeStyleSettings
import kotlinx.coroutines.runBlocking
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

        // setting all props to System
        setupSystemProperties()

        // Clean .idea folder to ensure fresh indexing
        cleanIdeaFolder(projectPath)

        // Register transformations
        registerBuiltInTransformations()

        // Use runBlocking to run coroutine-based code
        runBlocking {
            val disposable = Disposer.newDisposable()
            try {
                // Open and resolve the project
                val project = openProject(projectPath, disposable)
                // configure project code style (e.g., avoid import optimization)
                configureCodeStyle(project)

                logger.info("[CodeCocoon Starter] Code style configured to prevent wildcard imports")

                val transformations = mapToTransformations(config)

                val plan = transformations.joinToString(", ") { it.id }
                logger.info("[TransformationService] Planned transformations: [$plan]")

                // Execute a transformation pipeline using the service
                runCatching {
                    val transformationService = service<TransformationService>()
                    transformationService.executeTransformations(project, config, transformations)
                }.onFailure { err ->
                    logger.error("[CodeCocoon Starter] Transformation Service failed with exception", err)
                    err.printStackTrace(System.err)
                }.onSuccess {
                    logger.info("[CodeCocoon Starter] Transformation Service completed successfully")
                }

                // close project and exit
                logger.info("[CodeCocoon Starter] Execution completed")

                ApplicationManager.getApplication().invokeAndWait {
                    ProjectManager.getInstance().closeAndDispose(project)
                    logger.info("[CodeCocoon Starter] Project is closed successfully")
                }
                Disposer.dispose(disposable)
                exitProcess(0)
            } catch (e: Throwable) {
                logger.error("[CodeCocoon Starter] Execution failed with exception", e)
                e.printStackTrace(System.err)
                Disposer.dispose(disposable)
                throw e
            }
        }
    }

    private fun setupSystemProperties() {
        // Disable all refactoring dialogs
        System.setProperty("ide.performance.skip.refactoring.dialogs", "true")
    }

    /**
     * Configures code style settings to prevent ALL import optimizations.
     *
     * This method configures multiple import-related settings to minimize unwanted
     * import modifications during refactoring operations when `commitAllDocuments()` is called.
     *
     * **Settings configured:**
     * 1. Prevents wildcard imports (e.g., `import com.example.*`)
     * 2. Forces single class imports
     * 3. Disables auto-insertion of inner class imports
     * 4. Clears packages that should use wildcards
     *
     * **Limitations:**
     * - Cannot prevent removal of unused imports (hardcoded in IntelliJ's optimize imports)
     * - Cannot prevent removal of redundant same-package imports
     *
     * **Important:** Call this method once when the project is opened/initialized,
     * before running any transformations.
     *
     * @param project The project whose code style settings should be configured
     */
    private fun configureCodeStyle(project: Project) {
        val settings = CodeStyle.getSettings(project)
        val javaSettings = settings.getCustomSettings(JavaCodeStyleSettings::class.java)

        // 1. Set thresholds to 9999 to effectively disable wildcard imports
        // This prevents IntelliJ from collapsing multiple imports into import com.example.*
        javaSettings.classCountToUseImportOnDemand = 9999
        javaSettings.namesCountToUseImportOnDemand = 9999

        // 2. Force single class imports (prevent wildcards)
        javaSettings.isUseSingleClassImports = true

        // 3. Don't auto-insert inner class imports
        javaSettings.isInsertInnerClassImports = false

        logger.info("[CodeCocoon Starter] Configured code style settings:")
        logger.info("  - CLASS_COUNT_TO_USE_IMPORT_ON_DEMAND: ${javaSettings.classCountToUseImportOnDemand}")
        logger.info("  - NAMES_COUNT_TO_USE_IMPORT_ON_DEMAND: ${javaSettings.namesCountToUseImportOnDemand}")
        logger.info("  - USE_SINGLE_CLASS_IMPORTS: ${javaSettings.isUseSingleClassImports}")
        logger.info("  - INSERT_INNER_CLASS_IMPORTS: ${javaSettings.isInsertInnerClassImports}")
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

        val basePath = project.basePath
        if (basePath != null) {
            val projectBaseDir = LocalFileSystem.getInstance().findFileByPath(basePath)
            if (projectBaseDir != null) {
                VfsUtil.markDirtyAndRefresh(false, true, true, projectBaseDir)
            }
        }
        logger.info("[CodeCocoon Starter] Project opened successfully: ${project.name}")
        project
    } catch (e: Throwable) {
        logger.error("[CodeCocoon Starter] Failed to open project", e)
        throw e
    }

    /**
     * Registers built-in transformations in the [TransformationRegistry].
     *
     * This function sets up predefined transformations that are available for use.
     * Each transformation is identified by a unique ID and is associated with a factory
     * function that creates an instance of the transformation when invoked.
     *
     * The registration process ensures that the transformation is correctly mapped by its
     * unique ID in the registry, allowing it to be referenced dynamically during execution.
     */
    private fun registerBuiltInTransformations() {
        // renaming
        TransformationRegistry.register(AddCommentTransformation.ID) { config -> AddCommentTransformation(config) }
        TransformationRegistry.register(RenameMethodTransformation.ID) { config -> RenameMethodTransformation(config) }
        TransformationRegistry.register(RenameClassTransformation.ID) { config -> RenameClassTransformation(config) }
        TransformationRegistry.register(RenameVariableTransformation.ID) { config -> RenameVariableTransformation(config) }

        // structural
        // move file transformation:
        // 1) with AI suggested directory
        TransformationRegistry.register(MoveFileIntoSuggestedDirectoryTransformation.Companion.AI.ID) { config ->
            MoveFileIntoSuggestedDirectoryTransformation.withAI(config, token = System.getenv("GRAZIE_TOKEN"))
        }
        // 2) with a config-defined suggested directory
        TransformationRegistry.register(MoveFileIntoSuggestedDirectoryTransformation.Companion.Config.ID) { config ->
            MoveFileIntoSuggestedDirectoryTransformation.withConfig(config)
        }
        // reorder class methods transformation
        TransformationRegistry.register(ReorderClassMethodsTransformation.ID) { config ->
            ReorderClassMethodsTransformation(config)
        }
    }

    /**
     * Resolves transformation ids from YAML to concrete Transformation instances via the registry.
     * - Preserves the original order from the config.
     * - Enforces uniqueness: throws on duplicate ids.
     * - Throws on unknown ids and lists known ids to help configuration.
     */
    private fun mapToTransformations(config: CodeCocoonConfig): List<Transformation> {
        if (config.transformations.isEmpty()) {
            return emptyList()
        }

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
