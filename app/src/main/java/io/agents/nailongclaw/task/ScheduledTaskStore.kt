// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.agents.nailongclaw.utils.XLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Durable store of scheduled tasks — the timer half of the scheduling pair
 * (see [ScheduledTask]). Same layout and rules as [TaskRecordStore]: one JSON
 * file in the adb-pullable store dir, tmp+rename atomic writes, injected
 * clock/dir for tests, per-record defensive parsing.
 *
 * ## The queue's honesty lives here
 *
 * [markQueued] / [nextQueued] implement the single-concurrency queue: the app
 * runs one task at a time (the orchestrator's lock), so a scheduled task that
 * fires while another runs is *queued with a visible status*, never silently
 * dropped and never run concurrently. [drain] is pure policy — who may fire
 * next — so the queue order is unit-testable without Android types.
 *
 * Every write point tells the [ScheduleSink]s, outside the lock — the
 * schedule page reads the list, the sinks are what makes it live.
 */
object ScheduledTaskStore {

    private const val TAG = "ScheduledTaskStore"
    private const val FILE_NAME = "scheduled_tasks.json"
    private const val FILE_VERSION = 1

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cache: MutableList<ScheduledTask>? = null

    private val sinks = CopyOnWriteArrayList<ScheduleSink>()

    /** Test seams, same pattern as TaskRecordStore. */
    @VisibleForTesting
    @JvmStatic
    internal var storageDirOverride: File? = null

    @VisibleForTesting
    @JvmStatic
    internal var clock: () -> Long = { System.currentTimeMillis() }

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @VisibleForTesting
    @JvmStatic
    fun invalidate() {
        synchronized(lock) {
            cache = null
        }
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    /** Record a new scheduled task; false (and no write) on a duplicate id. */
    @JvmStatic
    fun schedule(task: ScheduledTask): Boolean {
        val added = synchronized(lock) {
            val tasks = loadLocked()
            if (tasks.any { it.id == task.id }) {
                XLog.w(TAG, "schedule: '${task.id}' already exists — refusing to overwrite")
                return false
            }
            tasks.add(task)
            persistLocked()
            true
        }
        if (added) notifyChanged(task)
        return added
    }

    /** Cancel a live task; false if unknown or already terminal. */
    @JvmStatic
    fun cancel(id: String): Boolean {
        val cancelled = synchronized(lock) {
            val tasks = loadLocked()
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx < 0) return false
            if (!tasks[idx].isLive) return false
            val next = tasks[idx].copy(status = ScheduledTask.Status.CANCELLED)
            tasks[idx] = next
            persistLocked()
            next
        }
        notifyChanged(cancelled)
        return true
    }

    /**
     * The task fired but another one is running: it waits its turn, visibly.
     * Returns the queued task or null if it was not live.
     */
    @JvmStatic
    fun markQueued(id: String): ScheduledTask? {
        val updated = mutateLive(id, from = ScheduledTask.Status.SCHEDULED) {
            it.copy(
                status = ScheduledTask.Status.QUEUED,
                lastOutcome = QUEUED_OUTCOME,
            )
        }
        if (updated != null) notifyChanged(updated)
        return updated
    }

    /**
     * The task actually started running. One-shots become FIRED (the ledger
     * record carries the outcome); repeats stay SCHEDULED with their next
     * occurrence computed from [now] — the caller re-arms the alarm.
     */
    @JvmStatic
    fun markFired(id: String): ScheduledTask? {
        val updated = mutateLive(id, from = null) {
            if (it.repeatIntervalMillis != null) {
                it.copy(
                    fireAtMillis = it.nextOccurrenceAfter(clock()),
                    lastFiredAt = clock(),
                )
            } else {
                it.copy(
                    status = ScheduledTask.Status.FIRED,
                    lastFiredAt = clock(),
                )
            }
        }
        if (updated != null) notifyChanged(updated)
        return updated
    }

    /** How the run ended — visible in list_scheduled_tasks and the task page. */
    @JvmStatic
    fun recordOutcome(id: String, outcome: String): ScheduledTask? {
        // synchronized 块内的 return null 是函数级返回，故 updated 必非空。
        val updated = synchronized(lock) {
            val tasks = loadLocked()
            val idx = tasks.indexOfFirst { it.id == id }
            if (idx < 0) return null
            val next = tasks[idx].copy(lastOutcome = outcome.take(300))
            tasks[idx] = next
            persistLocked()
            next
        }
        notifyChanged(updated)
        return updated
    }

    /**
     * After a reboot the alarms are gone and time has passed. Reconcile:
     * a repeating task re-aims at its next occurrence after [now]; a one-shot
     * whose moment passed is closed as MISSED, not fired late. Returns the
     * live tasks the caller must re-arm.
     */
    @JvmStatic
    fun reconcileAfterReboot(): List<ScheduledTask> {
        val (live, changed) = synchronized(lock) {
            val tasks = loadLocked()
            val now = clock()
            val moved = mutableListOf<ScheduledTask>()
            for (i in tasks.indices) {
                val task = tasks[i]
                if (!task.isLive || task.fireAtMillis > now) continue
                tasks[i] = if (task.repeatIntervalMillis != null) {
                    task.copy(fireAtMillis = task.nextOccurrenceAfter(now))
                } else {
                    XLog.i(TAG, "reconcile: '${task.id}' missed its window while the device was off")
                    task.copy(status = ScheduledTask.Status.MISSED, lastOutcome = MISSED_OUTCOME)
                }
                moved.add(tasks[i])
            }
            if (moved.isNotEmpty()) persistLocked()
            Pair(tasks.filter { it.isLive }, moved)
        }
        for (task in changed) notifyChanged(task)
        return live
    }

    /** What a queued task's outcome says — the queue's honest wording. */
    private const val QUEUED_OUTCOME =
        "queued: another task was running when this fired"

    private const val MISSED_OUTCOME =
        "missed: the device was off at the scheduled time"

    private inline fun mutateLive(
        id: String,
        from: ScheduledTask.Status?,
        transition: (ScheduledTask) -> ScheduledTask,
    ): ScheduledTask? = synchronized(lock) {
        val tasks = loadLocked()
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val current = tasks[idx]
        if (!current.isLive) return null
        if (from != null && current.status != from) return null
        val next = transition(current)
        tasks[idx] = next
        persistLocked()
        next
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** All tasks: live first (soonest first), then history newest-fired first. */
    @JvmStatic
    fun list(): List<ScheduledTask> = synchronized(lock) {
        val tasks = loadLocked()
        val live = tasks.filter { it.isLive }.sortedBy { it.fireAtMillis }
        val done = tasks.filter { !it.isLive }.sortedByDescending { it.lastFiredAt ?: it.createdAt }
        return live + done
    }

    @JvmStatic
    fun get(id: String): ScheduledTask? = synchronized(lock) {
        loadLocked().firstOrNull { it.id == id }
    }

    /** The queued task that has waited longest, if any. */
    @JvmStatic
    fun nextQueued(): ScheduledTask? = synchronized(lock) {
        loadLocked()
            .filter { it.status == ScheduledTask.Status.QUEUED }
            .minByOrNull { it.lastFiredAt ?: it.createdAt }
    }

    // ── Sinks ─────────────────────────────────────────────────────────────────

    @JvmStatic
    fun addSink(sink: ScheduleSink) {
        sinks.add(sink)
    }

    @JvmStatic
    fun removeSink(sink: ScheduleSink) {
        sinks.remove(sink)
    }

    /**
     * Tell the sinks the queue moved. Fired outside the lock (a sink may
     * re-enter [list]) and guarded per sink — one broken surface must not
     * break the write path that is mid-notify. Same contract as
     * [TaskRecordStore]'s notify.
     */
    private fun notifyChanged(task: ScheduledTask) {
        for (sink in sinks) {
            try {
                sink.onScheduleChanged(task)
            } catch (e: Exception) {
                XLog.e(TAG, "sink failed onScheduleChanged for '${task.id}'", e)
            }
        }
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    private fun loadLocked(): MutableList<ScheduledTask> {
        cache?.let { return it }
        val loaded = try {
            val file = storageFile()
            if (file.exists()) {
                val root = JsonParser.parseString(file.readText()).asJsonObject
                (root.getAsJsonArray("tasks") ?: JsonArray()).mapNotNull { el ->
                    if (el == null || el.isJsonNull || !el.isJsonObject) return@mapNotNull null
                    ScheduledTask.parse(el.asJsonObject)
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to read $FILE_NAME — starting from no scheduled tasks", e)
            emptyList()
        }
        cache = loaded.toMutableList()
        return cache!!
    }

    private fun persistLocked(): Boolean = try {
        val file = storageFile()
        val tmp = File(storageDir(), "$FILE_NAME.tmp")
        val root = JsonObject().apply {
            addProperty("version", FILE_VERSION)
            add("tasks", JsonArray().apply {
                for (task in cache ?: mutableListOf()) {
                    add(ScheduledTask.toJson(task))
                }
            })
        }
        tmp.writeText(gson.toJson(root))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
        true
    } catch (e: Exception) {
        XLog.e(TAG, "Failed to persist $FILE_NAME", e)
        false
    }

    private fun storageDir(): File {
        storageDirOverride?.let { dir ->
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
        val ctx = appContext
        val dir = ctx?.getExternalFilesDir(null) ?: ctx?.filesDir
            ?: throw IllegalStateException("ScheduledTaskStore.init(context) was never called")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun storageFile(): File = File(storageDir(), FILE_NAME)
}
