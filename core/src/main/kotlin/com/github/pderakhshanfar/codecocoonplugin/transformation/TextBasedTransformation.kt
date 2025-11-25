package com.github.pderakhshanfar.codecocoonplugin.transformation

import com.github.pderakhshanfar.codecocoonplugin.common.FileContext
import com.github.pderakhshanfar.codecocoonplugin.common.Language

/**
 * Base class for simple text-based transformations that work across all languages.
 */
abstract class TextBasedTransformation : Transformation {
    /**
     * Languages this transformation supports.
     * Default is [Language.entries].
     */
    open val supportedLanguages: Set<Language> = Language.entries.toSet()

    override fun accepts(context: FileContext): Boolean {
        return context.language in supportedLanguages
    }
}