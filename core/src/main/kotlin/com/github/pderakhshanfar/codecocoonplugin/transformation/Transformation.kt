package com.github.pderakhshanfar.codecocoonplugin.transformation

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationExecutor


/**
 * Represents a language-agnostic transformation that can be applied to files.
 * This is the base interface for all transformations.
 *
 * To execute a given transformation, use a [TransformationExecutor].
 *
 * @see TransformationExecutor
 */
interface Transformation {
    // TODO: add ID

    /**
     * Human-readable name for this transformation.
     */
    val name: String

    /**
     * Description of what this transformation does.
     */
    val description: String

    /**
     * Determines if this transformation can be applied to the given file context.
     *
     * @param context The file context to check
     * @return `true` if this transformation supports the file, `false` otherwise
     */
    fun accepts(context: FileContext): Boolean
}

