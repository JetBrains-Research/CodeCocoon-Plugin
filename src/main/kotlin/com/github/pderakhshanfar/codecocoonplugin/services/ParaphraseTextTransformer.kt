package com.github.pderakhshanfar.codecocoonplugin.services

import ai.koog.prompt.dsl.Prompt
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.Serializable

/**
 * Rewrites a [TextBlock] (`{title, body}` pair) so it looks surface-different while
 * preserving exact semantics. One LLM call per block so the title and body stay
 * coherent (same voice, same synonym choices). Used in the eval pipeline AFTER
 * [MetamorphicTextTransformer] has synced renames/moves.
 */
class ParaphraseTextTransformer(
    private val llm: LLM,
) {
    private val logger = thisLogger().withStdout()

    @Serializable
    private data class ParaphrasedBlock(
        val title: String,
        val body: String,
    )

    /**
     * Asks the LLM to paraphrase [block]'s title and body together. Returns [block]
     * verbatim when both fields are blank. Returns null on LLM failure.
     */
    suspend fun rewriteBlock(block: TextBlock): TextBlock? {
        if (block.title.isBlank() && block.body.isBlank()) return block

        val prompt = createRewritePrompt(block)
        logger.info("Created paraphrase prompt:\n'''$prompt\n'''")

        val result = llm.structuredRequest<ParaphrasedBlock>(
            prompt = prompt,
            maxRetries = 3,
            maxFixingAttempts = 2,
        ) ?: return null

        return TextBlock(title = result.title, body = result.body)
    }

    private fun createRewritePrompt(block: TextBlock): Prompt {
        return Prompt.build("paraphrase-text-block") {
            system {
                text("""
                    You are a technical-documentation paraphrasing assistant.

                    You will be given a block of documentation: a TITLE and a BODY that
                    describe the same change. Your task is to AGGRESSIVELY rewrite both
                    so the result looks SUBSTANTIALLY different on the surface while
                    remaining a strict semantic synonym — every requirement, fact, and
                    constraint preserved exactly. Apply the SAME rewriting voice and
                    synonym choices to title and body so they remain consistent with
                    each other.

                    Be bold with the rewrite. A near-copy of the input is a FAILURE.
                    Aim for a high-effort rewrite that a reader would not recognise as
                    the same prose at first glance, yet a domain expert would confirm
                    carries the same intent.

                    HARD CONSTRAINTS — preserve verbatim, never alter:
                    - All identifiers: class names, method names, variable names,
                      package names, file paths, command-line flags, environment
                      variables, URLs, version strings. Do NOT rename, translate, or
                      pluralise them.
                    - All code-like tokens inside `backticks` and inside fenced code
                      blocks (```...```). Do not edit, reorder, or reformat code fences
                      or their contents.
                    - All numbers, units, and concrete values (e.g. "5 retries",
                      "200 OK", "UTF-8").
                    - Markdown structural elements: headings, bullet lists, numbered
                      lists, and tables. Their COUNT and the order of their items must
                      stay the same; you may rephrase the prose inside each item, but
                      do not add, drop, merge, or split items, and do not change
                      heading levels.

                    REQUIRED SURFACE CHANGES — apply several of these, not just one:
                    1. Lexical: replace ordinary verbs, nouns, adjectives, and
                       connectors with synonyms or near-synonyms ("provides" →
                       "exposes", "responsible for" → "in charge of", "must continue
                       to" → "are still required to"). Do this for the MAJORITY of
                       non-identifier content words.
                    2. Syntactic: reshape sentences. Convert active ↔ passive voice,
                       swap subject/object framing, hoist subordinate clauses to the
                       front, turn "X does Y so that Z" into "Z requires that X does
                       Y", and similar. At least half of the sentences should differ
                       in structure from their original counterparts.
                    3. Granularity: split long sentences into shorter ones, or fuse
                       two short sentences into one — wherever it improves rhythm and
                       the meaning is preserved.
                    4. Sentence ordering WITHIN A PARAGRAPH: you may reorder sentences
                       within the same paragraph if it preserves logical flow. Do NOT
                       move sentences across paragraph boundaries or across markdown
                       sections.
                    5. Register tightening: keep tone neutral and technical; trim
                       throat-clearing phrases ("In order to" → "To") where it does
                       not change meaning.

                    FORBIDDEN:
                    - Do NOT add facts, examples, qualifications, or reasoning the
                      original did not contain.
                    - Do NOT remove facts, examples, qualifications, or reasoning the
                      original DID contain.
                    - Do NOT introduce ambiguity, hedging, or vagueness that the
                      original did not have ("must" stays "must"; "may" stays "may").
                    - Do NOT translate to another natural language.
                    - Do NOT comment on the rewrite, prefix it, or wrap it in extra
                      narration.

                    SELF-CHECK before responding:
                    - Could a reader infer any requirement that was not in the
                      original? If yes, revise.
                    - Could a reader miss any requirement that was in the original?
                      If yes, revise.
                    - Does at least 60% of the prose read differently (different word
                      choice or sentence shape) from the input? If no, rewrite more
                      aggressively.
                    - Does the rewritten title use the same voice / synonym choices as
                      the rewritten body? If no, align them.

                    Output: a JSON object with two fields, `title` and `body`, holding
                    the rewritten values:
                    ```json
                    { "title": "...", "body": "..." }
                    ```
                """.trimIndent())
            }

            user {
                text("## Original Title:")
                newline()
                text("'''")
                newline()
                text(block.title)
                newline()
                text("'''")
                text("\n\n")

                text("## Original Body:")
                newline()
                text("'''")
                newline()
                text(block.body)
                newline()
                text("'''")
                text("\n\n")

                text("Now produce the paraphrased title and body following the given rules.")
            }
        }
    }
}
