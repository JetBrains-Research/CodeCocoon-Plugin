package com.github.pderakhshanfar.codecocoonplugin.transformation

import kotlin.collections.contains


/**
 * Retrieves and casts a value from the map associated with the given key.
 * If the key does not exist or the value cannot be cast to the specified type `T`,
 * an exception is thrown.
 *
 * @param key The key to retrieve the value for.
 * @return The value associated with the key, cast to the specified type `T`.
 * @throws IllegalArgumentException if the key is not found in the map, or the value
 *         cannot be cast to the type `T`.
 */
inline fun <reified T> Map<String, Any>.require(key: String): T {
    val config = this
    if (key !in config || config[key] == null) {
        throw IllegalArgumentException("Missing required config parameter '$key' of type ${T::class.simpleName}")
    } else if (config[key] !is T) {
        throw IllegalArgumentException(
            "Expected config parameter '$key' to be of type ${T::class.simpleName}")
    }
    return config[key] as T
}

/**
 * Same as [require] but returns a default value if the key is not found in the map.
 */
inline fun <reified T> Map<String, Any>.requireOrDefault(key: String, defaultValue: T): T {
    val config = this
    if (key !in config || config[key] == null) {
        return defaultValue
    } else if (config[key] !is T) {
        throw IllegalArgumentException(
            "Expected config parameter '$key' to be of type ${T::class.simpleName}")
    }
    return config[key] as T
}
