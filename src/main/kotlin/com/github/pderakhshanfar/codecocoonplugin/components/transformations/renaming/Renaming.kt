package com.github.pderakhshanfar.codecocoonplugin.components.transformations.renaming

data class Renaming<T>(
    val suggestions: Map<T, List<String>>,
)