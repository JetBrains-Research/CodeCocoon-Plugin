package com.github.pderakhshanfar.codecocoonplugin.services

/**
 * A pair of related prose fields that should be transformed together by a single LLM
 * call so the result is internally coherent (consistent voice, consistent identifier
 * rewrites). Used for the main `{title, body}` of a benchmark record and for each
 * `resolved_issues[i].{title, body}` pair.
 */
data class TextBlock(
    val title: String,
    val body: String,
)
