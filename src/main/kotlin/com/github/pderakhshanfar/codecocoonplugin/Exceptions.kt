package com.github.pderakhshanfar.codecocoonplugin


// TODO: add to core
/**
 * Base class exception for all custom exceptions.
 */
sealed class CodeCocoonException(message: String, cause: Throwable? = null) : Exception(message, cause)


class ProjectConfiguratorFailed(
    message: String,
    cause: Throwable? = null
) : CodeCocoonException(message, cause)