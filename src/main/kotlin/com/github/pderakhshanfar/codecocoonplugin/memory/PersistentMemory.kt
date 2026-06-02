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
 * Stores key-value pairs as JSON in a single file at the path provided to the constructor.
 * The caller decides exactly where the memory file lives — there is no project-name
 * composition or per-project file partitioning.
 *
 * **Thread Safety:** This implementation is not thread-safe. Use external synchronization
 * if accessing from multiple threads.
 *
 * **Usage:**
 * ```kotlin
 * PersistentMemory("/path/to/memory.json").use { memory ->
 *     memory.put("key", "value")
 *     memory.get("key") // returns "value"
 * } // automatically saves on close
 * ```
 *
 * @param memoryFilepath Full path to the JSON memory file (created if missing)
 */
class PersistentMemory(private val memoryFilepath: String) : Memory<String, String> {

    private val logger = thisLogger().withStdout()

    private val memoryFile: Path = run {
        val path = Path(memoryFilepath)
        path.parent?.createDirectories()
        path
    }

    private var state: MemoryState = loadFromDisk(from = memoryFile)

    override fun get(key: String): String? {
        if (key.isBlank()) {
            logger.warn("Attempted to get empty key from memory")
            return null
        }
        return state.entries[key]
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

        return state.entries.put(key, value)
    }

    override fun save() {
        val jsonString = json.encodeToString(state)
        memoryFile.writeText(jsonString)
        logger.info("  ↳ Successfully saved memory to '$memoryFilepath' (${state.entries.size} entries)")
    }

    override fun size(): Int = state.entries.size

    /**
     * Loads memory data from disk, or creates a new empty memory if the file doesn't exist.
     * Throws on JSON parse errors.
     *
     * @param from The path to the memory file to load from
     */
    private fun loadFromDisk(from: Path): MemoryState {
        if (!from.exists()) {
            logger.info("  • No existing memory file at '$memoryFilepath', creating new memory")
            return MemoryState(mutableMapOf())
        }

        val jsonString = from.readText()
        return json.decodeFromString<MemoryState>(jsonString)
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
 * @property entries Map from key to value
 */
@Serializable
private data class MemoryState(
    val entries: MutableMap<String, String>
)
