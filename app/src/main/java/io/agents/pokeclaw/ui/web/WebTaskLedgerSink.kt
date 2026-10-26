// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import io.agents.pokeclaw.task.TaskRecord
import io.agents.pokeclaw.task.TaskSink

/**
 * The WebView surface's half of the ledger contract: the observer side of
 * [TaskLedgerBridge], implementing 部一's [TaskSink] so the task page gets
 * live deltas — a record appearing, a step landing, a status flipping.
 *
 * Deliberately does *not* read the store or touch a WebView: the callback
 * hands the owner the record that changed and the owner decides what to
 * re-read and on which thread. That keeps this class trivial to test and
 * keeps the WebView plumbing in the Activity, the same split
 * [io.agents.pokeclaw.ui.chat.WebChatSink] uses ("evaluateJavascript is a
 * side effect, not a state store").
 *
 * The record is passed through, not consumed: the owner re-reads a full
 * snapshot for the list (a missed delta has no recovery path), and reads
 * the record only to decide whether the task that just terminated is the
 * one this surface was running — which is what puts completion chips on
 * the right answer turn.
 *
 * [TaskSink] callbacks may arrive on any thread — the ledger writes from
 * the UI thread *and* the agent thread — so the owner's lambda is
 * responsible for marshalling. This class never assumes otherwise.
 */
class WebTaskLedgerSink(
    private val onLedgerChanged: (TaskRecord) -> Unit,
) : TaskSink {

    override fun onTaskRecordAdded(record: TaskRecord) {
        onLedgerChanged(record)
    }

    override fun onTaskRecordUpdated(record: TaskRecord) {
        onLedgerChanged(record)
    }
}
