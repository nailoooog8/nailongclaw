// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The queue's doorbell contract, pinned: every state-changing write rings
 * the [ScheduleSink]s with the *post-transition* task, and every refused
 * write rings nothing. The schedule page is built on this — a silent write
 * point is a page that shows a stale queue until the user pulls to refresh,
 * and a phantom ring is a page that redraws for no reason.
 *
 * Runs against a real store on a temp directory with an injected clock, the
 * MemoryStore/TaskRecordStoreTest pattern.
 */
class ScheduledTaskStoreSinkTest {

    private lateinit var dir: File
    private var now = 1_700_000_000_000L

    private val rings = mutableListOf<ScheduledTask>()
    private val sink = object : ScheduleSink {
        override fun onScheduleChanged(task: ScheduledTask) {
            rings.add(task)
        }
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("schedule-sink-test").toFile()
        ScheduledTaskStore.storageDirOverride = dir
        ScheduledTaskStore.clock = { now }
        ScheduledTaskStore.invalidate()
        ScheduledTaskStore.addSink(sink)
    }

    @After
    fun tearDown() {
        ScheduledTaskStore.removeSink(sink)
        ScheduledTaskStore.storageDirOverride = null
        ScheduledTaskStore.clock = { System.currentTimeMillis() }
        ScheduledTaskStore.invalidate()
        dir.deleteRecursively()
    }

    private fun arm(id: String, fireInMinutes: Long = 60, repeatMinutes: Long? = null): ScheduledTask {
        val task = ScheduledTask(
            id = id,
            taskText = "任务 $id",
            createdAt = now,
            fireAtMillis = now + fireInMinutes * 60_000,
            repeatIntervalMillis = repeatMinutes?.let { it * 60_000 },
        )
        assertTrue(ScheduledTaskStore.schedule(task))
        return task
    }

    @Test
    fun `arming a task rings the sink with the armed task`() {
        arm("s1")
        val ring = rings.single()
        assertEquals("s1", ring.id)
        assertEquals(ScheduledTask.Status.SCHEDULED, ring.status)
    }

    @Test
    fun `cancelling rings the sink with the cancelled task`() {
        arm("s1")
        rings.clear()
        assertTrue(ScheduledTaskStore.cancel("s1"))
        val ring = rings.single()
        assertEquals("s1", ring.id)
        // 铃带的是转换后的任务：页面据此重读即看到新状态，不必自己推。
        assertEquals(ScheduledTask.Status.CANCELLED, ring.status)
    }

    @Test
    fun `queueing rings the sink with the queued reason`() {
        arm("s1", fireInMinutes = 5)
        rings.clear()
        ScheduledTaskStore.markQueued("s1")
        val ring = rings.single()
        assertEquals(ScheduledTask.Status.QUEUED, ring.status)
        assertEquals(
            "queued: another task was running when this fired",
            ring.lastOutcome,
        )
    }

    @Test
    fun `firing a one-shot rings the sink with the fired task`() {
        arm("s1")
        rings.clear()
        val fired = ScheduledTaskStore.markFired("s1")
        assertEquals(ScheduledTask.Status.FIRED, fired!!.status)
        assertEquals(fired, rings.single())
    }

    @Test
    fun `firing a repeating task rings the sink with the re-aimed task`() {
        // 触发时刻在过去整整一个周期：markFired 把 fireAtMillis 重瞄到 now + 一个周期。
        arm("s1", fireInMinutes = -15, repeatMinutes = 15)
        rings.clear()
        val fired = ScheduledTaskStore.markFired("s1")!!
        // 周期任务保持 SCHEDULED，但 fireAtMillis 推进了——页面要重画倒计时。
        assertEquals(ScheduledTask.Status.SCHEDULED, fired.status)
        assertEquals(now + 15 * 60_000L, fired.fireAtMillis)
        assertEquals(fired, rings.single())
    }

    @Test
    fun `recording an outcome rings the sink with the outcome`() {
        arm("s1")
        rings.clear()
        val updated = ScheduledTaskStore.recordOutcome("s1", "done: 2 events summarised")
        assertEquals("done: 2 events summarised", updated!!.lastOutcome)
        assertEquals(updated, rings.single())
    }

    @Test
    fun `a reboot reconcile that moves tasks rings once per moved task`() {
        arm("missed", fireInMinutes = -60) // 单次、已过期 → MISSED
        arm("reaimed", fireInMinutes = -30, repeatMinutes = 60) // 周期 → 重瞄
        arm("future", fireInMinutes = 60) // 没到点 → 不动
        rings.clear()
        val live = ScheduledTaskStore.reconcileAfterReboot()
        // live 按 fireAt 升序：reaimed 重瞄到 now+30min，future 是 now+60min。
        assertEquals(listOf("reaimed", "future"), live.map { it.id })
        // 移动了两个（missed / reaimed），future 不动不响；铃序 = 存储里的任务序。
        assertEquals(listOf("missed", "reaimed"), rings.map { it.id })
        assertEquals(ScheduledTask.Status.MISSED, rings.first { it.id == "missed" }.status)
    }

    @Test
    fun `refused writes ring nothing`() {
        arm("s1")
        rings.clear()
        // 重复 id、未知 id 的取消/排队/触发/结果——全部被拒，一次都不响。
        // （arm() 自带 schedule 断言，重复 id 只能手工构造。）
        assertFalse(
            ScheduledTaskStore.schedule(
                ScheduledTask(
                    id = "s1",
                    taskText = "重复排同一个 id",
                    createdAt = now,
                    fireAtMillis = now + 60_000,
                )
            )
        )
        assertFalse(ScheduledTaskStore.cancel("nope"))
        assertNull(ScheduledTaskStore.markQueued("nope"))
        assertNull(ScheduledTaskStore.markFired("nope"))
        assertNull(ScheduledTaskStore.recordOutcome("nope", "done"))
        // 对已终态任务的取消同样被拒。
        assertTrue(ScheduledTaskStore.cancel("s1"))
        rings.clear()
        assertFalse(ScheduledTaskStore.cancel("s1"))
        assertTrue(rings.isEmpty())
    }

    @Test
    fun `a throwing sink does not break the write path`() {
        val boom = object : ScheduleSink {
            override fun onScheduleChanged(task: ScheduledTask) {
                throw RuntimeException("boom")
            }
        }
        ScheduledTaskStore.addSink(boom)
        try {
            arm("s1")
            assertTrue(ScheduledTaskStore.cancel("s1"))
            // 写路径活着：状态真的落了盘（缓存即事实源）。
            assertEquals(ScheduledTask.Status.CANCELLED, ScheduledTaskStore.get("s1")!!.status)
            // 并且好 sink 照样收到——坏 sink 只吃掉自己那一次回调。
            assertEquals(ScheduledTask.Status.CANCELLED, rings.last().status)
        } finally {
            ScheduledTaskStore.removeSink(boom)
        }
    }

    @Test
    fun `removing a sink stops its rings`() {
        ScheduledTaskStore.removeSink(sink)
        arm("s1")
        assertTrue(rings.isEmpty())
    }
}
