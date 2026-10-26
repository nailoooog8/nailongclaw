// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import io.agents.pokeclaw.task.TaskRecord
import io.agents.pokeclaw.task.TaskRecordStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The chip row's contract, pinned: the numbers on a completed answer must
 * be the record's numbers. A chip that counts attempts instead of
 * outcomes (a failed calendar_create showing as 已建日程 1) is worse than
 * no chip — it is a summary that lies about what the phone did.
 *
 * Runs against a real store on a temp directory with an injected clock,
 * the MemoryStore/TaskRecordStoreTest pattern.
 */
class TaskRecordChipsTest {

    private lateinit var dir: File
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("task-chips-test").toFile()
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

    private fun labels(chips: List<TaskChip>) = chips.map { it.label }

    @Test
    fun `a completed task gets its duration and step count at minimum`() {
        TaskRecordStore.start("t1", "导出记忆库并分享", io.agents.pokeclaw.channel.Channel.LOCAL, null)
        now += 222_000 // 3 分 42 秒
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.Completed("已导出并发送"))
        val chips = TaskRecordChips.of(TaskRecordStore.get("t1")!!)
        assertEquals(listOf("耗时", "步骤"), labels(chips))
        assertEquals("3 分钟", chips[0].value)
        assertEquals("0", chips[1].value)
    }

    @Test
    fun `tool steps count as tools and progress notes do not`() {
        TaskRecordStore.start("t1", "清理相册", io.agents.pokeclaw.channel.Channel.LOCAL, null)
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.ToolResult("accessibility.open_app", true, "ok"))
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.Progress(1, "正在扫描…"))
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.ToolResult("memory_recall", true, "3 条"))
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.Completed("完事"))
        val chips = TaskRecordChips.of(TaskRecordStore.get("t1")!!)
        assertEquals(listOf("耗时", "步骤", "工具"), labels(chips))
        // 三个步骤里两个是工具——进度备注不是工具。
        assertEquals("3", chips.first { it.label == "步骤" }.value)
        assertEquals("2", chips.first { it.label == "工具" }.value)
    }

    @Test
    fun `only landed calendar_create steps count as created events`() {
        TaskRecordStore.start("t1", "建两个日程", io.agents.pokeclaw.channel.Channel.LOCAL, null)
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.ToolResult("calendar_create", true, "已建：午饭"))
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.ToolResult("calendar_create", false, "权限缺失"))
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.ToolResult("calendar_read", true, "3 个事件"))
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.Completed("建了 1 个"))
        val chips = TaskRecordChips.of(TaskRecordStore.get("t1")!!)
        // 失败的那次不算「已建」——chip 数的是结果不是尝试。
        assertEquals("1", chips.first { it.label == "已建日程" }.value)
    }

    @Test
    fun `a task with no tools and no events has no such chips`() {
        TaskRecordStore.start("t1", "问个好", io.agents.pokeclaw.channel.Channel.LOCAL, null)
        TaskRecordStore.onEvent("t1", io.agents.pokeclaw.TaskEvent.Completed("你好"))
        val chips = TaskRecordChips.of(TaskRecordStore.get("t1")!!)
        assertEquals(listOf("耗时", "步骤"), labels(chips))
        assertTrue(chips.none { it.label == "工具" || it.label == "已建日程" })
    }

    @Test
    fun `durations format the same way the task page's elapsed column does`() {
        assertEquals("42 秒", TaskRecordChips.formatDuration(42_000))
        assertEquals("3 分钟", TaskRecordChips.formatDuration(222_000))
        assertEquals("1 小时", TaskRecordChips.formatDuration(3_600_000))
        assertEquals("1 小时 5 分", TaskRecordChips.formatDuration(3_900_000))
        // 负数不可能出现（durationMillis 已 coerce），但钳制要比抛异常诚实。
        assertEquals("0 秒", TaskRecordChips.formatDuration(-5_000))
    }
}
