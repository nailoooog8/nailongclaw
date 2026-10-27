// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

/**
 * Where the schedule queue publishes changes — the doorbell half of the
 * scheduling pair, the mirror of [TaskSink] on the history side.
 *
 * ## Why one callback, not two
 *
 * [TaskSink] splits added/updated because the ledger's readers care about the
 * difference (a record appearing vs. a step landing). The queue has no such
 * reader: its only surface is the schedule page, which re-reads the whole
 * list on any change — the list is short (bounded by what a user would arm)
 * and a diff protocol would be ceremony nobody consumes. So this is a
 * doorbell — "the queue moved, re-read" — with the task that moved passed
 * along for implementations that want it.
 *
 * ## Threading
 *
 * Callbacks fire outside the store's lock, on the writer's thread — a UI
 * cancel, a poller-thread markQueued, a boot-time reconcile. Implementations
 * marshal to their own thread, the same contract [TaskSink] uses.
 */
interface ScheduleSink {

    /** A task was armed, cancelled, queued, fired, or its outcome landed. */
    fun onScheduleChanged(task: ScheduledTask)
}
