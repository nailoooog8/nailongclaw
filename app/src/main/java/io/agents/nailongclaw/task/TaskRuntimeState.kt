// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the agent is doing *right now*, as a small observable snapshot.
 *
 * This is the in-app counterpart of the floating pill: [io.agents.nailongclaw.floating.FloatingCircleManager]
 * has always received every task event (start, round, tool action, token
 * status, terminal) for every entry path — channel tasks included — so
 * instead of re-plumbing [io.agents.nailongclaw.TaskOrchestrator], the pill
 * manager publishes what it is given and every chat surface reads the same
 * feed. One writer, many readers, the same "one controller, many thin sinks"
 * rule the rest of the app follows.
 *
 * Null means idle. Consumers render nothing; there is deliberately no
 * "finished" state here — the transcript and the result pill carry outcomes.
 */
object TaskRuntimeState {

    data class Snapshot(
        val running: Boolean,
        /** The task text as received — a short label for the capsule. */
        val taskText: String,
        /** Current tool display name, blank until the first tool call. */
        val action: String,
        /** Latest loop round / step count. */
        val round: Int,
        /** When this task started (the showTaskNotify moment). */
        val startedAtMs: Long,
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    fun start(taskText: String) {
        _snapshot.value = Snapshot(
            running = true,
            taskText = taskText,
            action = "",
            round = 0,
            startedAtMs = System.currentTimeMillis(),
        )
    }

    fun update(action: String, round: Int) {
        val current = _snapshot.value ?: return
        _snapshot.value = current.copy(action = action, round = round)
    }

    fun stop() {
        _snapshot.value = null
    }
}
