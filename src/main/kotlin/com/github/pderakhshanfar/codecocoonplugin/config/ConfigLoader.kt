package com.github.pderakhshanfar.codecocoonplugin.config

import com.github.pderakhshanfar.codecocoonplugin.intellij.vfs.refreshAndFindVirtualFile
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
            val root = yaml.load<Any?>(input) as Map<String, Any>

            val projectRoot = (root["projectRoot"])?.toString()
            val files = (root["files"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

            val transformationsRaw = (root["transformations"] as? List<*>) ?: emptyList<Any?>()
            val transformations = transformationsRaw.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = map["id"]?.toString() ?: return@mapNotNull null

                @Suppress("UNCHECKED_CAST")
                val cfg = (map["config"] as? Map<String, Any>) ?: emptyMap()
                TransformationConfig(id = id, config = cfg)
            }

            // Resolve memory file path
            val memoryFilepath = resolveMemoryFilepath(root["memoryFilepath"]?.toString())

            // if projectRoot is present, try to search for the corresponding virtual file
            val projectRootFile = projectRoot?.refreshAndFindVirtualFile()

            return CodeCocoonConfig(
                projectRoot = projectRoot,
                projectRootFile = projectRootFile,
                files = files,
                transformations = transformations,
                memoryFilepath = memoryFilepath,
            )
        }
    }

    /**
     * Resolves the memory JSON file path to an absolute path string.
     *
     * If [memoryFilepath] is provided:
     * - If absolute: use as-is
     * - If relative: resolve relative to config file's parent directory
     *
     * If [memoryFilepath] is null:
     * - Default to ".codecocoon-memory.json" in config file's parent directory
     *
     * @param memoryFilepath Optional memory file path from YAML
     * @return Resolved absolute path to the memory JSON file
     */
    private fun resolveMemoryFilepath(memoryFilepath: String?): String {
        val configPath = System.getProperty("codecocoon.config")
            ?: throw IllegalStateException("codecocoon.config system property not set")

        val configFile = File(configPath)
        val configParentDir = configFile.parentFile
            ?: throw IllegalStateException("Config file has no parent directory: $configPath")

        return if (memoryFilepath != null) {
            val memoryFile = File(memoryFilepath)
            if (memoryFile.isAbsolute) {
                memoryFile.canonicalPath
            } else {
                File(configParentDir, memoryFilepath).canonicalPath
            }
        } else {
            // Default to .codecocoon-memory.json in config parent directory
            val memoryDir = File(configParentDir, ".codecocoon-memory")
            File(memoryDir, "memory.json").canonicalPath
        }
    }
}