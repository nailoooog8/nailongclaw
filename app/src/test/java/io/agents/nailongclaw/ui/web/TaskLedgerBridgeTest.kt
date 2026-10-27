// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.agents.nailongclaw.TaskEvent
import io.agents.nailongclaw.channel.Channel
import io.agents.nailongclaw.task.TaskRecord
import io.agents.nailongclaw.task.TaskRecordStore
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
 * The 部二↔部一 seam, pinned: what the task page draws must stay a faithful
 * projection of the ledger. A drift here is not a crash — it is a card that
 * lies (a failed task reading as healthy, an error silently dropped), which
 * is worse. Runs against a real store on a temp directory with an injected
 * clock, the MemoryStore/TaskRecordStoreTest pattern.
 */
class TaskLedgerBridgeTest {

    private lateinit var dir: File
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("task-ledger-bridge-test").toFile()
        TaskRecordStore.storageDirOverride = dir
        TaskRecordStore.clock = { now }
        TaskRecordStore.invalidate()
    }

    @After
    fun tearDown() {
        TaskRecordStore.storageDirOverride = null
        TaskRecordStore.clock = { System.currentTimeMillis() }
        TaskRecordStore.invalidate()
        dir.deleteRecursively()
    }

    private fun start(id: String, text: String = "do a thing", channel: Channel = Channel.LOCAL, origin: String? = null) {
        TaskRecordStore.start(id, text, channel, origin)
        now += 5_000
    }

    private fun event(id: String, event: TaskEvent) {
        TaskRecordStore.onEvent(id, event)
        now += 5_000
    }

    private fun list(): List<JsonObject> {
        val raw = TaskLedgerBridge.readList()
        return JsonParser.parseString(raw).asJsonArray.map { it.asJsonObject }
    }

    private fun detail(id: String?): JsonObject? {
        val raw = TaskLedgerBridge.readDetail(id)
        if (raw == "null") return null
        return JsonParser.parseString(raw).asJsonObject
    }

    @Test
    fun `an empty ledger reads as an empty array and a null detail`() {
        assertEquals(0, list().size)
        assertNull(detail("nope"))
        assertNull(detail(null))
        assertNull(detail("   "))
    }

    @Test
    fun `a running task projects to a card without a finish time`() {
        start("t1", "给妈妈订高铁票", origin = "io.agents.nailongclaw.ui.web.ClaudeUiPreviewActivity")
        val card = list().single()
        assertEquals("t1", card["id"].asString)
        assertEquals("给妈妈订高铁票", card["title"].asString)
        assertEquals("RUNNING", card["state"].asString)
        assertEquals("Local", card["channel"].asString)
        assertEquals(0, card["stepCount"].asInt)
        assertFalse(card.has("finishedAt"))
        assertFalse(card.has("lastStep"))
        assertFalse(card.has("error"))
    }

    @Test
    fun `a tool result lands as a step and becomes the card's last step`() {
        start("t1")
        event("t1", TaskEvent.ToolResult("web_search", true, "查到 5 条结果"))
        val card = list().single()
        assertEquals(1, card["stepCount"].asInt)
        val last = card.getAsJsonObject("lastStep")
        assertEquals("web_search", last["name"].asString)
        assertEquals("查到 5 条结果", last["summary"].asString)
        assertTrue(last["success"].asBoolean)
    }

    @Test
    fun `a completed task reads as SUCCEEDED with a finish time and its answer`() {
        start("t1")
        event("t1", TaskEvent.ToolResult("send_file", true, "已发送"))
        val finished = now
        event("t1", TaskEvent.Completed("搞定，已发送到微信"))
        val card = list().single()
        assertEquals("SUCCEEDED", card["state"].asString)
        assertEquals(finished, card["finishedAt"].asLong)
        assertEquals("搞定，已发送到微信", card["answer"].asString)
    }

    @Test
    fun `a failed task keeps its reason visible — the Failed event adds no step`() {
        start("t1")
        event("t1", TaskEvent.ToolResult("accessibility.perform_action", false, "node gone"))
        event("t1", TaskEvent.Failed("error: node btn_delete not found"))
        val card = list().single()
        assertEquals("FAILED", card["state"].asString)
        // 失败原因必须在卡片载荷里：Failed 不产步骤，只进 error。
        assertEquals("error: node btn_delete not found", card["error"].asString)
        // 而工具步骤照旧在——账本不因为失败而吃掉之前的步骤。
        assertEquals(1, card["stepCount"].asInt)
    }

    @Test
    fun `a blocked task reads as FAILED on the card and keeps its reason`() {
        start("t1")
        event("t1", TaskEvent.Blocked)
        val card = list().single()
        assertEquals("FAILED", card["state"].asString)
        assertEquals("Blocked by a system dialog.", card["error"].asString)
    }

    @Test
    fun `a cancelled task reads as CANCELLED`() {
        start("t1")
        event("t1", TaskEvent.Cancelled)
        assertEquals("CANCELLED", list().single()["state"].asString)
    }

    @Test
    fun `a task parked on a confirmation reads as WAITING_CONFIRM with its note step`() {
        start("t1")
        event("t1", TaskEvent.ToolResult("mcp_wechat_send", true, "等待确认"))
        TaskRecordStore.noteWaitingConfirm("mcp_wechat_send")
        val card = list().single()
        assertEquals("WAITING_CONFIRM", card["state"].asString)
        // 进待确认会落一条 NOTE 步骤（store 的约定），卡片步数随之 +1。
        assertEquals(2, card["stepCount"].asInt)
        assertEquals("备注", card.getAsJsonObject("lastStep")["name"].asString)
    }

    @Test
    fun `a resolved confirmation returns the task to RUNNING`() {
        start("t1")
        TaskRecordStore.noteWaitingConfirm("mcp_wechat_send")
        TaskRecordStore.noteConfirmResolved("allowed once")
        assertEquals("RUNNING", list().single()["state"].asString)
    }

    @Test
    fun `the detail carries the prompt, the origin label and every step`() {
        start("t1", "清理相册重复照片", origin = "io.agents.nailongclaw.ui.web.ClaudeUiPreviewActivity")
        event("t1", TaskEvent.ToolResult("accessibility.open_app", true, "LAUNCH"))
        event("t1", TaskEvent.Progress(1, "正在扫描…"))
        val d = detail("t1")!!
        assertEquals("清理相册重复照片", d["prompt"].asString)
        assertEquals("Claude UI 聊天", d["origin"].asString)
        val steps = d.getAsJsonArray("steps")
        assertEquals(2, steps.size())
        assertEquals("accessibility.open_app", steps[0].asJsonObject["name"].asString)
        // 无 toolName 的步骤按 kind 贴标签，不留空名。
        assertEquals("进度", steps[1].asJsonObject["name"].asString)
        assertEquals("正在扫描…", steps[1].asJsonObject["summary"].asString)
        // 无 success 的步骤按 ok 画（不是失败行），页面只把 success===false 染红。
        assertTrue(steps[1].asJsonObject["success"].asBoolean)
    }

    @Test
    fun `origin labels fall back to the simple class name and vanish when absent`() {
        assertEquals("Claude UI 聊天", TaskLedgerBridge.originLabel("io.agents.nailongclaw.ui.web.ClaudeUiPreviewActivity"))
        assertEquals("聊天页", TaskLedgerBridge.originLabel("io.agents.nailongclaw.ui.chat.ComposeChatActivity"))
        assertEquals("SomeReceiver", TaskLedgerBridge.originLabel("io.agents.nailongclaw.automation.SomeReceiver"))
        assertNull(TaskLedgerBridge.originLabel(null))
        assertNull(TaskLedgerBridge.originLabel(""))
    }

    @Test
    fun `a read failure degrades to an empty ledger, never to fabricated records`() {
        // 把账本文件变成一个目录：exists() 为真而 readText 必抛——这是能在
        // 各平台稳定复现的「读坏了」，比模拟权限失败可靠。此时桥必须报空，
        // 不能报错给页面，更不能拿样例顶上。
        File(dir, "task_records.json").mkdirs()
        TaskRecordStore.invalidate()
        assertEquals("[]", TaskLedgerBridge.readList())
        assertEquals("null", TaskLedgerBridge.readDetail("t1"))
    }

    @Test
    fun `the newest record comes first, matching the store's list order`() {
        start("old", "旧任务")
        start("new", "新任务")
        val ids = list().map { it["id"].asString }
        assertEquals(listOf("new", "old"), ids)
    }

    @Test
    fun `mapping a record by hand matches the bridge output`() {
        val record = TaskRecord(
            id = "manual",
            taskText = "手工构造的记录",
            channel = Channel.WECHAT,
            originActivity = "io.agents.nailongclaw.ui.chat.ComposeChatActivity",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            status = TaskRecord.Status.COMPLETED,
            steps = listOf(
                TaskRecord.Step(at = 1_100L, kind = TaskRecord.StepKind.TOOL, text = "发了", toolName = "send_message", success = true),
                TaskRecord.Step(at = 1_200L, kind = TaskRecord.StepKind.NOTE, text = "备注一条"),
            ),
            answer = "完事",
        )
        val mapped = TaskLedgerBridge.detailOf(record)
        assertEquals("manual", mapped["id"])
        assertEquals("SUCCEEDED", mapped["state"])
        assertEquals("WeChat", mapped["channel"])
        assertEquals("聊天页", mapped["origin"])
        assertEquals("手工构造的记录", mapped["prompt"])
        assertEquals(2_000L, mapped["finishedAt"])
        assertEquals(2, (mapped["steps"] as List<*>).size)
        @Suppress("UNCHECKED_CAST")
        val first = mapped["steps"] as List<Map<String, Any?>>
        assertEquals("send_message", first[0]["name"])
        assertEquals("备注", first[1]["name"])
        // success 缺省 → true：一条没有成败的备注步骤画成 ok 行，不是红行。
        assertEquals(true, first[1]["success"])
    }
}
