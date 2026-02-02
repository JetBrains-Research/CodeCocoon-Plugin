package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.github.pderakhshanfar.codecocoonplugin.suggestions.SuggestionsApi
import com.github.pderakhshanfar.codecocoonplugin.transformation.requireOrDefault
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async


/**
 * A transformation that moves a given file to a directory suggested by AI.
 *
 * This transformation analyzes the context of the provided file and relocates it to an AI-recommended
 * directory based on its content, usage, or other metadata. The target directory is determined dynamically based
 * on AI algorithms, making project organization more intuitive and efficient.
 *
 * Expected config schema:
 * ```yaml
 * # TODO: any other params?
 * config:
 *   existingOnly: boolean (default: false) # optional, whether to suggestion yet non-existent directories
 * ```
 *
 * @property config Configuration parameters required for the transformation.
 * @constructor Initializes the transformation with the provided configuration map.
 */
@OptIn(DelicateCoroutinesApi::class)
class MoveFileToAiSuggestedDirectoryTransformation(
    override val config: Map<String, Any>,
) : IntelliJAwareTransformation {
    override val id = ID
    override val description = "Places the given file into a directory suggested by AI"

    private val existingOnly = config.requireOrDefault("existingOnly", false)

    private val logger = thisLogger().withStdout()

    override fun accepts(context: FileContext): Boolean = context.language == Language.JAVA

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile
    ): TransformationResult {
        val project = psiFile.project
        val requestor = this

        // TODO: redo (or delete?) MoveFileToNewDirectoryTransformation
        // 1. request new directory suggestion
        // 1. prepare a new package for the moved file
        // 1. move the file into the new location
        // 1. update the package of the moved file
        // 1. import resolution:
        //   - in the moved file: import components from the previous package that were accessed without imports
        //   - for every file that used components from the moved file: update the import statements accordingly

        // TODO: is it legit? make apply a suspend function?
        val suggestedDirectories = GlobalScope.async {
            SuggestionsApi.suggestNewDirectory(
                token = System.getenv("OPENAI_API_KEY"),
                projectRoot = project.basePath!!,
                filepath = virtualFile.path,
                content = {
                    // TODO: if text is too big output only public symbol declarations, not full text
                    psiFile.text
                },
                existingOnly = existingOnly
            )
        }.getCompleted()

        TODO("impl")
    }

    companion object {
        const val ID = "move-file-to-ai-suggested-directory-transformation"
    }
}