package com.github.pderakhshanfar.codecocoonplugin.transformation

/**
 * TransformationRegistry owns the mapping from transformation IDs (from YAML) to
 * factories that create concrete [Transformation] instances.
 *
 * Built-in transformation registrations are performed here during object init,
 * so there is no separate BuiltInTransformations object anymore.
 *
 * When real implementations are available, replace the placeholder factories
 * below with the actual classes (see commented lines near each registration).
 */
object TransformationRegistry {
    private val factories = mutableMapOf<String, (config: Map<String, Any?>) -> Transformation>()

    // TODO add transformations here

    // Register built-in transformations
    init {
       // register("dummyTransformation1") {
       //     PlaceholderTransformation(id = "placeholder", config = it)
       // }
    }

    @Synchronized
    fun register(id: String, factory: (config: Map<String, Any?>) -> Transformation) {
        factories[id] = factory
    }

    @Synchronized
    fun create(id: String, config: Map<String, Any?>): Transformation? = factories[id]?.invoke(config)

    @Synchronized
    fun knownIds(): Set<String> = factories.keys.toSet()
}
