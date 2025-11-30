package com.github.pderakhshanfar.codecocoonplugin.config

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream

/**
 * Ultra-simple ConfigLoader for the prototype.
 * Reads a single, explicit file path from JVM property `codecocoon.config`.
 * This property is set by the Gradle headless task invoked by run.sh.
 */
object ConfigLoader {

    fun load(): CodeCocoonConfig {
        val path = System.getProperty("codecocoon.config")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "Missing system property -Dcodecocoon.config.\n" +
                "Run via ./run.sh (it sets this automatically) and keep codecocoon.yml in the root of CodeCocoon-Plugin."
            )

        val file = File(path)
        if (!file.exists()) {
            throw IllegalStateException("Config file not found at ${path}.\n" +
                    "Ensure codecocoon.yml is in the root of CodeCocoon-Plugin.")
        }
        return parseYaml(file.inputStream())
    }

    private fun parseYaml(stream: InputStream): CodeCocoonConfig {
        stream.use { input ->
            val yaml = Yaml()

            @Suppress("UNCHECKED_CAST")
            val root = yaml.load<Any?>(input) as? Map<String, Any?> ?: emptyMap()

            val projectRoot = (root["projectRoot"])?.toString()
            val files = (root["files"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

            val transformationsRaw = (root["transformations"] as? List<*>) ?: emptyList<Any?>()
            val transformations = transformationsRaw.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = map["id"]?.toString() ?: return@mapNotNull null

                @Suppress("UNCHECKED_CAST")
                val cfg = (map["config"] as? Map<String, Any?>) ?: emptyMap()
                TransformationConfig(id = id, config = cfg)
            }

            return CodeCocoonConfig(
                projectRoot = projectRoot,
                files = files,
                transformations = transformations,
            )
        }
    }
}