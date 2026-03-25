package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.memory.RenameMemory
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Abstract base class for transformations that use memory-based renaming.
 *
 * **Design Decision: Extends SelfManagedTransformation**
 *
 * This class extends [SelfManagedTransformation] rather than [IntelliJAwareTransformation] directly
 * because all memory-aware rename transformations use IntelliJ's [com.intellij.refactoring.rename.RenameProcessor],
 * which internally manages its own write actions. The `selfManaged()` flag prevents the executor
 * from wrapping these transformations in additional write actions, which would cause conflicts.
 *
 * **Inheritance Hierarchy:**
 * ```
 * IntelliJAwareTransformation (interface)
 *     ↓
 * SelfManagedTransformation (abstract class - manages own write actions)
 *     ↓
 * MemoryAwareTransformation (abstract class - adds memory system)
 *     ↓
 * RenameClassTransformation / RenameMethodTransformation / RenameVariableTransformation
 * ```
 *
 * **Provides common functionality for:**
 * - Creating and initializing RenameMemory instances
 * - Storing successful renames in memory
 *
 * **Subclasses should:**
 * - Call [getOrCreateMemory] in their apply() method to initialize memory
 * - Extract `useMemory` config locally to determine mode
 * - Implement their own memory extraction logic for read mode
 * - Generate new names via LLM for write mode
 *
 * @param config Transformation configuration from YAML (must include 'memoryDir' key)
 */
abstract class MemoryAwareTransformation(
    override val config: Map<String, Any>
) : SelfManagedTransformation() {

    private val logger = thisLogger().withStdout()

    /**
     * Cached memory instance, initialized once per transformation.
     */
    protected var cachedMemory: RenameMemory? = null

    /**
     * Creates or retrieves a RenameMemory instance for the given project.
     *
     * Memory is initialized **once per transformation** and reused across all files,
     * since the memory file is project-scoped.
     *
     * Uses the project's base path to derive a meaningful name for the memory file.
     * Falls back to project.name if basePath is not available.
     *
     * @param project The IntelliJ project
     * @return RenameMemory instance, or null if initialization fails
     */
    protected fun getOrCreateMemory(project: Project): RenameMemory? {
        // Return the cached instance if already initialized
        if (cachedMemory != null) {
            return cachedMemory
        }

        // Initialize memory for the first time
        cachedMemory = try {
            // Use the project's base path to derive a meaningful name
            val projectName = project.basePath?.let { File(it).name } ?: project.name

            // Extract memoryDir from config (injected by HeadlessModeStarter)
            val memoryDirPath = config["memoryDir"] as? String
                ?: throw IllegalStateException("memoryDir not found in config")

            val memory = RenameMemory(projectName, memoryDirPath)
            logger.info("  ↳ Rename memory initialized. Memory file: ${memory.getMemoryFilePath()}")
            logger.info("    Loaded ${memory.size()} existing rename entries from memory")
            memory
        } catch (e: Exception) {
            logger.error("Failed to initialize rename memory", e)
            null
        }

        return cachedMemory
    }

    /**
     * Stores a successful rename in memory.
     *
     * **Important**: The signature must be generated _before_ the rename operation,
     * otherwise it will contain the new name instead of the original name.
     *
     * **Note**: This should only be called when in write mode (generating new renames).
     * The caller is responsible for checking the mode before calling this method.
     *
     * @param signature The signature of the element before it was renamed
     * @param newName The new name that was successfully applied
     */
    protected fun storeRenameInMemory(signature: String, newName: String) {
        if (cachedMemory != null) {
            cachedMemory!!.put(signature, newName)
            logger.info("      ✓ Stored rename in memory: `$signature` -> `$newName`")
        } else {
            logger.warn("      ✗ Memory is null. Could not store rename for: `$signature` -> `$newName`")
        }
    }
}
