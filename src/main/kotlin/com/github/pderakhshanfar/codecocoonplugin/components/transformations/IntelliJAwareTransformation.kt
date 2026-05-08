package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.memory.Memory
import com.github.pderakhshanfar.codecocoonplugin.transformation.Transformation
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import kotlinx.coroutines.runBlocking

/**
 * Interface for transformations that need access to IntelliJ Platform components (e.g., PSI, VFS, etc.).
 */
interface IntelliJAwareTransformation : Transformation {
    /**
     * Indicates whether this transformation manages its own write actions and commands.
     *
     * @return true if the transformation handles its own command/write action context, false otherwise
     */
    fun selfManaged(): Boolean = false

    /**
     * Apply the transformation using IntelliJ PSI.
     *
     * @param psiFile The PSI representation of the file
     * @param virtualFile The virtual file being transformed
     * @param memory Optional persistent memory for storing transformation state
     * @return Result of the transformation
     */
    fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile,
        memory: Memory<String, String>? = null
    ): TransformationResult

    companion object {
        /**
         * Helper function to reduce verbosity of PSI read access calls.
         * Wraps runBlocking { readAction { } } into a simpler API.
         *
         * Usage: `val data = IntelliJAwareTransformation.withReadAction { psiElement.name }`
         */
        inline fun <T> withReadAction(crossinline block: () -> T): T =
            runBlocking { readAction { block() } }

        /**
         * Smart-mode equivalent of [withReadAction]: suspends until the IntelliJ
         * indices are ready (i.e., not in dumb mode) before running [block]. Use
         * this anywhere [block] reaches into the stub / file-based index — e.g.
         * `psiClass.allFields`, `psiClass.supers`, `method.findSuperMethods()`,
         * `JavaPsiFacade.findClass(...)`, `ReferencesSearch.search(...)`.
         *
         * Background: writes from earlier files in the pipeline (rename commits
         * + document save) drop the IDE back into dumb mode while the index is
         * recomputed. Plain [withReadAction] holds a read lock but does not wait
         * for smart mode, so any index touch in that window throws
         * [com.intellij.openapi.project.IndexNotReadyException].
         */
        inline fun <T> withSmartReadAction(project: Project, crossinline block: () -> T): T =
            runBlocking { smartReadAction(project) { block() } }
    }
}