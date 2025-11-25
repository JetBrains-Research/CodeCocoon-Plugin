package com.github.pderakhshanfar.codecocoonplugin.common

/**
 * Supported programming languages
 */
enum class Language {
    JAVA,
    KOTLIN,
    UNKNOWN;

    companion object {
        /**
         * Determines the programming language based on the given file extension.
         *
         * @param extension the file extension, which may include or exclude the leading dot (e.g., "java", ".kt").
         * @return the corresponding [Language] based on the extension, or [Language.UNKNOWN] if the extension is unrecognized.
         */
        fun fromExtension(extension: String): Language = extension.removeSuffix(".").let {
            when (extension.lowercase()) {
                "java" -> JAVA
                "kt", "kts" -> KOTLIN
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Platform-agnostic representation of a file context.
 * Contains minimal information needed to determine if a transformation applies
 * to this file.
 *
 * Serves as a file descriptor.
 */
data class FileContext(
    val relativePath: String,
    val extension: String,
    val language: Language,
)
