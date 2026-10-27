// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The scheduling half's durability and queue policy, on a real directory with
 * an injected clock. What is pinned:
 *
 * - the queue's honesty: a task that fires while another runs waits visibly
 *   (QUEUED), in arrival order, and only live tasks can move;
 * - the reboot honesty: a one-shot whose window passed while the device was
 *   off is closed as MISSED, a repeat re-aims at its next occurrence;
 * - the file round-trips.
 */
class ScheduledTaskStoreTest {

    private lateinit var dir: File
    private var now = 1_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("scheduled-tasks-test").toFile()
        ScheduledTaskStore.storageDirOverride = dir
        ScheduledTaskStore.clock = { now }
        ScheduledTaskStore.invalidate()
    }

    @After
    fun tearDown() {
        ScheduledTaskStore.storageDirOverride = null
        ScheduledTaskStore.clock = { System.currentTimeMillis() }
        ScheduledTaskStore.invalidate()
        dir.deleteRecursively()
    }

    private fun oneShot(id: String, fireAt: Long = now + 3_600_000) = ScheduledTask(
        id = id,
        taskText = "brief me",
        createdAt = now,
        fireAtMillis = fireAt,
    )

    private fun daily(id: String, firstFireAt: Long = now + 3_600_000) = ScheduledTask(
        id = id,
        taskText = "morning brief",
        createdAt = now,
        fireAtMillis = firstFireAt,
        repeatIntervalMillis = 24 * 3_600_000L,
        calendarAnchorHours = 14,
    )

    @Test
    fun `schedule then list round-trips through a cold reload`() {
        assertTrue(ScheduledTaskStore.schedule(oneShot("s1")))
        // Earlier fire than s1 so "soonest first" is a real ordering, not a tie.
        assertTrue(ScheduledTaskStore.schedule(daily("s2", firstFireAt = now + 1_800_000)))
        ScheduledTaskStore.invalidate()
        val reloaded = ScheduledTaskStore.list()
        assertEquals(listOf("s2", "s1"), reloaded.map { it.id }) // soonest first
        assertEquals(24 * 3_600_000L, reloaded.first { it.id == "s2" }.repeatIntervalMillis)
        assertEquals(14, reloaded.first { it.id == "s2" }.calendarAnchorHours)
    }

    @Test
    fun `duplicate ids are refused`() {
        assertTrue(ScheduledTaskStore.schedule(oneShot("s1")))
        assertFalse(ScheduledTaskStore.schedule(oneShot("s1", fireAt = now + 100)))
    }

    @Test
    fun `firing a one-shot closes it as FIRED and keeps the armed time untouched`() {
        ScheduledTaskStore.schedule(oneShot("s1"))
        now += 3_600_000
        val fired = ScheduledTaskStore.markFired("s1")!!
        assertEquals(ScheduledTask.Status.FIRED, fired.status)
        assertEquals(now, fired.lastFiredAt)
        assertFalse(ScheduledTaskStore.get("s1")!!.isLive)
    }

    @Test
    fun `firing a repeat advances to the next occurrence and stays SCHEDULED`() {
        ScheduledTaskStore.schedule(daily("s1"))
        now += 3_600_000 // first fire moment
        val advanced = ScheduledTaskStore.markFired("s1")!!
        assertEquals(ScheduledTask.Status.SCHEDULED, advanced.status)
        assertEquals(now + 24 * 3_600_000L, advanced.fireAtMillis)
        assertTrue(advanced.isLive)
    }

    @Test
    fun `a repeat skipped for days aims at the next occurrence, not a burst of catch-up fires`() {
        val first = now + 60_000
        ScheduledTaskStore.schedule(daily("s1", firstFireAt = first))
        now += 3 * 24 * 3_600_000L + 60_000 // three days and a minute late
        val advanced = ScheduledTaskStore.markFired("s1")!!
        // Next occurrence is within one interval of "now", not the first
        // missed slot three days ago.
        assertTrue(advanced.fireAtMillis > now)
        assertTrue(advanced.fireAtMillis <= now + 24 * 3_600_000L)
    }

    @Test
    fun `the queue holds visibly and drains in arrival order`() {
        ScheduledTaskStore.schedule(oneShot("first"))
        ScheduledTaskStore.schedule(oneShot("second"))
        now += 3_600_000
        // Both fire while a task is running.
        val queued1 = ScheduledTaskStore.markQueued("first")!!
        assertEquals(ScheduledTask.Status.QUEUED, queued1.status)
        assertTrue(queued1.lastOutcome!!.contains("queued"))
        now += 60_000
        ScheduledTaskStore.markQueued("second")

        // Longest-waiting first, and markFired accepts a queued task.
        assertEquals("first", ScheduledTaskStore.nextQueued()!!.id)
        val fired = ScheduledTaskStore.markFired("first")!!
        assertEquals(ScheduledTask.Status.FIRED, fired.status)
        assertEquals("second", ScheduledTaskStore.nextQueued()!!.id)
    }

    @Test
    fun `markQueued only applies to a SCHEDULED task`() {
        ScheduledTaskStore.schedule(oneShot("s1"))
        ScheduledTaskStore.markFired("s1") // one-shot now terminal
        assertNull(ScheduledTaskStore.markQueued("s1"))
        assertNull(ScheduledTaskStore.nextQueued())
    }

    @Test
    fun `cancel stops a live task and refuses a finished one`() {
        ScheduledTaskStore.schedule(oneShot("s1"))
        ScheduledTaskStore.schedule(oneShot("s2"))
        ScheduledTaskStore.markFired("s2")
        assertTrue(ScheduledTaskStore.cancel("s1"))
        assertEquals(ScheduledTask.Status.CANCELLED, ScheduledTaskStore.get("s1")!!.status)
        assertFalse(ScheduledTaskStore.cancel("s1"))
        assertFalse(ScheduledTaskStore.cancel("s2"))
        assertFalse(ScheduledTaskStore.cancel("ghost"))
    }

    @Test
    fun `reconcile after a reboot misses a one-shot and re-aims a repeat`() {
        ScheduledTaskStore.schedule(oneShot("once", fireAt = now + 60_000))
        ScheduledTaskStore.schedule(daily("daily", firstFireAt = now + 60_000))
        ScheduledTaskStore.schedule(oneShot("future", fireAt = now + 10 * 24 * 3_600_000L))

        now += 8 * 3_600_000 // device was off for 8 hours

        val live = ScheduledTaskStore.reconcileAfterReboot()
        // The one-shot whose moment passed while off is closed as missed.
        val missed = ScheduledTaskStore.get("once")!!
        assertEquals(ScheduledTask.Status.MISSED, missed.status)
        assertTrue(missed.lastOutcome!!.contains("missed"))
        // The repeat re-aims inside its next interval.
        val repeat = ScheduledTaskStore.get("daily")!!
        assertEquals(ScheduledTask.Status.SCHEDULED, repeat.status)
        assertTrue(repeat.fireAtMillis > now)
        assertTrue(repeat.fireAtMillis <= now + 24 * 3_600_000L)
        // The future one-shot is untouched.
        val future = ScheduledTaskStore.get("future")!!
        assertEquals(now + 10 * 24 * 3_600_000L - 8 * 3_600_000L, future.fireAtMillis)
        assertEquals(setOf("daily", "future"), live.map { it.id }.toSet())

        // And the reconcile persisted: a cold reload sees the same story.
        ScheduledTaskStore.invalidate()
        assertEquals(ScheduledTask.Status.MISSED, ScheduledTaskStore.get("once")!!.status)
    }

    @Test
    fun `list puts history after live with outcomes`() {
        ScheduledTaskStore.schedule(oneShot("done"))
        ScheduledTaskStore.schedule(daily("live"))
        ScheduledTaskStore.markFired("done")
        ScheduledTaskStore.recordOutcome("done", "completed: brief sent")
        val list = ScheduledTaskStore.list()
        assertEquals("live", list.first().id)
        assertEquals("done", list.last().id)
        assertEquals("completed: brief sent", list.last().lastOutcome)
    }

    @Test
    fun `recordOutcome caps what it stores`() {
        ScheduledTaskStore.schedule(oneShot("s1"))
        ScheduledTaskStore.recordOutcome("s1", "x".repeat(1000))
        assertEquals(300, ScheduledTaskStore.get("s1")!!.lastOutcome!!.length)
        assertNotNull(ScheduledTaskStore.get("s1"))
    }
}
