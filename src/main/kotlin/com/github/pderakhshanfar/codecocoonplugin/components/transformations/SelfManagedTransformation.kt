package com.github.pderakhshanfar.codecocoonplugin.components.transformations

/**
 * Marker interface for transformations that manage their own write actions and commands.
 *
 * Transformations implementing this interface should not be wrapped in a writeCommandAction
 * by the IntelliJTransformationExecutor, as they handle their own command/write action context.
 *
 * This is used for transformations that use IntelliJ Platform's refactoring processors (like RenameProcessor),
 * which manage their own write actions internally.
 */
interface SelfManagedTransformation : IntelliJAwareTransformation
