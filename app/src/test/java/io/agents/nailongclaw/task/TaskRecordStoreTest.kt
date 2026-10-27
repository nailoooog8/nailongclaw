// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.agents.nailongclaw.TaskEvent
import io.agents.nailongclaw.channel.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Durability contract of the ledger, on a real directory with an injected
 * clock — the MemoryStore test pattern. What is pinned here:
 *
 * - the on-disk format round-trips (the chat transcript's save/load
 *   asymmetry is the standing warning — a format whose reader and writer
 *   drift apart is a silent data-loss bug);
 * - a crash-leftover RUNNING record is closed honestly, not left claiming
 *   to run forever;
 * - growth is bounded and says so when it truncates.
 */
class TaskRecordStoreTest {

    private lateinit var dir: File
    private var now = 1_000_000L

    private val added = mutableListOf<TaskRecord>()
    private val updated = mutableListOf<TaskRecord>()
    private val sink = object : TaskSink {
        override fun onTaskRecordAdded(record: TaskRecord) { added.add(record) }
        override fun onTaskRecordUpdated(record: TaskRecord) { updated.add(record) }
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("task-ledger-test").toFile()
        TaskRecordStore.storageDirOverride = dir
        TaskRecordStore.clock = { now }
        TaskRecordStore.invalidate()
        added.clear()
        updated.clear()
        TaskRecordStore.addSink(sink)
    }

    @After
    fun tearDown() {
        TaskRecordStore.removeSink(sink)
        TaskRecordStore.storageDirOverride = null
        TaskRecordStore.clock = { System.currentTimeMillis() }
        TaskRecordStore.invalidate()
        dir.deleteRecursively()
    }

    private fun startLocal(id: String, text: String = "do a thing", origin: String? = "SomeActivity") =
        TaskRecordStore.start(id, text, Channel.LOCAL, origin)

    @Test
    fun `a task lifecycle lands in the ledger with steps and terminal payload`() {
        assertNotNull(startLocal("t1", "message Mom"))
        now += 10

        TaskRecordStore.onEvent("t1", TaskEvent.ToolAction("Send Message"))
        now += 10
        TaskRecordStore.onEvent("t1", TaskEvent.ToolResult("Send Message", true, "sent to Mom"))
        now += 10
        TaskRecordStore.onEvent("t1", TaskEvent.Progress(2, "Step 2/2: confirming"))
        now += 10
        TaskRecordStore.onEvent("t1", TaskEvent.Completed("messaged Mom", "gpt-test"))

        val record = TaskRecordStore.get("t1")!!
        assertEquals(TaskRecord.Status.COMPLETED, record.status)
        assertEquals("messaged Mom", record.answer)
        assertNull(record.error)
        assertTrue(record.isTerminal)
        // ToolAction is not step material (action/result are not paired);
        // one TOOL step and one PROGRESS step remain.
        assertEquals(2, record.steps.size)
        assertEquals(TaskRecord.StepKind.TOOL, record.steps[0].kind)
        assertEquals(TaskRecord.StepKind.PROGRESS, record.steps[1].kind)
        // Duration is createdAt→terminal updatedAt of the ledger, not wall
        // clock: four clock ticks of 10 (the ToolAction tick touches nothing).
        assertEquals(40L, record.durationMillis)
        // The task page reads provenance from the record itself.
        assertEquals(Channel.LOCAL, record.channel)
        assertEquals("SomeActivity", record.originActivity)

        assertEquals("t1", added.single().id)
        assertTrue(updated.any { it.id == "t1" && it.status == TaskRecord.Status.COMPLETED })
    }

    @Test
    fun `active returns the running record and clears once terminal`() {
        startLocal("t1")
        assertEquals("t1", TaskRecordStore.active()?.id)
        TaskRecordStore.onEvent("t1", TaskEvent.Cancelled)
        assertNull(TaskRecordStore.active())
    }

    @Test
    fun `failed and blocked carry their honest payloads`() {
        startLocal("f1")
        TaskRecordStore.onEvent("f1", TaskEvent.Failed("Accessibility is not running"))
        assertEquals(TaskRecord.Status.FAILED, TaskRecordStore.get("f1")!!.status)
        assertEquals("Accessibility is not running", TaskRecordStore.get("f1")!!.error)

        startLocal("b1")
        TaskRecordStore.onEvent("b1", TaskEvent.Blocked)
        val blocked = TaskRecordStore.get("b1")!!
        assertEquals(TaskRecord.Status.BLOCKED, blocked.status)
        assertNotNull(blocked.error)
    }

    @Test
    fun `duplicate ids are refused instead of overwritten`() {
        assertNotNull(startLocal("t1", "first"))
        assertNull(startLocal("t1", "second"))
        assertEquals("first", TaskRecordStore.get("t1")!!.taskText)
    }

    @Test
    fun `events for unknown records are dropped`() {
        assertNull(TaskRecordStore.onEvent("ghost", TaskEvent.Completed("done")))
    }

    @Test
    fun `a finished record absorbs the duplicate terminal event`() {
        startLocal("t1")
        TaskRecordStore.onEvent("t1", TaskEvent.Completed("done"))
        assertNull(TaskRecordStore.onEvent("t1", TaskEvent.Failed("late watchdog")))
        val record = TaskRecordStore.get("t1")!!
        assertEquals(TaskRecord.Status.COMPLETED, record.status)
        assertEquals("done", record.answer)
        assertNull(record.error)
    }

    @Test
    fun `the file format round-trips through a cold reload`() {
        startLocal("t1", "round trip")
        TaskRecordStore.onEvent("t1", TaskEvent.ToolResult("Take Screenshot", false, "permission denied"))
        TaskRecordStore.onEvent("t1", TaskEvent.Failed("no screen"))

        val before = TaskRecordStore.get("t1")!!
        TaskRecordStore.invalidate()
        val after = TaskRecordStore.get("t1")!!

        assertEquals(before, after)
        assertTrue(dir.resolve("task_records.json").exists())
    }

    @Test
    fun `a RUNNING record left by a dead process is closed as interrupted`() {
        // Simulate the file a previous process left behind: RUNNING, nobody
        // driving it. Written by hand so this test reads the real format.
        val record = TaskRecord(
            id = "crashed",
            taskText = "half done",
            channel = Channel.TELEGRAM,
            originActivity = null,
            createdAt = now,
            updatedAt = now,
        )
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("records", JsonArray().apply { add(TaskRecord.toJson(record)) })
        }
        dir.resolve("task_records.json").writeText(Gson().toJson(root))

        val loaded = TaskRecordStore.list().single()
        assertEquals(TaskRecord.Status.FAILED, loaded.status)
        assertTrue(loaded.error!!.contains("Interrupted"))
        // And the closure is persisted, not just observed.
        TaskRecordStore.invalidate()
        assertTrue(TaskRecordStore.list().single().error!!.contains("Interrupted"))
    }

    @Test
    fun `records unreadable in identity fields are skipped not guessed at`() {
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("records", JsonArray().apply {
                // Unknown channel: keeping it would fabricate provenance.
                add(JsonObject().apply {
                    addProperty("id", "future-channel")
                    addProperty("taskText", "x")
                    addProperty("channel", "SMS")
                    addProperty("createdAt", 1L)
                    addProperty("updatedAt", 1L)
                    addProperty("status", "COMPLETED")
                })
                // No status: malformed.
                add(JsonObject().apply {
                    addProperty("id", "no-status")
                    addProperty("taskText", "x")
                    addProperty("channel", "LOCAL")
                    addProperty("createdAt", 1L)
                    addProperty("updatedAt", 1L)
                })
            })
        }
        dir.resolve("task_records.json").writeText(Gson().toJson(root))
        assertTrue(TaskRecordStore.list().isEmpty())
    }

    @Test
    fun `a scheduled task's channel round-trips instead of dropping the record`() {
        // The alarm fires as Channel.SCHEDULED now. If the ledger could not
        // read that name back it would skip the record under the
        // unreadable-channel rule above, and every scheduled task would
        // vanish from the task page — the one place its outcome is visible.
        assertNotNull(TaskRecordStore.start("s1", "morning brief", Channel.SCHEDULED, null))
        TaskRecordStore.onEvent("s1", TaskEvent.Completed("2 meetings today"))

        assertEquals(Channel.SCHEDULED, TaskRecordStore.get("s1")!!.channel)
        TaskRecordStore.invalidate()
        val reloaded = TaskRecordStore.list().single()
        assertEquals("s1", reloaded.id)
        assertEquals(Channel.SCHEDULED, reloaded.channel)
        // No screen started it, and the page shows that honestly.
        assertNull(reloaded.originActivity)
    }

    @Test
    fun `the ledger trims to the cap but never evicts a live record`() {
        // MAX+4 tasks: the first four are terminal and oldest; the last one
        // is still running — eviction must take history, never the live task.
        for (i in 0 until TaskRecordStore.MAX_RECORDS + 4) {
            startLocal("t$i", text = "task $i")
            now += 10
            if (i < 4) {
                TaskRecordStore.onEvent("t$i", TaskEvent.Completed("done $i"))
            }
        }
        val list = TaskRecordStore.list()
        assertEquals(TaskRecordStore.MAX_RECORDS, list.size)
        assertNull("oldest terminal record should be gone", TaskRecordStore.get("t0"))
        assertNotNull("the live record must survive the trim", TaskRecordStore.get("t${TaskRecordStore.MAX_RECORDS + 3}"))
    }

    @Test
    fun `steps are capped at the newest MAX_STEPS and the record says so`() {
        startLocal("t1")
        for (i in 1..TaskRecord.MAX_STEPS + 5) {
            now += 1
            TaskRecordStore.onEvent("t1", TaskEvent.ToolResult("Tool $i", true, "step $i"))
        }
        val record = TaskRecordStore.get("t1")!!
        assertEquals(TaskRecord.MAX_STEPS, record.steps.size)
        assertTrue(record.stepsTruncated)
        // Newest kept, oldest dropped.
        assertEquals("step ${TaskRecord.MAX_STEPS + 5}", record.steps.last().text)
        assertEquals("step 6", record.steps.first().text)
    }

    @Test
    fun `list is newest first`() {
        startLocal("old"); now += 100
        startLocal("mid"); now += 100
        startLocal("new")
        assertEquals(listOf("new", "mid", "old"), TaskRecordStore.list().map { it.id })
    }

    // ---- WAITING_CONFIRM (M2) ----

    @Test
    fun `a confirmation parks the active task and its resolution resumes it`() {
        startLocal("t1")
        TaskRecordStore.noteWaitingConfirm("echo")
        val parked = TaskRecordStore.get("t1")!!
        assertEquals(TaskRecord.Status.WAITING_CONFIRM, parked.status)
        assertEquals(TaskRecord.StepKind.NOTE, parked.steps.last().kind)
        assertTrue(parked.steps.last().text.contains("echo"))

        TaskRecordStore.noteConfirmResolved("ALLOW_ONCE")
        val resumed = TaskRecordStore.get("t1")!!
        assertEquals(TaskRecord.Status.RUNNING, resumed.status)
        assertTrue(resumed.steps.last().text.contains("ALLOW_ONCE"))
    }

    @Test
    fun `waiting notes are no-ops without an active or parked record`() {
        // Remote-channel tasks (M1 boundary) never create a record — the
        // notes must not invent one.
        TaskRecordStore.noteWaitingConfirm("echo")
        assertTrue(TaskRecordStore.list().isEmpty())
        TaskRecordStore.noteConfirmResolved("DENY_BY_USER")
        assertTrue(TaskRecordStore.list().isEmpty())
    }

    @Test
    fun `a task that died while waiting stays terminal after the decision lands`() {
        startLocal("t1")
        TaskRecordStore.noteWaitingConfirm("echo")
        // Watchdog fires while the card is up: the terminal event wins.
        TaskRecordStore.onEvent("t1", TaskEvent.Failed("Task stalled (inactivity timeout)"))
        TaskRecordStore.noteConfirmResolved("DENY_TIMED_OUT")
        val record = TaskRecordStore.get("t1")!!
        assertEquals(TaskRecord.Status.FAILED, record.status)
        assertEquals("Task stalled (inactivity timeout)", record.error)
    }

    @Test
    fun `a WAITING_CONFIRM leftover from a dead process is closed as interrupted`() {
        val record = TaskRecord(
            id = "parked-crash",
            taskText = "waiting forever",
            channel = Channel.LOCAL,
            originActivity = null,
            createdAt = now,
            updatedAt = now,
            status = TaskRecord.Status.WAITING_CONFIRM,
        )
        val root = JsonObject().apply {
            addProperty("version", 1)
            add("records", JsonArray().apply { add(TaskRecord.toJson(record)) })
        }
        dir.resolve("task_records.json").writeText(Gson().toJson(root))

        assertEquals(TaskRecord.Status.FAILED, TaskRecordStore.list().single().status)
    }
}
