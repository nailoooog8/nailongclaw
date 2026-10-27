// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

/**
 * Where the task ledger publishes changes — the observer half of the ledger
 * contract, the mirror of [io.agents.nailongclaw.ui.chat.ChatSink] on the task
 * side.
 *
 * ## Why this exists
 *
 * Two surfaces already render the same task flow (the Compose chat screen and
 * the Claude UI WebView), and the task page is about to become a third reader
 * of task truth. The chat side learned the hard way what happens when each
 * surface re-implements the flow itself (see the "Promote the Claude UI
 * mockup" backlog item: "Do not shortcut by duplicating the flow") — the rule
 * since then is one controller, many thin sinks. The ledger applies the same
 * rule to task *history*: one [TaskRecordStore], many thin [TaskSink]s.
 *
 * A surface that wants the current state reads [TaskRecordStore.list]; this
 * interface exists for the live deltas — a record appearing, a step landing,
 * a status flipping to a terminal state.
 *
 * ## Threading
 *
 * Callbacks may fire on any thread (ledger writes come from the UI thread and
 * from the agent thread); implementations marshal to their own UI thread, the
 * same contract [io.agents.nailongclaw.ui.chat.ChatSink] uses.
 */
interface TaskSink {

    /** A new record entered the ledger (a task was accepted). */
    fun onTaskRecordAdded(record: TaskRecord)

    /** An existing record changed — a step was appended or its status moved. */
    fun onTaskRecordUpdated(record: TaskRecord)
}
