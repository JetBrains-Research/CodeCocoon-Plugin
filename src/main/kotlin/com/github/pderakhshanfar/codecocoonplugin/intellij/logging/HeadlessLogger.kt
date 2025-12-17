package com.github.pderakhshanfar.codecocoonplugin.intellij.logging

import com.intellij.openapi.diagnostic.Logger

/**
 * Uses IntelliJ Platform's [Logger] to log messages
 * and duplicates messages to **stdout** / **stderr** for ease of
 * debugging and development in headless mode.
 */
internal class HeadlessLogger(private val logger: Logger) {
    fun info(message: String) {
        logger.info(message)
        println(message)
    }

    fun warn(message: String) {
        logger.warn(message)
        System.err.println("WARN: $message")
    }

    fun error(message: String, throwable: Throwable? = null) {
        logger.error(message, throwable)
        System.err.println("ERROR: $message")
        throwable?.printStackTrace(System.err)
    }
}

/**
 * Wraps the current [Logger] instance with a [HeadlessLogger],
 * enabling the duplication of logged messages to stdout and stderr.
 *
 * Useful for improving visibility of log messages during
 * development and debugging in headless environments.
 *
 * @return A [HeadlessLogger] instance that wraps the current [Logger].
 */
internal fun Logger.withStdout() = HeadlessLogger(this)