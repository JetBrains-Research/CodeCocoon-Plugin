package com.github.pderakhshanfar.codecocoonplugin.suggestions.impl

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.rag.base.files.JVMFileSystemProvider
import com.github.pderakhshanfar.codecocoonplugin.common.LLM
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FixHunksInput(
    @SerialName("repo_root") val repoRoot: String,
    @SerialName("patch_label") val patchLabel: String = "",
    val description: String = "",
    val hunks: List<HunkSpec>,
)

@Serializable
data class ImportLine(
    val line: Int,
    @SerialName("import") val importStmt: String,
)

@Serializable
data class HunkSpec(
    val file: String,
    @SerialName("hunk_type") val hunkType: String,
    val description: String = "",
    @SerialName("hunk_header") val hunkHeader: String = "",
    @SerialName("old_start_line") val oldStartLine: Int = 0,
    @SerialName("old_line_count") val oldLineCount: Int = 0,
    @SerialName("new_start_line") val newStartLine: Int = 0,
    @SerialName("new_line_count") val newLineCount: Int = 0,
    val action: String = "",
    @SerialName("full_hunk_diff") val fullHunkDiff: String = "",
    // Present only when hunk_type == "import_reorder":
    @SerialName("original_import_block") val originalImportBlock: List<ImportLine>? = null,
    @SerialName("current_import_block") val currentImportBlock: List<ImportLine>? = null,
    // Present only when hunk_type == "wildcard_import_removal":
    @SerialName("removed_wildcards") val removedWildcards: List<String>? = null,
)

@OptIn(ExperimentalSerializationApi::class)
private val agentJson = Json {
    prettyPrint = true
    encodeDefaults = false
    explicitNulls = false
}

suspend fun runImportHunkFixerAgent(
    token: String,
    model: LLModel,
    repoRoot: String,
    batchDescription: String,
    hunks: List<HunkSpec>,
    maxAgentIterations: Int = 60,
): Result<String> {
    val executor = LLM.createGrazieExecutor(token)

    val agent = AIAgent(
        promptExecutor = executor,
        agentConfig = AIAgentConfig(
            prompt = Prompt.build("import-hunk-reverter") {
                system(buildSystemPrompt(repoRoot))
            },
            model = model,
            maxAgentIterations = maxAgentIterations,
        ),
        toolRegistry = ToolRegistry {
            tool(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))
            tool(ReadFileTool(JVMFileSystemProvider.ReadOnly))
            tool(EditFileTool(JVMFileSystemProvider.ReadWrite))
        },
        strategy = reActStrategy(
            name = "import_hunk_reverter",
            reasoningInterval = 1,
        ),
    ) {
        handleEvents {
            onToolCallStarting { ctx ->
                thisLogger().info("→ Calling `${ctx.tool.name}` with: ${ctx.toolArgs}")
            }
        }
    }

    return runCatching {
        agent.run(agentInput = buildUserPrompt(repoRoot, batchDescription, hunks))
    }
}

private fun buildSystemPrompt(repoRoot: String): String = """
    You are a precise code-editing agent. Your ONLY job is to revert specific
    unwanted modifications that an automated refactoring tool (IntelliJ's
    import optimizer) introduced into Java source files. You must minimize
    the diff: revert ONLY what each hunk describes, and nothing else.

    REPO ROOT: $repoRoot

    INPUT SCHEMA — fields you will receive per hunk:
      Common to every hunk:
        • file               — repo-relative path to the Java source file.
        • hunk_type          — "import_reorder" or "wildcard_import_removal".
        • description        — human-readable explanation of this specific hunk.
        • hunk_header        — the raw `@@ -a,b +c,d @@` header from the diff.
        • old_start_line / old_line_count   — window in the ORIGINAL file.
        • new_start_line / new_line_count   — window in the CURRENT file
                                              (where to look NOW).
        • action             — one-sentence imperative instruction.
        • full_hunk_diff     — the complete unified-diff hunk (context).
      For hunk_type == "import_reorder" ONLY:
        • original_import_block — list of {line, import} entries showing the
                                  imports in the order they had BEFORE the
                                  optimizer ran (the desired post-revert
                                  order). The "line" field is the absolute
                                  1-indexed line number in the original file.
        • current_import_block  — list of {line, import} entries showing the
                                  SAME imports as they appear NOW in the
                                  file. The set of `import` strings is
                                  identical to original_import_block — only
                                  the ORDER differs.
      For hunk_type == "wildcard_import_removal" ONLY:
        • removed_wildcards  — list of wildcard import statements (verbatim,
                              including `import` keyword and trailing `;`)
                              that the optimizer DELETED and that you must
                              ADD BACK.

    ALLOWED CHANGES (per hunk):
      • hunk_type = "import_reorder":
          Reorder the lines in the file's import block so that the imports
          appear in the SAME ORDER as `original_import_block` (top → bottom).
          The SET of import lines AFTER your edit MUST equal the SET BEFORE
          (same imports, just reordered). Preserve any blank lines that
          separate sub-groups in the current block exactly.
      • hunk_type = "wildcard_import_removal":
          Add each line in `removed_wildcards` back into the file's import
          section. Do NOT remove or modify any other import. Place the
          restored wildcard at the location implied by `full_hunk_diff` and
          `new_start_line` (typically the same relative position it had in
          the original — e.g. as a separate static-imports group at the end
          of the import block, with a preceding blank line if the diff
          shows one). If the diff is ambiguous, prefer placing it at the
          end of the import block.

    DO NOT TOUCH OTHER CHANGES IN `full_hunk_diff`:
      The `full_hunk_diff` may contain unrelated `+`/`-` lines from other
      transformations (e.g. a class rename like `JSON` → `JsonMapper`).
      Those are intentional and must STAY. Restrict your edit strictly to
      the field that describes your hunk type:
        - For import_reorder: only the imports in original_import_block /
          current_import_block.
        - For wildcard_import_removal: only the lines in removed_wildcards.

    FORBIDDEN CHANGES — MUST NEVER HAPPEN:
      • Do not modify code outside the import block.
      • Do not add, delete, rename, or reformat classes, methods, fields,
        comments, javadoc, or annotations.
      • Do not change whitespace outside the lines you reorder/insert.
      • Do not run any git command. Do not stage, commit, push, or branch.
      • Do not invoke any tool other than list_directory, read_file, edit_file.
      • Do not edit files that are not listed in the user prompt.

    PROCESS (apply per hunk):
      1. Resolve the file: it is at "$repoRoot/<file>". If read_file fails
         there, use list_directory under the repo root to locate it.
      2. Read the file. Locate the import block near `new_start_line`
         (lines are 1-indexed; the block may have shifted by a few lines).
      3. Compute the minimal edit:
           - For import_reorder:
               • Confirm that the set of imports currently present matches
                 `current_import_block`. (If not, the file has drifted —
                 still attempt the reorder using whatever imports are there
                 that also appear in `original_import_block`.)
               • Build the replacement block by listing the imports in the
                 order of `original_import_block`. Preserve any blank
                 separator lines you observed in the current block at the
                 SAME positions (e.g., a blank line before the static
                 imports section).
           - For wildcard_import_removal:
               • Insert each wildcard from `removed_wildcards` into the
                 current import block at the right position. Do NOT remove
                 any other import line.
      4. Use edit_file with `original` = the exact current import block
         snippet (multiple consecutive lines, copied verbatim from the
         file you just read) and `replacement` = the corrected block. Keep
         a one- or two-line anchor of unchanged context (the package line
         above and the blank line below the import block) byte-for-byte
         identical inside both `original` and `replacement` so the edit is
         unambiguous and proves you changed nothing else.
      5. After editing, read the file back and verify that ONLY import-block
         lines differ from your mental model of the pre-edit content. If
         anything else changed, fix it with another edit_file call.

    AUTONOMY (CRITICAL):
      • You operate fully autonomously. There is NO human in the loop. The
        caller cannot answer questions, approve plans, or reply "yes".
      • NEVER ask "Shall I proceed?", "Should I continue?", "Ready to apply?",
        or any other confirmation question. If you do, the run is marked
        failed and the changes are discarded.
      • Do NOT emit a plan/thoughts message and stop. Plans without applied
        edits are worthless here. Execute every required edit_file call in
        this same run, before producing your terminal message.
      • The ONLY acceptable terminal message is the JSON report below, and
        you may emit it ONLY AFTER every needed edit_file call has run.

    OUTPUT (final assistant message — emit ONLY after all edits are done):
      A short JSON report:
      ```json
      {
        "results": [
          { "file": "<file>", "hunk_type": "...", "applied": true, "reason": "..." }
        ]
      }
      ```
""".trimIndent()

private fun buildUserPrompt(
    repoRoot: String,
    batchDescription: String,
    hunks: List<HunkSpec>,
): String {
    val hunksJson = agentJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(HunkSpec.serializer()),
        hunks,
    )
    return """
        BATCH DESCRIPTION: $batchDescription
        REPO ROOT: $repoRoot

        HUNKS TO REVERT (apply each one, in order):
        ```json
        $hunksJson
        ```

        For each hunk:
          • Resolve the file as $repoRoot/<file>.
          • Read it, find the import block near `new_start_line`, and apply
            the minimal edit by calling edit_file NOW, in this same run.
          • For "import_reorder": reorder current imports to match
            `original_import_block` (set of imports unchanged).
          • For "wildcard_import_removal": insert every line from
            `removed_wildcards` into the import block; do not remove or
            modify any other import.
          • Use list_directory only if read_file cannot find the file.
          • Ignore unrelated `+`/`-` lines in `full_hunk_diff` — they are
            from other transformations and must stay.

        Do NOT respond with a plan and stop. Do NOT ask for confirmation
        ("Shall I proceed?", "Ready to apply?", etc.) — there is no human
        to answer, and the run will fail.

        Only AFTER every required edit_file call has been executed for
        every hunk in this batch, emit the JSON report described in the
        system prompt and end the turn.
    """.trimIndent()
}
