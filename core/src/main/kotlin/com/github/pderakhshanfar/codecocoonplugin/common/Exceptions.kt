package com.github.pderakhshanfar.codecocoonplugin.common


/**
 * Base class exception for all custom exceptions.
 */
sealed class CodeCocoonException(message: String, cause: Throwable? = null) : Exception(message, cause)


class ProjectConfiguratorFailed(
    message: String,
    cause: Throwable? = null
) : CodeCocoonException(message, cause)

class TransformationStepFailed(
    message: String,
    cause: Throwable? = null
) : CodeCocoonException(message, cause)

class VirtualFileNotFound(
    message: String,
    cause: Throwable? = null
) : CodeCocoonException(message, cause)

class ParsingException(
    message: String,
    cause: Throwable? = null
) : CodeCocoonException(message, cause)