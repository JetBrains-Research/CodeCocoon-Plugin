package com.github.pderakhshanfar.codecocoonplugin.suggestions

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import com.github.pderakhshanfar.codecocoonplugin.suggestions.impl.suggestNewDirectoryImpl

/**
 * Facade for the suggestion APIs
 */
object SuggestionsApi {
    /**
     * Suggests potential new directory locations for a given file based on its purpose and content via LLMs.
     *
     * The suggestion is made using a language model that analyzes the project's structure, the file's characteristics,
     * and existing directory constraints.
     *
     * @param token API key or token used for authentication with the language model service.
     * @param model The language model to be used for generating directory suggestions.
     * @param projectRoot The root directory of the project; used as context for analyzing the file's organization.
     * @param filepath The current file path of the target file for which suggestions are being generated.
     * @param content A lambda function providing the content of the file to be analyzed (the content may be truncated).
     * @param existingOnly A flag to indicate whether suggestions should be restricted to existing directories only.
     *
     * @see suggestNewDirectoryImpl
     */
    suspend fun suggestNewDirectory(
        token: String,
        model: LLModel = OpenAIModels.Chat.GPT4o,
        projectRoot: String,
        filepath: String,
        content: () -> String,
        existingOnly: Boolean = false
    ): Result<List<String>> = suggestNewDirectoryImpl(
        token = token,
        model = model,
        projectRoot = projectRoot,
        filepath = filepath,
        content = content,
        existingOnly = existingOnly,
    )
}