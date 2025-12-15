package com.github.pderakhshanfar.codecocoonplugin.components.transformations

import com.github.pderakhshanfar.codecocoonplugin.common.Language
import com.github.pderakhshanfar.codecocoonplugin.executor.TransformationResult
import com.github.pderakhshanfar.codecocoonplugin.intellij.psi.document
import com.github.pderakhshanfar.codecocoonplugin.transformation.TextBasedTransformation
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * Adds a comment at the beginning of a file.
 * Works with Java and Kotlin.
 *
 * **NOTE**: _serves as a mere example of how to implement a custom transformation.
 * Note intended to be used in production_.
 */
class AddCommentTransformation(
    override val config: Map<String, Any>
) : TextBasedTransformation(), IntelliJAwareTransformation {
    override val id: String = ID
    override val name: String = id
    override val description: String = "Adds a comment at the beginning of the file"

    override val supportedLanguages: Set<Language> = setOf(
        Language.JAVA,
        Language.KOTLIN,
    )

    private val message: String = config.let {
        val param = "message"
        if (!(param in it && it[param] is String)) {
            throw IllegalArgumentException("Missing required config parameter '$param' of type string")
        }
        it[param] as String
    }

    override fun apply(
        psiFile: PsiFile,
        virtualFile: VirtualFile,
    ): TransformationResult {
        val result = try {
            val comment = createComment(psiFile.name, message)
            val document = psiFile.document()

            val value = if (document != null) {
                // Add comment at the beginning
                document.insertString(0, "$comment\n\n")
                TransformationResult.Success(
                    message = "Added comment to ${virtualFile.name}",
                    filesModified = 1
                )
            } else {
                TransformationResult.Failure(
                    "Could not get document for file ${psiFile.name}")
            }
            value
        } catch (e: Exception) {
            TransformationResult.Failure("Failed to add comment for file ${psiFile.name}", e)
        }

        return result
    }

    /**
     * @param filename The filename of the file to add a comment to
     * @param message The comment message **without** any language-specific comment prefix (e.g., "//" for Java)
     */
    private fun createComment(filename: String, message: String): String? {
        val language = filename.let {
            when {
                it.endsWith(".java") -> Language.JAVA
                it.endsWith(".kt") || it.endsWith(".kts") -> Language.KOTLIN
                // defaults to Java-style comments
                else -> Language.JAVA
            }
        }

        return when (language) {
            Language.JAVA, Language.KOTLIN -> "// $message"
            else -> ""
        }
    }

    companion object {
        const val ID = "add-comment-transformation"
    }
}