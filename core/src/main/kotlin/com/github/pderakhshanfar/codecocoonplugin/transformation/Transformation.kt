package com.github.pderakhshanfar.codecocoonplugin.transformation

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext

/**
 * Represents a language-agnostic transformation that can be applied to files.
 * This is the base interface for all transformations.
 *
 * Each transformation is identified by a unique short `id` (coming from YAML)
 * and holds its own free-form `config` which the implementation may parse lazily
 * when executing.
 */
interface Transformation {
    /** Unique identifier of the transformation type (e.g., "addComment"). */
    val id: String

    /**
     * Human-readable name for this transformation.
     */
    val name: String

    /**
     * Description of what this transformation does.
     */
    val description: String

    /** Free-form configuration as provided from YAML for this instance. */
    val config: Map<String, Any>

    /**
     * Determines if this transformation can be applied to the given file context.
     *
     * @param context The file context to check
     * @return `true` if this transformation supports the file, `false` otherwise
     */
    fun accepts(context: FileContext): Boolean
}

