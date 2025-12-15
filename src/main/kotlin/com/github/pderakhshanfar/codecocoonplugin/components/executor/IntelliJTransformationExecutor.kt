package com.github.pderakhshanfar.codecocoonplugin.components.executor

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.components.transformations.IntelliJAwareTransformation
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationExecutor
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.psiFile
import com.github.pderakhshanfar.codecocoonplugin.intellij.vfs.findVirtualFile
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation
import com.intellij.openapi.application.ReadResult
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
        context: FileContext
    ): TransformationResult {
        return try {
            when (transformation) {
                is IntelliJAwareTransformation -> executeIntelliJTransformation(transformation, context)
                else -> TransformationResult.Failure("Transformation ${transformation.name} must implement `IntelliJAwareTransformation`")
            }
        } catch (err: Exception) {
            TransformationResult.Failure(
                "Failed to execute transformation ${transformation.name} on ${context.language} file ${context.relativePath}", err)
        }
    }

    private suspend fun executeIntelliJTransformation(
        transformation: IntelliJAwareTransformation,
        context: FileContext,
    ): TransformationResult {
        val virtualFile = project.findVirtualFile(context)
            ?: return TransformationResult.Failure(
                "Project '${project.name}' doesn't contain file: ${context.relativePath}")

        return readAndWriteAction {
            val psiFile = project.psiFile(virtualFile)
                ?: return@readAndWriteAction ReadResult.value(
                    TransformationResult.Failure("Cannot get PSI for file: ${context.relativePath}")
                )

            writeCommandAction(project, transformation.name) {
                transformation.apply(psiFile, virtualFile)
            }
        }
    }
}