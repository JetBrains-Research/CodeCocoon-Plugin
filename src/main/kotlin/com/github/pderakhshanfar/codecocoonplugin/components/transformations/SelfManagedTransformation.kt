package com.github.pderakhshanfar.codecocoonplugin.components.transformations

/**
 * Abstract class for transformations that manage their own write actions and commands.
 *
 * Transformations extending this class should not be wrapped in a writeCommandAction
 * by the IntelliJTransformationExecutor, as they handle their own command/write action context.
 *
 * This is used for transformations that use IntelliJ Platform's refactoring processors (like RenameProcessor),
 * which manage their own write actions internally.
 */
abstract class SelfManagedTransformation : IntelliJAwareTransformation {
    override fun selfManaged(): Boolean = true

    companion object {
        init {
            // Disable all refactoring dialogs
            System.setProperty("ide.performance.skip.refactoring.dialogs", "true")
        }
    }
}
