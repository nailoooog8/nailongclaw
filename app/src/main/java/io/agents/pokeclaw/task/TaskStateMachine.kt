// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.task

import io.agents.pokeclaw.TaskEvent

/**
 * Pure transition table for [TaskRecord.status] — no I/O, no clock, no store.
 *
 * The four terminal [TaskEvent]s map to the four terminal statuses
 * (COMPLETED / FAILED / CANCELLED / BLOCKED), from RUNNING *and* from
 * WAITING_CONFIRM (a task can die while parked on a confirmation card —
 * watchdog timeout — and that death must still land in a terminal state).
 * A terminal record absorbs every later event: the orchestrator has several
 * paths that can each emit a terminal event, and a task that finished twice
 * must not flip status twice (the same one-shot rule
 * `TaskFlowController.publishAssistantResponse` applies to the transcript).
 *
 * RUNNING ↔ WAITING_CONFIRM is deliberately **not** here: no TaskEvent
 * carries it — a confirmation is not a task event — so those two transitions
 * live in [TaskRecordStore.noteWaitingConfirm] / [noteConfirmResolved].
 */
object TaskStateMachine {

    /**
     * Next status for [status] on [event], or null when the event does not
     * move it. Unknown-id / no-transition cases are the caller's non-events.
     */
    fun nextStatus(status: TaskRecord.Status, event: TaskEvent): TaskRecord.Status? {
        if (status.isTerminalState()) return null
        return when (event) {
            is TaskEvent.Completed -> TaskRecord.Status.COMPLETED
            is TaskEvent.Failed -> TaskRecord.Status.FAILED
            TaskEvent.Cancelled -> TaskRecord.Status.CANCELLED
            TaskEvent.Blocked -> TaskRecord.Status.BLOCKED
            else -> null
        }
    }

    /**
     * The ledger step an event contributes, or null when the event is not
     * step material.
     *
     * Only outcomes and narrated progress become steps. `ToolAction` is
     * deliberately skipped — action and result are not reliably paired (the
     * orchestrator also emits a bare `ToolAction("Retrying with AI agent")`
     * that never gets a result), and recording on the action would leave a
     * step that claims something happened when it may not have. The same
     * rule already governs the chat tool cards; the ledger must not diverge
     * from it or the two surfaces will tell different stories about one task.
     *
     * The `Finish` pseudo-tool is skipped for the same reason it is skipped
     * in the transcript: it is the loop's own bookkeeping, not device work.
     */
    fun stepFor(event: TaskEvent, at: Long): TaskRecord.Step? {
        return when (event) {
            is TaskEvent.ToolResult -> {
                if (event.toolName.contains("Finish", ignoreCase = true)) return null
                TaskRecord.Step(
                    at = at,
                    kind = TaskRecord.StepKind.TOOL,
                    text = event.detail.ifBlank { event.toolName },
                    toolName = event.toolName,
                    success = event.success,
                )
            }
            is TaskEvent.Progress -> TaskRecord.Step(
                at = at,
                kind = TaskRecord.StepKind.PROGRESS,
                text = truncate(event.description),
            )
            else -> null
        }
    }

    /** Visible cut, never a silent drop — a ledger that edits itself lies. */
    private const val STEP_TEXT_CAP = 300

    private fun truncate(text: String): String =
        if (text.length <= STEP_TEXT_CAP) text else text.take(STEP_TEXT_CAP) + "…"
}

private fun TaskRecord.Status.isTerminalState(): Boolean =
    this in TaskRecord.TERMINAL_STATUSES
