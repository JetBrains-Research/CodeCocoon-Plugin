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
 * - Extracting useMemory flag from config
 * - Creating and initializing RenameMemory instances
 * - Determining whether to use LLM or memory mode
 *
 * **Subclasses should:**
 * - Call [getOrCreateMemory] in their apply() method to initialize memory
 * - Use [useMemory] to determine whether to read from memory or generate new names
 */
abstract class MemoryAwareTransformation(
    override val config: Map<String, Any>
) : SelfManagedTransformation() {

    /**
     * Whether memory mode is enabled for this transformation.
     * When true: only uses cached names from memory.
     * When false: generates names via LLM and stores them in memory.
     */
    protected val useMemory: Boolean = config["useMemory"] as? Boolean ?: false

    private val logger = thisLogger().withStdout()

    /**
     * Cached memory instance, initialized once per transformation.
     */
    private var cachedMemory: RenameMemory? = null

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

            RenameMemory(projectName).also { memory ->
                if (useMemory) {
                    logger.info("  ↳ Memory-based renaming enabled (read mode). Memory file: ${memory.getMemoryFilePath()}")
                    logger.info("    Loaded ${memory.size()} existing rename entries from memory")
                } else {
                    logger.info("  ↳ LLM-based renaming enabled (write mode). Memory file: ${memory.getMemoryFilePath()}")
                    logger.info("    Loaded ${memory.size()} existing rename entries. New renames will be saved.")
                }
            }
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
     * @param signature The signature of the element before it was renamed
     * @param newName The new name that was successfully applied
     */
    protected fun storeRenameInMemory(signature: String, newName: String) {
        if (cachedMemory != null) {
            if (!useMemory) {
                cachedMemory!!.put(signature, newName)
                logger.info("      ✓ Stored rename in memory: `$signature` -> `$newName`")
            }
        } else {
            logger.warn("      ✗ Memory is null. Could not store rename for: `\$signature` -> `\$newName`\"")
        }
    }
}
