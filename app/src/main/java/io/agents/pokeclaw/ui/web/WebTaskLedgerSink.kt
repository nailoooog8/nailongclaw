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
 * just says "something in the ledger changed" and the owner decides what to
 * re-read and on which thread. That keeps this class trivial to test and
 * keeps the WebView plumbing in the Activity, the same split
 * [io.agents.pokeclaw.ui.chat.WebChatSink] uses ("evaluateJavascript is a
 * side effect, not a state store").
 *
 * [TaskSink] callbacks may arrive on any thread — the ledger writes from the
 * UI thread *and* the agent thread — so the owner's lambda is responsible
 * for marshalling. This class never assumes otherwise.
 */
class WebTaskLedgerSink(
    private val onLedgerChanged: () -> Unit,
) : TaskSink {

    override fun onTaskRecordAdded(record: TaskRecord) {
        onLedgerChanged()
    }

    override fun onTaskRecordUpdated(record: TaskRecord) {
        onLedgerChanged()
    }
}
