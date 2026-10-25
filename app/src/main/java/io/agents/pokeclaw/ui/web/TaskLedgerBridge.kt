// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import com.google.gson.Gson
import io.agents.pokeclaw.task.TaskRecord
import io.agents.pokeclaw.task.TaskRecordStore
import io.agents.pokeclaw.utils.XLog

/**
 * The only read path between the durable task ledger ([TaskRecordStore]) and
 * the task page (`mockup/claude-ui`, through the `PokeClawChat.readTaskList` /
 * `readTaskDetail` bridge methods). The observer half lives in
 * [WebTaskLedgerSink].
 *
 * ## Why a mapping layer exists at all
 *
 * The store's model is the record of truth: [TaskRecord] with its
 * `status`/`steps`/`channel`/`originActivity`. The page's contract is what a
 * read-only ledger view needs to draw: short state names, display labels,
 * `lastStep` summaries. Keeping the translation here — instead of bending
 * either side toward the other — means 部一 can evolve the record format and
 * 部二 can evolve the page without a shared edit on every field. The mapping
 * is pure and unit-tested (TaskLedgerBridgeTest) precisely because a silent
 * drift here shows up as a wrong card, not a crash.
 *
 * ## Honesty rules the page depends on
 *
 * - An empty ledger serialises to `[]`; the page then shows its empty state.
 *   Nothing here ever fabricates a record.
 * - A read failure (store never initialised, unreadable file) also yields
 *   `[]` / `null` **and logs** — the page cannot tell the two apart, logcat
 *   can.
 * - Timestamps are epoch millis all the way through; the page computes
 *   elapsed time itself so a running card never shows a stale duration.
 */
object TaskLedgerBridge {

    private val gson = Gson()

    /** Task summaries, newest first — what the page's list level draws. */
    fun readList(): String = runCatching {
        gson.toJson(TaskRecordStore.list().map { summaryOf(it) })
    }.getOrElse { e ->
        XLog.e(TAG, "readList failed — reporting an empty ledger", e)
        "[]"
    }

    /** One record with its full step ledger, or `null` when the id is unknown. */
    fun readDetail(id: String?): String {
        val wanted = id?.takeIf { it.isNotBlank() } ?: return "null"
        val record = runCatching { TaskRecordStore.get(wanted) }.getOrElse { e ->
            XLog.e(TAG, "readDetail('$wanted') failed — reporting a missing record", e)
            null
        } ?: return "null"
        return runCatching { gson.toJson(detailOf(record)) }.getOrElse { e ->
            XLog.e(TAG, "readDetail('$wanted') failed to serialise", e)
            "null"
        }
    }

    // ── Mapping (pure — see TaskLedgerBridgeTest) ────────────────────────────

    internal fun summaryOf(record: TaskRecord): Map<String, Any?> = mapOf(
        "id" to record.id,
        "title" to record.taskText,
        "state" to pageStateOf(record.status),
        "channel" to record.channel.displayName,
        "createdAt" to record.createdAt,
        "finishedAt" to record.finishedAtOrNull(),
        "stepCount" to record.steps.size,
        "lastStep" to record.steps.lastOrNull()?.let { stepOf(it) },
        // 终态载荷：失败原因与最终答案。Failed 事件不产步骤
        // （TaskStateMachine.stepFor），原因只活在 error 里——不把它送到
        // 页面，失败任务在设备上就只剩一句没有下文的红标。
        "error" to record.error,
        "answer" to record.answer,
    )

    internal fun detailOf(record: TaskRecord): Map<String, Any?> =
        summaryOf(record) + mapOf(
            "origin" to originLabel(record.originActivity),
            "prompt" to record.taskText,
            "steps" to record.steps.map { stepOf(it) },
        )

    internal fun stepOf(step: TaskRecord.Step): Map<String, Any?> = mapOf(
        "name" to (step.toolName ?: step.kind.pageLabel()),
        "summary" to step.text,
        "success" to (step.success ?: true),
        "at" to step.at,
    )

    /**
     * Ledger status → the page's six states.
     *
     * BLOCKED maps to FAILED: the task did not finish and the user did not
     * cancel it, so the card reads as a failure; the *reason* ("Blocked by a
     * system dialog.") stays visible in the detail's error row. WAITING_CONFIRM
     * passes through — the page's 待确认 card is the amber pill that says a
     * human decision is pending, which is exactly what the status means.
     */
    internal fun pageStateOf(status: TaskRecord.Status): String = when (status) {
        TaskRecord.Status.RUNNING -> "RUNNING"
        TaskRecord.Status.WAITING_CONFIRM -> "WAITING_CONFIRM"
        TaskRecord.Status.COMPLETED -> "SUCCEEDED"
        TaskRecord.Status.FAILED -> "FAILED"
        TaskRecord.Status.CANCELLED -> "CANCELLED"
        TaskRecord.Status.BLOCKED -> "FAILED"
    }

    private fun TaskRecord.finishedAtOrNull(): Long? = updatedAt.takeIf { isTerminal }

    private fun TaskRecord.StepKind.pageLabel(): String = when (this) {
        TaskRecord.StepKind.TOOL -> "工具"
        TaskRecord.StepKind.PROGRESS -> "进度"
        TaskRecord.StepKind.NOTE -> "备注"
    }

    /**
     * FQCN → short label for the detail meta line. Unknown origins fall back
     * to the simple class name rather than a blank: "where did this come
     * from" is the question the field exists to answer.
     */
    internal fun originLabel(fqcn: String?): String? {
        if (fqcn.isNullOrBlank()) return null
        val simple = fqcn.substringAfterLast('.')
        return when (simple) {
            "ClaudeUiPreviewActivity" -> "Claude UI 聊天"
            "ComposeChatActivity" -> "聊天页"
            else -> simple
        }
    }

    private const val TAG = "TaskLedgerBridge"
}
