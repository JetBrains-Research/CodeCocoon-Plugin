package com.github.pderakhshanfar.codecocoonplugin.components.executor

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationExecutor
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.psiFile
import com.github.pderakhshanfar.codecocoonplugin.memory.Memory
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation
import com.intellij.openapi.application.ReadResult
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.readAndWriteAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.project.Project

/**
 * IntelliJ-specific executor that uses IntelliJ Platform components to apply transformations.
 *
 * Requires the given transformations to implement [IntelliJAwareTransformation].
 *
 * @see IntelliJAwareTransformation
 */
class IntelliJTransformationExecutor(
    private val project: Project
) : TransformationExecutor {

    override suspend fun execute(
        transformation: Transformation,
        context: FileContext,
        memory: Memory<String, String>?
    ): TransformationResult {
        return try {
            when (transformation) {
                is IntelliJAwareTransformation -> {
                    executeIntelliJTransformation(transformation, context, memory)
                }
                else -> TransformationResult.Failure("Transformation ${transformation.id} must implement `IntelliJAwareTransformation`")
            }
        } catch (err: Exception) {
            TransformationResult.Failure(
                "Failed to execute transformation ${transformation.id} on ${context.language} file ${context.relativePath}", err)
        }
    }

    private suspend fun executeIntelliJTransformation(
        transformation: IntelliJAwareTransformation,
        context: FileContext,
        memory: Memory<String, String>?,
    ): TransformationResult {
        val virtualFile = context.virtualFile
        return when {
            // Self-managed transformations handle their own write actions/commands
            transformation.selfManaged() -> {
                // Get PSI file in a read action
                val psiFile = readAction {
                    project.psiFile(virtualFile)
                } ?: return TransformationResult.Failure("Cannot get PSI for file: ${virtualFile.path}")

                // Run transformation directly - self-managed transformations handle EDT requirements internally
                transformation.apply(psiFile, virtualFile, memory)
            }
            else -> readAndWriteAction {
                // Regular transformations need to use `writeCommandAction` wrapper
                val psiFile = project.psiFile(virtualFile)
                    ?: return@readAndWriteAction ReadResult.value(
                        TransformationResult.Failure("Cannot get PSI for file: ${virtualFile.path}")
                    )

                writeCommandAction(project, transformation.id) {
                    transformation.apply(psiFile, virtualFile, memory)
                }
            }
        }
    }
}