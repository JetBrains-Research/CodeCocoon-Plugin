package com.github.pderakhshanfar.codecocoonplugin.services

import ai.koog.prompt.dsl.Prompt
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.github.pderakhshanfar.codecocoonplugin.intellij.logging.withStdout
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.Serializable

/**
 * Rewrites a problem statement to look surface-different while preserving exact semantics.
 * Used in the eval pipeline AFTER [MetamorphicTextTransformer] has synced renames/moves.
 */
class ParaphraseTextTransformer(
    private val llm: LLM,
) {
    private val logger = thisLogger().withStdout()

    @Serializable
    data class ParaphrasedText(
        val problemStatement: String,
    )

    /**
     * Asks the LLM to paraphrase [problemStatement]. Returns null if the LLM call fails.
     */
    suspend fun rewrite(problemStatement: String): ParaphrasedText? {
        if (problemStatement.isBlank()) {
            logger.warn("WARNING: Empty problem statement passed to paraphraser")
            return ParaphrasedText(problemStatement)
        }

        val prompt = createRewritePrompt(problemStatement)
        logger.info("Created paraphrase prompt:\n'''$prompt\n'''")

        return llm.structuredRequest<ParaphrasedText>(
            prompt = prompt,
            maxRetries = 3,
            maxFixingAttempts = 2,
        )
    }

    private fun createRewritePrompt(problemStatement: String): Prompt {
        return Prompt.build("paraphrase-problem-statement") {
            system {
                text("""
                    You are a technical-documentation paraphrasing assistant.

                    You will be given a problem statement. Your task is to AGGRESSIVELY
                    rewrite it so the result looks SUBSTANTIALLY different on the surface
                    while remaining a strict semantic synonym — every requirement, fact,
                    and constraint preserved exactly.

                    Be bold with the rewrite. A near-copy of the input is a FAILURE. Aim
                    for a high-effort rewrite that a reader would not recognise as the
                    same prose at first glance, yet a domain expert would confirm carries
                    the same intent.

                    HARD CONSTRAINTS — preserve verbatim, never alter:
                    - All identifiers: class names, method names, variable names, package
                      names, file paths, command-line flags, environment variables, URLs,
                      version strings. Do NOT rename, translate, or pluralise them.
                    - All code-like tokens inside `backticks` and inside fenced code
                      blocks (```...```). Do not edit, reorder, or reformat code fences
                      or their contents.
                    - All numbers, units, and concrete values (e.g. "5 retries",
                      "200 OK", "UTF-8").
                    - Markdown structural elements: headings, bullet lists, numbered
                      lists, and tables. Their COUNT and the order of their items must
                      stay the same; you may rephrase the prose inside each item, but do
                      not add, drop, merge, or split items, and do not change heading
                      levels.

                    REQUIRED SURFACE CHANGES — apply several of these, not just one:
                    1. Lexical: replace ordinary verbs, nouns, adjectives, and connectors
                       with synonyms or near-synonyms ("provides" → "exposes",
                       "responsible for" → "in charge of", "must continue to" → "are
                       still required to"). Do this for the MAJORITY of non-identifier
                       content words.
                    2. Syntactic: reshape sentences. Convert active ↔ passive voice,
                       swap subject/object framing, hoist subordinate clauses to the
                       front, turn "X does Y so that Z" into "Z requires that X does Y",
                       and similar. At least half of the sentences should differ in
                       structure from their original counterparts.
                    3. Granularity: split long sentences into shorter ones, or fuse two
                       short sentences into one — wherever it improves rhythm and the
                       meaning is preserved.
                    4. Sentence ordering WITHIN A PARAGRAPH: you may reorder sentences
                       within the same paragraph if it preserves logical flow. Do NOT
                       move sentences across paragraph boundaries or across markdown
                       sections.
                    5. Register tightening: keep tone neutral and technical; trim throat-
                       clearing phrases ("In order to" → "To") where it does not change
                       meaning.

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
                    - Could a reader infer any requirement that was not in the original?
                      If yes, revise.
                    - Could a reader miss any requirement that was in the original? If
                      yes, revise.
                    - Does at least 60% of the prose read differently (different word
                      choice or sentence shape) from the input? If no, rewrite more
                      aggressively.

                    Output: a JSON object with a single field `problemStatement` holding
                    the rewritten text:
                    ```json
                    {
                        "problemStatement": "..."
                    }
                    ```
                """.trimIndent())
            }

            user {
                text("## Original Problem Statement:")

                newline()
                text("'''")
                newline()
                text(problemStatement)
                newline()
                text("'''")

                text("\n\n")
                text("Now produce the paraphrased problem statement following the given rules.")
            }
        }
    }
}
