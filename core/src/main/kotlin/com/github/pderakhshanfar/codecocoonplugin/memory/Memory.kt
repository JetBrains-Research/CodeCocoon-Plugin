package com.github.pderakhshanfar.codecocoonplugin.memory

/**
 * Generic persistent storage interface for key-value pairs.
 *
 * Implements [AutoCloseable] to ensure automatic persistence on resource cleanup.
 * Use with `.use {}` blocks to guarantee data is saved:
 *
 * ```kotlin
 * PersistentMemory(projectName, memoryDir).use { memory ->
 *     memory.put("key", "value")
 *     // memory.save() called automatically on close
 * }
 * ```
 *
 * @param K Key type
 * @param V Value type
 */
interface Memory<K, V> : AutoCloseable {

    /**
     * Retrieves the value associated with the given key, or null if not found.
     */
    fun get(key: K): V?

    /**
     * Puts a new key-value pair into the memory, returning the previous value if it existed.
     */
    fun put(key: K, value: V): V?

    fun size(): Int

    fun save()

    override fun close() {
        save()
    }
}
