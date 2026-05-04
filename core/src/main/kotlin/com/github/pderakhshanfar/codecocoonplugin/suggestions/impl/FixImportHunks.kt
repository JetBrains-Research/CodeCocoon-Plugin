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
    @SerialName("original_order") val originalOrder: List<String>? = null,
    @SerialName("reordered_to") val reorderedTo: List<String>? = null,
    @SerialName("removed_wildcards") val removedWildcards: List<String>? = null,
)

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
    unwanted modifications that an automated refactoring tool introduced into
    Java source files. You must minimize the diff: revert ONLY what each hunk
    describes, and nothing else.

    REPO ROOT: $repoRoot

    ALLOWED CHANGES (per hunk):
      • hunk_type = "import_reorder":
          Reorder the import statements inside the file's import block so they
          appear in the SAME ORDER as `original_order`. Do NOT add, remove, or
          rewrite any import line — only reorder lines that are already there.
          The set of import lines AFTER your edit must equal the set BEFORE.
      • hunk_type = "wildcard_import_removal":
          Restore the wildcard import lines from `removed_wildcards`, and
          delete the consecutive single-class imports that those wildcards
          subsume (these are the lines marked '+' in `full_hunk_diff` whose
          package matches a restored wildcard's package, e.g. lines starting
          with `import a.b.X;` for a restored `import a.b.*;`).

    FORBIDDEN CHANGES — MUST NEVER HAPPEN:
      • Do not modify code outside the import block.
      • Do not add, delete, rename, or reformat classes, methods, fields, or
        comments.
      • Do not change whitespace outside the lines you reorder/replace.
      • Do not run any git command. Do not stage, commit, push, or branch.
      • Do not invoke any tool other than list_directory, read_file, edit_file.
      • Do not edit files that are not listed in the user prompt.

    PROCESS (apply per hunk):
      1. Resolve the file: it is at "$repoRoot/<file>". If read_file fails
         there, use list_directory under the repo root to locate it.
      2. Read the file. Locate the import block near `new_start_line`
         (lines are 1-indexed; the block may have shifted by a few lines).
      3. Compute the minimal edit:
           - For import_reorder: build the replacement block by taking the
             CURRENT import lines (with their existing whitespace) and
             reordering them to match `original_order`. The set of lines
             must be IDENTICAL — only their order changes.
           - For wildcard_import_removal: form the replacement by inserting
             the wildcard lines from `removed_wildcards` (in the order they
             appear in `original_order` if provided, otherwise as given) and
             removing each single-class import they subsume.
      4. Use edit_file with `original` = the exact current import block
         snippet (multiple consecutive lines, copied verbatim from the
         file you just read) and `replacement` = the corrected block. Keep
         a one- or two-line anchor of unchanged context (the package line
         above and a blank line below) byte-for-byte identical inside both
         `original` and `replacement` so that the edit is unambiguous and
         proves you changed nothing else.
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
    val hunksJson = agentJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(HunkSpec.serializer()), hunks)
    return """
        BATCH DESCRIPTION: $batchDescription
        REPO ROOT: $repoRoot

        HUNKS TO REVERT (apply each one, in order):
        ```json
        $hunksJson
        ```

        For each hunk:
          • Resolve the file as $repoRoot/<file>.
          • Read it, find the import block, and apply the minimal edit by
            calling edit_file NOW, in this same run.
          • Use list_directory only if read_file cannot find the file.

        Do NOT respond with a plan and stop. Do NOT ask for confirmation
        ("Shall I proceed?", "Ready to apply?", etc.) — there is no human
        to answer, and the run will fail.

        Only AFTER every required edit_file call has been executed for
        every hunk in this batch, emit the JSON report described in the
        system prompt and end the turn.
    """.trimIndent()
}
