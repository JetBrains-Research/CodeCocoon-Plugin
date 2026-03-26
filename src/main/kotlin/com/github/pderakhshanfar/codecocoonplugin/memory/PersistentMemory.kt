package com.github.pderakhshanfar.codecocoonplugin.memory

import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.*

/**
 * File-based persistent storage implementation of [Memory] interface.
 *
 * Stores key-value pairs as JSON in a file within the specified directory.
 * Files are organized by project name to allow tracking multiple projects independently.
 *
 * **Thread Safety:** This implementation is not thread-safe. Use external synchronization
 * if accessing from multiple threads.
 *
 * **Usage:**
 * ```kotlin
 * PersistentMemory("myProject", "/path/to/memory").use { memory ->
 *     memory.put("key", "value")
 *     memory.get("key") // returns "value"
 * } // automatically saves on close
 * ```
 *
 * @param projectName The name of the project (used for the memory filename)
 * @param memoryDirPath The directory path where memory files should be stored
 */
class PersistentMemory(private val projectName: String, memoryDirPath: String) : Memory<String, String> {

    private val logger = thisLogger().withStdout()

    private val memoryFile: Path
    private var memoryData: MemoryState

    init {
        // Sanitize project name for use in filename
        val sanitizedName = sanitizeProjectName(projectName)

        // Convert path to Path and ensure memory directory exists
        val memoryDir = Path(memoryDirPath)
        memoryDir.createDirectories()

        memoryFile = memoryDir.resolve("$sanitizedName.json")
        memoryData = loadFromDisk()
    }

    override fun get(key: String): String? {
        if (key.isBlank()) {
            logger.warn("Attempted to get empty key from memory")
            return null
        }
        return memoryData.entries[key]
    }

    override fun put(key: String, value: String): String? {
        if (key.isBlank()) {
            logger.warn("Attempted to store empty key in memory")
            return null
        }
        if (value.isBlank()) {
            logger.warn("Attempted to store empty value for key: $key")
            return null
        }

        return memoryData.entries.put(key, value)
    }

    override fun save() {
        val jsonString = json.encodeToString(memoryData)
        memoryFile.writeText(jsonString)
        logger.info("  ↳ Successfully saved memory for project '$projectName' (${memoryData.entries.size} entries)")
    }

    override fun size(): Int = memoryData.entries.size

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
                "Memory file: ${memoryFile.absolutePathString()}"
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
 * @property entries Map from key to value
 */
@Serializable
private data class MemoryState(
    val projectName: String,
    val entries: MutableMap<String, String>
)
