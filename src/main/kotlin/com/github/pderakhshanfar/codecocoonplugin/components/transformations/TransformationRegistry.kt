package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation

/**
 * Owns the mapping from transformation IDs (from YAML) to
 * [factories] that create concrete [Transformation] instances.
 *
 * When real implementations are available, replace the placeholder [factories]
 * below with the actual classes (see commented lines near each registration).
 */
object TransformationRegistry {
    private val factories = mutableMapOf<String, (config: Map<String, Any>) -> Transformation>()

    @Synchronized
    fun register(id: String, factory: (config: Map<String, Any>) -> Transformation) {
        factories[id] = factory
    }

    @Synchronized
    fun create(id: String, config: Map<String, Any>): Transformation? = factories[id]?.invoke(config)

    @Synchronized
    fun knownIds(): Set<String> = factories.keys.toSet()
}