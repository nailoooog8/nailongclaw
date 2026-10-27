// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl

import io.agents.nailongclaw.task.ScheduledTask
import io.agents.nailongclaw.task.ScheduledTaskStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The scheduling trio's honest edges — validation refusals that tell the
 * model what to do instead of failing opaquely, and the id round-trip the
 * cancel flow depends on. Storage is the real store on a temp dir; the alarm
 * wiring is a recording no-op.
 */
class SchedulingToolsTest {

    private lateinit var dir: File
    private var now = 1_777_000_000_000L
    private val armed = mutableListOf<ScheduledTask>()
    private val disarmed = mutableListOf<String>()

    private val scheduleTool = ScheduleTaskTool(
        clock = { now },
        arm = { armed.add(it) },
    )
    private val cancelTool = CancelScheduledTaskTool(disarm = { disarmed.add(it) })
    private val listTool = ListScheduledTasksTool(clock = { now })

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sched-tools-test").toFile()
        ScheduledTaskStore.storageDirOverride = dir
        ScheduledTaskStore.clock = { now }
        ScheduledTaskStore.invalidate()
        armed.clear()
        disarmed.clear()
    }

    @After
    fun tearDown() {
        ScheduledTaskStore.storageDirOverride = null
        ScheduledTaskStore.clock = { System.currentTimeMillis() }
        ScheduledTaskStore.invalidate()
        dir.deleteRecursively()
    }

    private fun lastScheduledId(): String = ScheduledTaskStore.list().first().id

    @Test
    fun `a valid schedule lands armed with the exact fire time and honest caveats`() {
        val result = scheduleTool.execute(
            mapOf("task" to "daily calendar brief", "delay_minutes" to 90)
        )
        assertTrue(result.isSuccess)
        val stored = ScheduledTaskStore.list().single()
        assertEquals(now + 90 * 60_000L, stored.fireAtMillis)
        assertEquals(stored.id, armed.single().id)
        // The caveats are the contract: cancel needs the id, busy means queue.
        assertTrue(result.data!!.contains(stored.id))
        assertTrue(result.data!!.contains("queues visibly"))
        assertTrue(result.data!!.contains("missed"))
    }

    @Test
    fun `delay and at_millis are mutually exclusive and one is required`() {
        val neither = scheduleTool.execute(mapOf("task" to "x"))
        assertFalse(neither.isSuccess)
        assertTrue(neither.error!!.contains("exactly one"))

        val both = scheduleTool.execute(
            mapOf("task" to "x", "delay_minutes" to 5, "at_millis" to now + 999)
        )
        assertFalse(both.isSuccess)
        assertTrue(both.error!!.contains("exactly one"))
    }

    @Test
    fun `a time in the past is refused with the right alternative`() {
        val past = scheduleTool.execute(
            mapOf("task" to "x", "at_millis" to now - 60_000)
        )
        assertFalse(past.isSuccess)
        assertTrue(past.error!!.contains("past"))
    }

    @Test
    fun `repeat and anchor ride along and repeat has a floor`() {
        val ok = scheduleTool.execute(
            mapOf(
                "task" to "brief",
                "at_millis" to now + 24 * 3_600_000L,
                "repeat_minutes" to 1440,
                "calendar_anchor_hours" to 12,
            )
        )
        assertTrue(ok.isSuccess)
        val stored = ScheduledTaskStore.list().single()
        assertEquals(1440 * 60_000L, stored.repeatIntervalMillis)
        assertEquals(12, stored.calendarAnchorHours)

        val tooFast = scheduleTool.execute(
            mapOf("task" to "x", "delay_minutes" to 10, "repeat_minutes" to 1)
        )
        assertFalse(tooFast.isSuccess)
        assertTrue(tooFast.error!!.contains("at least 15"))
    }

    @Test
    fun `cancel by id disarms and unknown ids list the live ones`() {
        scheduleTool.execute(mapOf("task" to "one", "delay_minutes" to 30))
        scheduleTool.execute(mapOf("task" to "two", "delay_minutes" to 40))
        val id1 = ScheduledTaskStore.list().sortedBy { it.createdAt }.first().id

        val cancelled = cancelTool.execute(mapOf("id" to id1))
        assertTrue(cancelled.isSuccess)
        assertEquals(listOf(id1), disarmed)
        assertEquals(1, ScheduledTaskStore.list().count { it.isLive })

        val unknown = cancelTool.execute(mapOf("id" to "sched_nope"))
        assertFalse(unknown.isSuccess)
        val liveId = ScheduledTaskStore.list().first { it.isLive }.id
        assertTrue(unknown.error!!.contains(liveId))
    }

    @Test
    fun `list reports live fires and honest terminal outcomes`() {
        scheduleTool.execute(mapOf("task" to "live one", "delay_minutes" to 30))
        scheduleTool.execute(mapOf("task" to "done one", "delay_minutes" to 1))
        val doneId = ScheduledTaskStore.list().first { it.taskText == "done one" }.id
        now += 60_000
        ScheduledTaskStore.markQueued(doneId)

        val result = listTool.execute(mapOf())
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("[SCHEDULED]"))
        assertTrue(result.data!!.contains("[QUEUED]"))
        assertTrue(result.data!!.contains("waiting for the running task"))
        assertTrue(result.data!!.contains("live one"))
    }

    @Test
    fun `an empty schedule says so instead of an empty string`() {
        val result = listTool.execute(mapOf())
        assertTrue(result.isSuccess)
        assertTrue(result.data!!.contains("No scheduled tasks"))
    }
}
