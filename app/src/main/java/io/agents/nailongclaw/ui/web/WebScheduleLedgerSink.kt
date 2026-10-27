// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import io.agents.nailongclaw.task.ScheduleSink
import io.agents.nailongclaw.task.ScheduledTask

/**
 * The WebView surface's half of the schedule-queue contract: the observer
 * side of [ScheduleLedgerBridge], implementing 部一's [ScheduleSink] so the
 * schedule page gets live deltas — a task armed, cancelled, queued, fired,
 * or its outcome landing.
 *
 * Deliberately does *not* read the store or touch a WebView, the same split
 * as [WebTaskLedgerSink]: the callback just says "the queue moved" and the
 * owner decides what to re-read and on which thread.
 *
 * [ScheduleSink] callbacks may arrive on any thread — a UI-thread cancel, a
 * poller-thread markQueued, a boot-time reconcile — so the owner's lambda is
 * responsible for marshalling. This class never assumes otherwise.
 */
class WebScheduleLedgerSink(
    private val onQueueChanged: () -> Unit,
) : ScheduleSink {

    override fun onScheduleChanged(task: ScheduledTask) {
        onQueueChanged()
    }
}
