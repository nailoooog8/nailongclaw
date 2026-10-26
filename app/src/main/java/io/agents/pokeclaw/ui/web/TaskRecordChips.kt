// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import io.agents.pokeclaw.task.TaskRecord

/** One chip on a completed task's answer turn: a label and its number. */
data class TaskChip(val label: String, val value: String)

/**
 * The completion chip row's content — a task's story in four numbers or
 * fewer, the shape the big three converged on (ChatGPT's "Paid / Sent /
 * Done. 25 of 26." confirmations, Open WebUI's completed-vs-total count,
 * lobe-chat's task-card chip row): the answer stays prose, the metadata
 * becomes scannable chips above it.
 *
 * Pure projection of [TaskRecord] — the page renders whatever this
 * returns and invents nothing. Rules:
 *
 * - 耗时 is the honest start-to-finish difference, never an estimate.
 * - 步骤 counts ledger steps; 工具 counts only steps that named a tool
 *   (progress notes and confirmations are not tools).
 * - 已建日程 appears only when a `calendar_create` step actually landed
 *   (a failed one is not a created event) — a chip that counts attempts
 *   instead of outcomes is a chip that lies.
 * - A task with nothing but a duration and a step count still gets its
 *   two chips; an empty list never renders an empty row.
 */
object TaskRecordChips {

    private const val CALENDAR_CREATE = "calendar_create"

    fun of(record: TaskRecord): List<TaskChip> = buildList {
        add(TaskChip("耗时", formatDuration(record.durationMillis)))
        add(TaskChip("步骤", record.steps.size.toString()))
        val tools = record.steps.count { it.toolName != null }
        if (tools > 0) add(TaskChip("工具", tools.toString()))
        val events = record.steps.count {
            it.toolName == CALENDAR_CREATE && it.success != false
        }
        if (events > 0) add(TaskChip("已建日程", events.toString()))
    }

    /**
     * Milliseconds as a human duration: 秒 below a minute, 分钟 below an
     * hour, then 小时 + 分. Mirrors cds.js's fmtElapsed so the task page's
     * elapsed column and the chip row read the same way.
     */
    internal fun formatDuration(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0)) / 1000
        if (seconds < 60) return "$seconds 秒"
        val minutes = seconds / 60
        if (minutes < 60) return "$minutes 分钟"
        val hours = minutes / 60
        val restMinutes = minutes % 60
        return if (restMinutes == 0L) "$hours 小时" else "$hours 小时 $restMinutes 分"
    }
}
