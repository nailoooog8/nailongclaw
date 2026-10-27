// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.task

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.utils.XLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The durable task ledger — every task the app accepted, on disk.
 *
 * [io.agents.pokeclaw.TaskSessionStore] is the volatile single slot for "what
 * is running now"; this store is its memory. The task page reads only from
 * here, so a task's record outlives its Activity, its process, and the
 * session that ran it.
 *
 * ## Write path (M1 scope)
 *
 * Two halves, deliberately split by what each can see:
 *
 * - **Opening** happens at the entry choke point,
 *   [io.agents.pokeclaw.AppViewModel.startTask], *before* any gate runs. The
 *   user is told the task started the moment that is called, so the record
 *   must already exist when something refuses it — a task rejected by the
 *   LLM-config check, an `onBeforeTask` throw, or a task-lock race would
 *   otherwise run to completion with no trace here.
 * - **Folding** happens in [io.agents.pokeclaw.TaskOrchestrator], which is the
 *   one place every task passes through, so channel tasks (Telegram/Discord/
 *   WeChat/QQ/Douyin) are recorded like tasks typed into the app. It adopts
 *   the record the entry point opened rather than creating a second one, and
 *   creates one itself only for callers that reach `startNewTask` directly
 *   ([io.agents.pokeclaw.channel.ChannelSetup] among them).
 *
 * One event stream, two ledgers: the chat transcript for the conversation,
 * this file for the task page. The one path that keeps its own write is the
 * UI's direct-tool task, which runs a tool without the orchestrator at all.
 *
 * ## Durability rules
 *
 * - Atomic writes: tmp file + rename, with a copy fallback (MemoryStore's
 *   pattern — same adb-pullable directory, `getExternalFilesDir`).
 * - Crash honesty: a record still RUNNING when this process reads the file
 *   cannot be running — the session store is in-memory only, so a fresh
 *   process has no live tasks. It is marked FAILED("Interrupted…") rather
 *   than left claiming to run forever.
 * - Bounded growth: newest [MAX_RECORDS] records, newest [TaskRecord.MAX_STEPS]
 *   steps per record, with [TaskRecord.stepsTruncated] saying so.
 *
 * ## Threading
 *
 * All reads/writes are synchronized on one lock; [TaskSink] notifications
 * fire outside it and may arrive on any thread — sinks marshal themselves.
 */
object TaskRecordStore {

    private const val TAG = "TaskRecordStore"
    private const val FILE_NAME = "task_records.json"
    private const val FILE_VERSION = 1

    /** Hard cap on stored records; the page never needs more history than this. */
    const val MAX_RECORDS = 200

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cache: MutableList<TaskRecord>? = null

    /** Id of the record this process started and has not yet seen terminate. */
    @Volatile
    private var activeRecordId: String? = null

    private val sinks = CopyOnWriteArrayList<TaskSink>()

    // ── Test seams (MemoryStore's pattern: overridable reads, no subclass) ────

    /** Overrides the directory the ledger file lives in. Test-only. */
    @VisibleForTesting
    @JvmStatic
    internal var storageDirOverride: File? = null

    /** Overrides every clock read. Test-only. */
    @VisibleForTesting
    @JvmStatic
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called from ClawApplication.onCreate, next to MemoryStore.init. */
    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Drops the in-memory cache; the next read re-parses the file. Test-only. */
    @VisibleForTesting
    @JvmStatic
    fun invalidate() {
        synchronized(lock) {
            cache = null
        }
    }

    // ── Sinks ─────────────────────────────────────────────────────────────────

    @JvmStatic
    fun addSink(sink: TaskSink) {
        sinks.add(sink)
    }

    @JvmStatic
    fun removeSink(sink: TaskSink) {
        sinks.remove(sink)
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Record that a task was accepted. Returns the stored record, or null if
     * [id] is already taken (ids are minted per task; a collision is a bug).
     */
    @JvmStatic
    fun start(
        id: String,
        taskText: String,
        channel: Channel,
        originActivity: String?,
    ): TaskRecord? {
        val record = synchronized(lock) {
            val records = loadLocked()
            if (records.any { it.id == id }) {
                XLog.w(TAG, "start: record '$id' already exists — refusing to overwrite")
                return null
            }
            val now = clock()
            val fresh = TaskRecord(
                id = id,
                taskText = taskText,
                channel = channel,
                originActivity = originActivity,
                createdAt = now,
                updatedAt = now,
            )
            records.add(fresh)
            activeRecordId = id
            trimLocked(records)
            persistLocked()
            fresh
        }
        notifyAdded(record)
        return record
    }

    /**
     * Fold one [TaskEvent] into the record — the ledger half of the
     * dual-write. Applies the state machine, appends any step the event
     * contributes, persists, and returns the updated record (null when
     * [recordId] is unknown or the event changes nothing).
     */
    @JvmStatic
    fun onEvent(recordId: String, event: TaskEvent): TaskRecord? {
        val updated = synchronized(lock) {
            val records = loadLocked()
            val idx = records.indexOfFirst { it.id == recordId }
            if (idx < 0) return null
            val current = records[idx]
            val nextStatus = TaskStateMachine.nextStatus(current.status, event)
            val step = TaskStateMachine.stepFor(event, clock())
            if (nextStatus == null && step == null) return null

            var next = current
            if (step != null) next = appendStepLocked(next, step)
            if (nextStatus != null) {
                next = next.copy(
                    status = nextStatus,
                    updatedAt = clock(),
                    answer = (event as? TaskEvent.Completed)?.answer ?: next.answer,
                    error = when (event) {
                        is TaskEvent.Failed -> event.error
                        TaskEvent.Blocked -> BLOCKED_ERROR
                        else -> next.error
                    },
                )
            }
            if (next == current) return null
            records[idx] = next
            if (next.isTerminal && activeRecordId == recordId) {
                activeRecordId = null
            }
            persistLocked()
            next
        }
        updated?.let { notifyUpdated(it) }
        return updated
    }

    /** What a BLOCKED terminal event says in the ledger. */
    private const val BLOCKED_ERROR = "Blocked by a system dialog."

    /**
     * The active task parked on a human decision — an MCP confirmation card
     * just appeared. RUNNING → WAITING_CONFIRM plus a NOTE step, so the task
     * page can show the six-card "waiting for you" state. Best-effort by
     * contract: never throws, never invents a record — if nothing is active
     * it is a no-op.
     */
    @JvmStatic
    fun noteWaitingConfirm(toolName: String) {
        transitionActive("noteWaitingConfirm($toolName)") { current ->
            if (current.status != TaskRecord.Status.RUNNING) return@transitionActive null
            current.copy(
                status = TaskRecord.Status.WAITING_CONFIRM,
                updatedAt = clock(),
                steps = current.steps + TaskRecord.Step(
                    at = clock(),
                    kind = TaskRecord.StepKind.NOTE,
                    text = "⏳ Waiting for user confirmation: $toolName",
                ),
            )
        }
    }

    /**
     * The decision landed (allowed once, denied, timed out, …). Back to
     * RUNNING with a NOTE step recording the outcome. A task that went
     * terminal while waiting stays terminal — the decision no longer matters.
     */
    @JvmStatic
    fun noteConfirmResolved(outcome: String) {
        transitionActive("noteConfirmResolved($outcome)") { current ->
            if (current.status != TaskRecord.Status.WAITING_CONFIRM) return@transitionActive null
            current.copy(
                status = TaskRecord.Status.RUNNING,
                updatedAt = clock(),
                steps = current.steps + TaskRecord.Step(
                    at = clock(),
                    kind = TaskRecord.StepKind.NOTE,
                    text = "Confirmation $outcome",
                ),
            )
        }
    }

    /**
     * Shared body of the confirmation notes: apply [transition] to the active
     * record if there is one, persist and notify when it produced a change.
     */
    private fun transitionActive(
        what: String,
        transition: (TaskRecord) -> TaskRecord?,
    ) {
        val updated = try {
            synchronized(lock) {
                val id = activeRecordId ?: return
                val records = loadLocked()
                val idx = records.indexOfFirst { it.id == id }
                if (idx < 0) return
                val next = transition(records[idx]) ?: return
                records[idx] = next
                persistLocked()
                next
            }
        } catch (e: Exception) {
            // Observability only: a ledger hiccup must never break the
            // confirmation flow that is currently blocking the agent thread.
            XLog.w(TAG, "$what failed", e)
            return
        }
        notifyUpdated(updated)
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** All records, newest first. This is what the task page lists. */
    @JvmStatic
    fun list(): List<TaskRecord> = synchronized(lock) {
        loadLocked().sortedByDescending { it.createdAt }
    }

    @JvmStatic
    fun get(id: String): TaskRecord? = synchronized(lock) {
        loadLocked().firstOrNull { it.id == id }
    }

    /** The record this process is still driving, if any. */
    @JvmStatic
    fun active(): TaskRecord? {
        val id = activeRecordId ?: return null
        return synchronized(lock) { loadLocked().firstOrNull { it.id == id } }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun appendStepLocked(record: TaskRecord, step: TaskRecord.Step): TaskRecord {
        val combined = record.steps + step
        val kept = if (combined.size > TaskRecord.MAX_STEPS) {
            XLog.i(
                TAG,
                "record '${record.id}' hit the ${TaskRecord.MAX_STEPS}-step cap — " +
                    "dropping ${combined.size - TaskRecord.MAX_STEPS} oldest steps"
            )
            combined.takeLast(TaskRecord.MAX_STEPS)
        } else {
            combined
        }
        return record.copy(
            steps = kept,
            stepsTruncated = record.stepsTruncated || (kept.size < combined.size),
            updatedAt = clock(),
        )
    }

    /**
     * Keep the newest [MAX_RECORDS] records. A record that has not terminated
     * is always kept — evicting a live record would erase the one task the
     * user is currently watching from its own page. (At most a couple can
     * exist: one task runs at a time.)
     */
    private fun trimLocked(records: MutableList<TaskRecord>) {
        if (records.size <= MAX_RECORDS) return
        val keep = records
            .sortedWith(
                compareByDescending<TaskRecord> { !it.isTerminal }
                    .thenByDescending { it.createdAt }
            )
            .take(MAX_RECORDS)
        val dropped = records.size - keep.size
        records.clear()
        records.addAll(keep)
        XLog.i(TAG, "trim: ledger over $MAX_RECORDS — dropped $dropped oldest terminal records")
    }

    private fun loadLocked(): MutableList<TaskRecord> {
        cache?.let { return it }
        val (loaded, orphansMarked) = readFromDisk()
        val asList = loaded.toMutableList()
        cache = asList
        if (orphansMarked) persistLocked()
        return asList
    }

    /**
     * Parse the file. Every record still RUNNING that this process did not
     * start is a crash leftover — the session store is in-memory only, so no
     * task can survive a process restart — and is closed out as FAILED
     * ("Interrupted") instead of claiming to run forever.
     */
    private fun readFromDisk(): Pair<List<TaskRecord>, Boolean> {
        return try {
            val file = storageFile()
            if (!file.exists()) return Pair(emptyList(), false)
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val version = root.get("version")?.takeIf { !it.isJsonNull }?.asInt ?: FILE_VERSION
            if (version > FILE_VERSION) {
                XLog.w(TAG, "ledger file version $version is newer than $FILE_VERSION — trying to read it anyway")
            }
            val arr = root.getAsJsonArray("records") ?: return Pair(emptyList(), false)
            var marked = false
            val records = arr.mapNotNull { el ->
                if (el == null || el.isJsonNull || !el.isJsonObject) return@mapNotNull null
                val parsed = TaskRecord.parse(el.asJsonObject) ?: return@mapNotNull null
                // RUNNING or WAITING_CONFIRM: both claim a live task, and
                // neither can survive the process that owned it.
                if (!parsed.isTerminal && parsed.id != activeRecordId) {
                    marked = true
                    parsed.copy(
                        status = TaskRecord.Status.FAILED,
                        error = INTERRUPTED_ERROR,
                        updatedAt = clock(),
                    )
                } else {
                    parsed
                }
            }
            Pair(records, marked)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to read $FILE_NAME — starting from an empty ledger", e)
            Pair(emptyList(), false)
        }
    }

    /** What a crash-leftover RUNNING record is closed out with. */
    private const val INTERRUPTED_ERROR =
        "Interrupted: the app stopped before this task finished."

    private fun persistLocked(): Boolean {
        return try {
            val file = storageFile()
            val tmp = File(storageDir(), "$FILE_NAME.tmp")
            val root = JsonObject().apply {
                addProperty("version", FILE_VERSION)
                add("records", JsonArray().apply {
                    for (record in cache ?: mutableListOf()) {
                        add(TaskRecord.toJson(record))
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
    }

    private fun storageDir(): File {
        storageDirOverride?.let { dir ->
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
        val ctx = appContext
        val dir = ctx?.getExternalFilesDir(null) ?: ctx?.filesDir
            ?: throw IllegalStateException("TaskRecordStore.init(context) was never called")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun storageFile(): File = File(storageDir(), FILE_NAME)

    private fun notifyAdded(record: TaskRecord) {
        for (sink in sinks) {
            try {
                sink.onTaskRecordAdded(record)
            } catch (e: Exception) {
                XLog.e(TAG, "sink failed onTaskRecordAdded for '${record.id}'", e)
            }
        }
    }

    private fun notifyUpdated(record: TaskRecord) {
        for (sink in sinks) {
            try {
                sink.onTaskRecordUpdated(record)
            } catch (e: Exception) {
                XLog.e(TAG, "sink failed onTaskRecordUpdated for '${record.id}'", e)
            }
        }
    }
}
