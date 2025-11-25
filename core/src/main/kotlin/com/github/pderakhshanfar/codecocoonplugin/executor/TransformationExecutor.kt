package com.github.pderakhshanfar.codecocoonplugin.executor

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation

/**
 * Platform-agnostic interface for applying transformations.
 * The actual implementation will be platform-specific (IntelliJ PSI, etc.)
 */
interface TransformationExecutor {
    /**
     * Applies a transformation to a single file.
     *
     * @param transformation The transformation to apply
     * @param context The file context
     * @return Result of the transformation
     */
    suspend fun execute(
        transformation: Transformation,
        context: FileContext,
    ): TransformationResult
}