package com.github.pderakhshanfar.codecocoonplugin.memory

import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages persistent storage of rename operations to enable deterministic transformations.
 *
 * Memory files are stored in the provided memory directory and are organized by project name
 * to allow tracking multiple projects independently.
 *
 * @param projectName The name of the project (used for the memory filename)
 * @param memoryDirPath The directory path where memory files should be stored
 */
class RenameMemory(private val projectName: String, memoryDirPath: String) {

    private val logger = thisLogger().withStdout()

    private val memoryFile: File
    private var memoryData: MemoryState

    init {
        // Sanitize project name for use in filename
        val sanitizedName = sanitizeProjectName(projectName)

        // Convert path to File and ensure memory directory exists
        val memoryDir = File(memoryDirPath)
        if (!memoryDir.exists()) {
            memoryDir.mkdirs()
        }

        memoryFile = File(memoryDir, "$sanitizedName.json")
        memoryData = loadFromDisk()
    }

    /**
     * Retrieves the stored name for a given element signature.
     *
     * @param signature The unique signature of the element
     * @return The stored new name, or null if not found in memory
     */
    fun get(signature: String): String? {
        if (signature.isBlank()) {
            logger.warn("Attempted to get empty signature from memory")
            return null
        }
        return memoryData.entries[signature]
    }

    /**
     * Stores a successful rename operation in memory.
     *
     * @param signature The unique signature of the element
     * @param newName The new name that was successfully applied
     */
    fun put(signature: String, newName: String) {
        if (signature.isBlank()) {
            logger.warn("Attempted to store empty signature in memory")
            return
        }
        if (newName.isBlank()) {
            logger.warn("Attempted to store empty name for signature: $signature")
            return
        }

        memoryData.entries[signature] = newName
    }

    /**
     * Persists the current memory state to disk.
     */
    fun save() {
        val jsonString = json.encodeToString(memoryData)
        memoryFile.writeText(jsonString)
        logger.info("  ↳ Successfully saved rename memory for project '$projectName' (${memoryData.entries.size} entries)")
    }

    /**
     * Loads memory data from disk, or creates a new empty memory if the file doesn't exist.
     * Throws on JSON parse errors or project name mismatches.
     */
    private fun loadFromDisk(): MemoryState {
        if (!memoryFile.exists()) {
            logger.info("  • No existing memory file found for project '$projectName', creating new memory")
            return MemoryState(projectName, mutableMapOf())
        }

        val jsonString = memoryFile.readText()
        val loaded = json.decodeFromString<MemoryState>(jsonString)

        // Verify project name matches
        if (loaded.projectName != projectName) {
            throw IllegalStateException(
                "Memory file project name mismatch: expected '$projectName', found '${loaded.projectName}'. " +
                "Memory file: ${memoryFile.absolutePath}"
            )
        }

        return loaded
    }

    /**
     * Sanitizes a project name to be safe for use in a filename.
     * Throws if the project name is blank or becomes blank after sanitization.
     */
    private fun sanitizeProjectName(name: String): String {
        val sanitized = name
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(100) // Limit length to avoid filesystem issues

        if (sanitized.isBlank()) {
            throw IllegalArgumentException(
                "Project name '$name' contains only invalid characters or is blank."
            )
        }

        return sanitized
    }

    /**
     * Returns the number of entries currently in memory.
     */
    fun size(): Int = memoryData.entries.size

    /**
     * Returns the path to the memory file.
     */
    fun getMemoryFilePath(): String = memoryFile.absolutePath

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}

/**
 * Data class representing the persistent memory file structure.
 *
 * @property projectName The name of the project this memory belongs to
 * @property entries Map from element signature to new name
 */
@Serializable
private data class MemoryState (
    val projectName: String,
    val entries: MutableMap<String, String>
)
