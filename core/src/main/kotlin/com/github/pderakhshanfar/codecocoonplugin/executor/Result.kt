package com.github.pderakhshanfar.codecocoonplugin.executor

/**
 * Represents the result of applying a transformation.
 */
sealed class TransformationResult {
    data class Success(
        val message: String,
        val filesModified: Int = 1
    ) : TransformationResult()

    data class Failure(
        val error: String,
        val exception: Throwable? = null
    ) : TransformationResult()

    data class Skipped(
        val reason: String
    ) : TransformationResult()
}
