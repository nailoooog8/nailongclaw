// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One user-typed message waiting for the single task slot to free up.
 */
data class PendingTask(
    val id: String,
    val text: String,
    val enqueuedAtMs: Long,
)

/**
 * Where messages go when the user sends while a task is running.
 *
 * The old behaviour was a rejection ("Another task is still running. Stop it
 * first.") — the user's typing was thrown away at the exact moment they had
 * the least patience. This queue makes busy-send mean *queue*, visibly and
 * honestly, the same contract the scheduled queue already keeps
 * ([ScheduleDispatcher]: "queued, visibly… never dropped, never concurrent").
 *
 * ## Who sends
 *
 * The queue itself never starts a task. A chat surface registers itself as
 * the sender ([registerSender]) — tasks drained this way go through
 * [io.agents.nailongclaw.ui.chat.TaskFlowController.sendTask], so the user
 * bubble, typing indicator and transcript land on the surface the user
 * typed on, not a headless [io.agents.nailongclaw.AppViewModel.startTask]
 * call whose events no surface renders. Drains are poked from
 * [io.agents.nailongclaw.AppViewModel]'s task-finished hook (the same place
 * the scheduled queue drains), after the orchestrator has released the slot.
 *
 * ## Memory only, on purpose
 *
 * A v1 queue that survives process death needs persistence *and* a
 * user-visible answer to "why did my message send itself yesterday?" —
 * scheduled tasks earn that (the user asked for a timer); a few seconds of
 * queued typing does not. The UI says so instead of pretending.
 */
object PendingTaskQueue {

    private const val DRAIN_DELAY_MS = 1_000L

    private val _items = MutableStateFlow<List<PendingTask>>(emptyList())
    val items: StateFlow<List<PendingTask>> = _items.asStateFlow()

    @Volatile
    private var sender: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Add a message to the back of the queue. Blank text is refused. */
    fun enqueue(text: String): PendingTask? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val task = PendingTask(
            id = "queued_${System.currentTimeMillis()}_${(0..999).random()}",
            text = trimmed,
            enqueuedAtMs = System.currentTimeMillis(),
        )
        _items.value = _items.value + task
        return task
    }

    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
    }

    /**
     * Send one queued item now, through the registered surface. Only works
     * when a surface is registered — the UI only offers this button on a
     * visible surface, and while a task runs the item stays (the drain will
     * pick it up when the slot frees).
     */
    fun sendNow(id: String): Boolean {
        val item = _items.value.firstOrNull { it.id == id } ?: return false
        val active = sender ?: return false
        _items.value = _items.value.filterNot { it.id == id }
        active(item.text)
        return true
    }

    /**
     * Poke a drain after a delay: the orchestrator releases the session
     * before delivering its terminal event, and a channel task can grab the
     * slot in between — the same grace period [ScheduleDispatcher.drainQueue]
     * waits before firing a queued task.
     */
    fun drainSoonIfIdle(isIdle: () -> Boolean) {
        mainHandler.postDelayed({
            if (!isIdle()) return@postDelayed
            val next = _items.value.firstOrNull() ?: return@postDelayed
            sendNow(next.id)
        }, DRAIN_DELAY_MS)
    }

    /**
     * The chat surface that will run drained tasks. Registered from
     * [io.agents.nailongclaw.ui.chat.TaskFlowController]; a null sender just
     * means no surface is alive, and items wait for the next one.
     */
    fun registerSender(send: ((String) -> Unit)?) {
        sender = send
    }
}
