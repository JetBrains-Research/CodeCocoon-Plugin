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
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
    val id: String = "",
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
    // Ordered list; an empty string "" denotes a blank separator line that was removed and must be restored.
    @SerialName("removed_wildcards") val removedWildcards: List<String>? = null,
    // Present only when hunk_type == "import_cross_hunk_move":
    // Role of THIS hunk in a multi-hunk rotation: "spurious_addition" | "missing_import" | "mixed".
    @SerialName("cross_move_role") val crossMoveRole: String? = null,
    // Specific import line(s) involved in THIS hunk of the rotation.
    @SerialName("moved_imports") val movedImports: List<String>? = null,
)

@OptIn(ExperimentalSerializationApi::class)
private val agentJson = Json {
    prettyPrint = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * Outcome of a single agent batch invocation.
 *
 * @property agentReport the agent's terminal message (advisory; not source of truth)
 * @property verifications per-hunk filesystem verification results, in the same order as the input hunks
 * @property error null on success; an exception describing the agent-level failure (e.g. iteration cap, network)
 *                 The verifications list is still the per-hunk source of truth even when this is non-null.
 */
data class FixBatchResult(
    val agentReport: String?,
    val verifications: List<VerifyResult>,
    val error: Throwable? = null,
) {
    val appliedHunkIds: List<String> get() = verifications.filter { it.applied }.map { it.hunkId }.filter { it.isNotEmpty() }
    val isFullySuccessful: Boolean get() = error == null && verifications.all { it.applied }
}

suspend fun runImportHunkFixerAgent(
    token: String,
    model: LLModel,
    repoRoot: String,
    batchDescription: String,
    hunks: List<HunkSpec>,
    maxAgentIterations: Int = 60,
): FixBatchResult {
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
                logBoth("→ Calling `${ctx.tool.name}` with: ${ctx.toolArgs}")
            }
            onToolCallCompleted { ctx ->
                logBoth("← `${ctx.tool.name}` returned: ${ctx.result.toString().take(MAX_TOOL_LOG_CHARS)}")
            }
            onToolCallFailed { ctx ->
                logBoth("✖ `${ctx.tool.name}` FAILED: ${ctx.throwable.message}")
            }
            onToolValidationFailed { ctx ->
                logBoth("✖ `${ctx.tool.name}` VALIDATION FAILED: ${ctx.error}")
            }
        }
    }

    // Snapshot files touched by import_cross_hunk_move BEFORE the run, so
    // we can detect "agent did nothing" cases that aren't visible from
    // import-presence checks alone (the rotation preserves the import set).
    val crossMoveFiles = hunks
        .filter { it.hunkType == "import_cross_hunk_move" }
        .map { it.file }
        .distinct()
    val crossMoveBefore: Map<String, String> = crossMoveFiles.mapNotNull { rel ->
        runCatching { rel to File(repoRoot, rel).readText() }.getOrNull()
    }.toMap()

    val agentRun = runCatching {
        agent.run(agentInput = buildUserPrompt(repoRoot, batchDescription, hunks))
    }
    val agentReport = agentRun.getOrNull()
    val agentError = agentRun.exceptionOrNull()

    // Verify regardless of whether the agent threw — partial successes count.
    val verifications = hunks.map { verifyHunkApplied(repoRoot, it, crossMoveBefore) }
    return FixBatchResult(agentReport = agentReport, verifications = verifications, error = agentError)
}

/** Renders the unapplied hunks as a multi-line summary suitable for logging. */
fun FixBatchResult.unappliedSummary(): String {
    val unapplied = verifications.filterNot { it.applied }
    if (unapplied.isEmpty()) return "all hunks verified applied"
    return unapplied.joinToString("\n  - ", prefix = "  - ") {
        val tag = if (it.hunkId.isNotEmpty()) "${it.hunkId} " else ""
        "$tag${it.file} [${it.hunkType}]: ${it.reason}"
    }
}

private const val MAX_TOOL_LOG_CHARS = 500

private val agentLogger = Logger.getInstance("FixImportHunksAgent")

private fun logBoth(message: String) {
    agentLogger.info(message)
    println(message)
}

/**
 * Result of filesystem-level verification of a single hunk after the agent run.
 * The agent's own `applied: true` claim is advisory; this is the source of truth.
 */
data class VerifyResult(
    val hunkId: String,
    val file: String,
    val hunkType: String,
    val applied: Boolean,
    val reason: String,
)

/**
 * Reads the file under `repoRoot/<hunk.file>` and checks the post-condition
 * implied by the hunk type.
 *
 * - `import_reorder`: filters the file's imports down to the ones listed in
 *   `originalImportBlock` (preserving file order) and checks they match the
 *   target order. If they still match `currentImportBlock`, the agent did
 *   nothing.
 * - `wildcard_import_removal`: every line in `removedWildcards` must appear
 *   verbatim as a standalone import line in the file.
 */
private fun verifyHunkApplied(
    repoRoot: String,
    hunk: HunkSpec,
    crossMoveBefore: Map<String, String> = emptyMap(),
): VerifyResult {
    val target = File(repoRoot, hunk.file)
    if (!target.isFile) {
        return VerifyResult(hunk.id, hunk.file, hunk.hunkType, false, "file not found at $target")
    }
    val content = try {
        target.readText()
    } catch (e: Exception) {
        return VerifyResult(hunk.id, hunk.file, hunk.hunkType, false, "could not read file: ${e.message}")
    }
    val fileImports = content.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("import ") && it.endsWith(";") }
        .toList()

    return when (hunk.hunkType) {
        "import_reorder" -> verifyReorder(hunk, fileImports)
        "wildcard_import_removal" -> verifyWildcard(hunk, fileImports)
        "import_cross_hunk_move" -> verifyCrossHunkMove(hunk, fileImports, content, crossMoveBefore[hunk.file])
        else -> VerifyResult(hunk.id, hunk.file, hunk.hunkType, false, "unknown hunk_type: ${hunk.hunkType}")
    }
}

private fun verifyReorder(hunk: HunkSpec, fileImports: List<String>): VerifyResult {
    val target = hunk.originalImportBlock?.map { it.importStmt.trim() }
        ?: return VerifyResult(hunk.id, hunk.file, hunk.hunkType, false, "missing originalImportBlock")
    val current = hunk.currentImportBlock?.map { it.importStmt.trim() } ?: emptyList()

    // Are all expected imports even present?
    val missing = target.filter { it !in fileImports }
    if (missing.isNotEmpty()) {
        return VerifyResult(
            hunk.id, hunk.file, hunk.hunkType, false,
            "imports missing from file: ${missing.joinToString(", ")}",
        )
    }

    // Filter file's imports down to just the ones this hunk cares about,
    // preserving file order. That sequence must equal the target order.
    val targetSet = target.toSet()
    val filtered = fileImports.filter { it in targetSet }

    return if (filtered == target) {
        VerifyResult(hunk.id, hunk.file, hunk.hunkType, true, "imports in target order")
    } else if (current.isNotEmpty() && filtered == current) {
        VerifyResult(
            hunk.id, hunk.file, hunk.hunkType, false,
            "imports still in pre-revert order; agent did not edit the file",
        )
    } else {
        VerifyResult(
            hunk.id, hunk.file, hunk.hunkType, false,
            "imports in unexpected order: $filtered (expected $target)",
        )
    }
}

private fun verifyWildcard(hunk: HunkSpec, fileImports: List<String>): VerifyResult {
    val expected = hunk.removedWildcards
        ?.filter { it.isNotEmpty() }       // skip blank-separator markers
        ?.map { it.trim() }
        ?: return VerifyResult(hunk.id, hunk.file, hunk.hunkType, false, "missing removedWildcards")
    val missing = expected.filter { it !in fileImports }
    return if (missing.isEmpty()) {
        VerifyResult(hunk.id, hunk.file, hunk.hunkType, true, "wildcards present: ${expected.joinToString(", ")}")
    } else {
        VerifyResult(
            hunk.id, hunk.file, hunk.hunkType, false,
            "wildcards still missing from file: ${missing.joinToString(", ")}",
        )
    }
}

/**
 * Verifies an `import_cross_hunk_move` hunk. The rotation preserves the
 * file's overall import set, so set-presence alone is insufficient — we
 * also compare the post-run file content against a pre-run snapshot to
 * detect "agent did nothing" cases.
 *
 * - "missing_import" / "mixed": every line in `moved_imports` MUST be
 *   present in the file's imports after the run.
 * - "spurious_addition" / "mixed": the file content must DIFFER from the
 *   pre-run snapshot (something was edited). We can't easily check
 *   "imports no longer at the spurious location" without parsing line
 *   ranges, so we rely on the file-changed signal plus the missing-import
 *   counterpart hunks (each rotation has at least one missing_import or
 *   mixed entry as well).
 */
private fun verifyCrossHunkMove(
    hunk: HunkSpec,
    fileImports: List<String>,
    fileContent: String,
    beforeContent: String?,
): VerifyResult {
    val moved = hunk.movedImports?.map { it.trim() }
        ?: return VerifyResult(hunk.id, hunk.file, hunk.hunkType, false, "missing movedImports")
    val role = hunk.crossMoveRole
        ?: return VerifyResult(hunk.id, hunk.file, hunk.hunkType, false, "missing crossMoveRole")

    val fileChanged = beforeContent != null && fileContent != beforeContent
    val snapshotMissing = beforeContent == null

    return when (role) {
        "missing_import" -> {
            val missing = moved.filter { it !in fileImports }
            if (missing.isEmpty()) {
                VerifyResult(hunk.id, hunk.file, hunk.hunkType, true, "moved imports restored: ${moved.joinToString(", ")}")
            } else {
                VerifyResult(
                    hunk.id, hunk.file, hunk.hunkType, false,
                    "moved imports still missing: ${missing.joinToString(", ")}",
                )
            }
        }
        "spurious_addition" -> {
            when {
                snapshotMissing -> VerifyResult(
                    hunk.id, hunk.file, hunk.hunkType, true,
                    "no pre-run snapshot; spurious_addition cannot be falsified",
                )
                fileChanged -> VerifyResult(
                    hunk.id, hunk.file, hunk.hunkType, true,
                    "file changed (rotation applied)",
                )
                else -> VerifyResult(
                    hunk.id, hunk.file, hunk.hunkType, false,
                    "file unchanged since before agent run; rotation not applied",
                )
            }
        }
        "mixed" -> {
            val missing = moved.filter { it !in fileImports }
            when {
                missing.isNotEmpty() -> VerifyResult(
                    hunk.id, hunk.file, hunk.hunkType, false,
                    "moved imports missing from file: ${missing.joinToString(", ")}",
                )
                snapshotMissing || fileChanged -> VerifyResult(
                    hunk.id, hunk.file, hunk.hunkType, true,
                    "moved imports present and file changed",
                )
                else -> VerifyResult(
                    hunk.id, hunk.file, hunk.hunkType, false,
                    "file unchanged since before agent run; rotation not applied",
                )
            }
        }
        else -> VerifyResult(
            hunk.id, hunk.file, hunk.hunkType, false,
            "unknown cross_move_role: $role",
        )
    }
}

private fun buildSystemPrompt(repoRoot: String): String = """
    You are a precise code-editing agent. Your ONLY job is to revert specific
    unwanted modifications that an automated refactoring tool (IntelliJ's
    import optimizer) introduced into Java source files. You must minimize
    the diff: revert ONLY what each hunk describes, and nothing else.

    REPO ROOT: '$repoRoot'

    INPUT SCHEMA — fields you will receive per hunk:
      Common to every hunk:
        • file               — repo-relative path to the Java source file.
        • hunk_type          — "import_reorder", "wildcard_import_removal",
                              or "import_cross_hunk_move".
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
        • removed_wildcards  — ORDERED list of import lines (verbatim,
                              including `import` keyword and trailing `;`)
                              that the optimizer DELETED and that you must
                              ADD BACK in this order. An entry equal to the
                              empty string "" denotes a BLANK separator
                              line that was also removed — restore it as a
                              blank line in the same position.
      For hunk_type == "import_cross_hunk_move" ONLY:
        • cross_move_role    — role of THIS hunk in a coordinated multi-hunk
                              rotation:
                                "spurious_addition" — this location only
                                  ADDS imports that actually belong at
                                  another location → REMOVE them here.
                                "missing_import" — this location only
                                  REMOVES imports that belong here →
                                  ADD them back here.
                                "mixed" — this location both adds and
                                  removes; revert both sides.
        • moved_imports      — the specific import line(s) involved in THIS
                              hunk (verbatim).

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
      • hunk_type = "import_cross_hunk_move":
          The optimizer ROTATED imports between two or more locations in
          the SAME file (e.g. swapped imports that were originally near
          line 3 with imports near line 120 of the same file). The input
          contains MULTIPLE entries with this hunk_type for one file —
          they are coordinated and must be reverted TOGETHER as a single
          rotation, not independently:
            - cross_move_role == "spurious_addition" → DELETE the lines
              in `moved_imports` from this location.
            - cross_move_role == "missing_import" → INSERT the lines in
              `moved_imports` back at this location.
            - cross_move_role == "mixed" → both delete and insert at this
              location, as `action` describes.
          Net effect: the SET of imports in the file is unchanged; only
          their positions are restored. Process all entries for the same
          file in ONE pass (one read_file, then plan all
          insertions/deletions, then issue your edit_file call(s)). Do
          NOT insert before deleting (or vice versa) in a way that ends
          up duplicating the same import line.

    CROSS-HUNK COORDINATION (import_cross_hunk_move):
      When you see N ≥ 2 import_cross_hunk_move entries with the same
      `file`, treat them as a single coordinated revert. After your
      edits:
        • Every import that was originally in the file must still be
          there exactly once (no duplicates, no losses).
        • Imports listed in spurious_addition entries must NOT remain at
          their spurious location.
        • Imports listed in missing_import entries MUST appear at their
          missing location.

    DO NOT TOUCH OTHER CHANGES IN `full_hunk_diff`:
      The `full_hunk_diff` may contain unrelated `+`/`-` lines from other
      transformations (e.g. a class rename like `JSON` → `JsonMapper`).
      Those are intentional and must STAY. Restrict your edit strictly to
      the field that describes your hunk type:
        - For import_reorder: only the imports in original_import_block /
          current_import_block.
        - For wildcard_import_removal: only the lines in removed_wildcards.
        - For import_cross_hunk_move: only the lines in moved_imports
          (across all hunks for the same file).

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
                 current import block at the right position. Empty-string
                 entries denote a blank separator line — restore them as
                 blank lines. Do NOT remove any other import line.
           - For import_cross_hunk_move:
               • Group all entries with this hunk_type for the SAME file
                 and revert them as ONE rotation. Read the file ONCE,
                 plan all deletions (spurious_addition entries) and
                 insertions (missing_import entries; both for mixed)
                 together, then issue your edit_file call(s). The
                 post-revert file must contain every original import
                 exactly once — no duplicates, no losses.
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

    TRUTHFULNESS (CRITICAL):
      • Failure to call `edit_file` for a hunk is NOT a no-op success — it
        is a FAILURE of the run. Skipping a hunk because "the file already
        looks fine" is forbidden; trust the input, not your judgment.
      • Mark a hunk `"applied": true` ONLY if you actually called
        `edit_file` for that hunk AND the tool's response said the patch
        was applied successfully ("Successfully edited file"). If the tool
        responded with "patch application failed", the hunk is NOT applied
        — re-read the file with `read_file` to copy the import block
        verbatim, then call `edit_file` again with that fresh `original`.
      • NEVER report `"applied": true` for a hunk you did not edit. NEVER
        report success based on what you intended to do — only on tool
        responses you actually received in this run.
      • A separate filesystem verifier runs after you finish and will
        compare every file against the expected post-revert state. False
        `"applied": true` claims will be caught and the run will be marked
        failed regardless of what your report says.

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
            `removed_wildcards` (in order; "" entries = blank separator
            lines) into the import block; do not remove or modify any
            other import.
          • For "import_cross_hunk_move": group ALL entries for the SAME
            file and revert them as one rotation. Per-entry: if
            cross_move_role == "spurious_addition", DELETE the lines in
            `moved_imports` from this location; if "missing_import",
            INSERT them back at this location; if "mixed", do both as the
            `action` describes. The file's overall import set must remain
            unchanged — no duplicates, no losses.
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
