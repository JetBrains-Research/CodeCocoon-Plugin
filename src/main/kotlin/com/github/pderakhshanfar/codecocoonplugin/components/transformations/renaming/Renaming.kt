package com.github.pderakhshanfar.codecocoonplugin.components.transformations.renaming

data class RenameSuggestions<T>(
    val suggestions: Map<T, List<String>>,
)