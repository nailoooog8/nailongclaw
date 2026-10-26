// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.agents.pokeclaw.task.ScheduledTask
import io.agents.pokeclaw.task.ScheduledTaskStore
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
 * The 部二↔部一 seam for the schedule queue, pinned: what the schedule page
 * draws must stay a faithful projection of the queue. A drift here is not a
 * crash — it is a row that lies (a queued task reading as armed, a missed
 * reason silently dropped), which is worse. Runs against a real store on a
 * temp directory with an injected clock, the MemoryStore/TaskRecordStoreTest
 * pattern — same as its sibling [TaskLedgerBridgeTest].
 */
class ScheduleLedgerBridgeTest {

    private lateinit var dir: File
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("schedule-ledger-bridge-test").toFile()
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

    private fun arm(
        id: String,
        text: String = "早上看一眼今天的日历和未读通知",
        fireInMinutes: Long = 60,
        repeatMinutes: Long? = null,
        anchorHours: Int? = null,
    ): ScheduledTask {
        val task = ScheduledTask(
            id = id,
            taskText = text,
            createdAt = now,
            fireAtMillis = now + fireInMinutes * 60_000,
            repeatIntervalMillis = repeatMinutes?.let { it * 60_000 },
            calendarAnchorHours = anchorHours,
        )
        assertTrue(ScheduledTaskStore.schedule(task))
        return task
    }

    private fun list(): List<JsonObject> {
        val raw = ScheduleLedgerBridge.readList()
        return JsonParser.parseString(raw).asJsonArray.map { it.asJsonObject }
    }

    private fun detail(id: String?): JsonObject? {
        val raw = ScheduleLedgerBridge.readDetail(id)
        if (raw == "null") return null
        return JsonParser.parseString(raw).asJsonObject
    }

    @Test
    fun `an empty queue reads as an empty array and a null detail`() {
        assertEquals(0, list().size)
        assertNull(detail("nope"))
        assertNull(detail(null))
        assertNull(detail("   "))
    }

    @Test
    fun `a scheduled one-shot projects to a card with its next fire time`() {
        val task = arm("s1", "明早七点半 summarize my calendar", fireInMinutes = 30)
        val card = list().single()
        assertEquals("s1", card["id"].asString)
        assertEquals("明早七点半 summarize my calendar", card["title"].asString)
        assertEquals("SCHEDULED", card["state"].asString)
        assertEquals("单次", card["repeat"].asString)
        assertEquals(task.fireAtMillis, card["nextFireAt"].asLong)
        // 没跑过：没有 lastFiredAt、没有 outcome。
        assertFalse(card.has("lastFiredAt"))
        assertFalse(card.has("outcome"))
    }

    @Test
    fun `a repeating task says its interval in the user's words`() {
        // 直接钉纯函数：schedule_task 只铸分钟粒度（最小 15），标签按分钟制走。
        assertEquals("每 15 分钟", ScheduleLedgerBridge.repeatLabel(15 * 60_000L))
        assertEquals("每小时", ScheduleLedgerBridge.repeatLabel(60 * 60_000L))
        assertEquals("每 6 小时", ScheduleLedgerBridge.repeatLabel(6 * 60 * 60_000L))
        assertEquals("每天", ScheduleLedgerBridge.repeatLabel(24 * 60 * 60_000L))
        assertEquals("每周", ScheduleLedgerBridge.repeatLabel(7 * 24 * 60 * 60_000L))
        assertEquals("每 14 天", ScheduleLedgerBridge.repeatLabel(14 * 24 * 60 * 60_000L))
        assertEquals("单次", ScheduleLedgerBridge.repeatLabel(null))
        // 并经卡片露面：一行 JSON 就是页面的契约。
        arm("s1", repeatMinutes = 1440)
        assertEquals("每天", list().single()["repeat"].asString)
    }

    @Test
    fun `live tasks come soonest first and history last, matching the store's list order`() {
        arm("later", fireInMinutes = 120)
        arm("soon", fireInMinutes = 60)
        arm("once", fireInMinutes = 90)
        ScheduledTaskStore.markFired("once")
        val ids = list().map { it["id"].asString }
        assertEquals(listOf("soon", "later", "once"), ids)
        // 跑过的单次任务进历史，带 lastFiredAt。
        val history = list().last()
        assertEquals("FIRED", history["state"].asString)
        assertEquals(now, history["lastFiredAt"].asLong)
        // 终态没有“下一次”。
        assertFalse(history.has("nextFireAt"))
    }

    @Test
    fun `a queued task keeps its reason visible and shows no next fire time`() {
        arm("s1", fireInMinutes = 5)
        ScheduledTaskStore.markQueued("s1")
        val card = list().single()
        assertEquals("QUEUED", card["state"].asString)
        // 排队原因必须在行载荷里：QUEUED 没有 error 字段，原因只活在 outcome。
        assertEquals(
            "queued: another task was running when this fired",
            card["outcome"].asString,
        )
        // QUEUED 已经触发过，等的是“前一个任务结束”这个事件——没有时间可倒计。
        assertFalse(card.has("nextFireAt"))
    }

    @Test
    fun `a missed one-shot reads as MISSED and keeps its reason`() {
        arm("s1", fireInMinutes = -60) // 过去的时间：设备关机错过的那一类
        val rearmed = ScheduledTaskStore.reconcileAfterReboot()
        assertTrue(rearmed.isEmpty()) // 没有 live 任务需要重新 arm
        val card = list().single()
        assertEquals("MISSED", card["state"].asString)
        assertEquals(
            "missed: the device was off at the scheduled time",
            card["outcome"].asString,
        )
        assertFalse(card.has("nextFireAt"))
    }

    @Test
    fun `a repeating task re-aims instead of going MISSED after a reboot`() {
        // 原时刻在过去整整一个周期：重瞄后应是 now + 一个周期（跳过错过的那些）。
        arm("daily", fireInMinutes = -1440, repeatMinutes = 1440)
        val rearmed = ScheduledTaskStore.reconcileAfterReboot()
        assertEquals(listOf("daily"), rearmed.map { it.id })
        val card = list().single()
        assertEquals("SCHEDULED", card["state"].asString)
        assertEquals(now + 1440 * 60_000L, card["nextFireAt"].asLong)
    }

    @Test
    fun `a cancelled task reads as CANCELLED`() {
        arm("s1")
        assertTrue(ScheduledTaskStore.cancel("s1"))
        val card = list().single()
        assertEquals("CANCELLED", card["state"].asString)
        assertFalse(card.has("nextFireAt"))
    }

    @Test
    fun `the detail carries the prompt, the armed fire time and the calendar anchor`() {
        val task = arm(
            "s1",
            text = "明早七点半 summarize tomorrow's calendar",
            fireInMinutes = 45,
            anchorHours = 12,
        )
        val d = detail("s1")!!
        assertEquals("明早七点半 summarize tomorrow's calendar", d["prompt"].asString)
        assertEquals(task.fireAtMillis, d["fireAt"].asLong)
        assertEquals(12, d["calendarAnchorHours"].asInt)
        assertEquals("SCHEDULED", d["state"].asString)
    }

    @Test
    fun `a read failure degrades to an empty queue, never to fabricated tasks`() {
        // 把队列文件变成一个目录：exists() 为真而 readText 必抛——与
        // TaskLedgerBridgeTest 同款的「读坏了」。此时桥必须报空，不能拿样例顶上。
        File(dir, "scheduled_tasks.json").mkdirs()
        ScheduledTaskStore.invalidate()
        assertEquals("[]", ScheduleLedgerBridge.readList())
        assertEquals("null", ScheduleLedgerBridge.readDetail("s1"))
    }

    @Test
    fun `mapping a task by hand matches the bridge output`() {
        val task = ScheduledTask(
            id = "manual",
            taskText = "手工构造的定时任务",
            createdAt = 1_000L,
            fireAtMillis = 2_000L,
            repeatIntervalMillis = 60 * 60_000L,
            calendarAnchorHours = 6,
            status = ScheduledTask.Status.QUEUED,
            lastFiredAt = 1_500L,
            lastOutcome = "queued: another task was running when this fired",
        )
        val mapped = ScheduleLedgerBridge.detailOf(task)
        assertEquals("manual", mapped["id"])
        assertEquals("QUEUED", mapped["state"])
        assertEquals("每小时", mapped["repeat"])
        assertEquals(1_500L, mapped["lastFiredAt"])
        assertEquals("queued: another task was running when this fired", mapped["outcome"])
        assertEquals(6, mapped["calendarAnchorHours"])
        // QUEUED 没有下一次可倒计——映射层不造时间。
        assertNull(mapped["nextFireAt"])
        assertEquals(2_000L, mapped["fireAt"])
    }
}
