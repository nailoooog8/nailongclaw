// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl

import io.agents.nailongclaw.task.ScheduledTask
import io.agents.nailongclaw.task.ScheduledTaskStore
import io.agents.nailongclaw.tool.BaseTool
import io.agents.nailongclaw.tool.ToolParameter
import io.agents.nailongclaw.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * List the device's scheduled tasks — live ones soonest-first, then history
 * with each one's honest outcome (queued, missed, completed, failed).
 *
 * READ_ONLY: it observes the app's own state; nothing here reaches the
 * world.
 */
class ListScheduledTasksTool(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : BaseTool() {

    override fun getName() = "list_scheduled_tasks"

    override fun getDisplayName() = "Scheduled Tasks"

    override fun getDescriptionEN() =
        "List this device's scheduled tasks: live ones (SCHEDULED, soonest first) with fire time, " +
        "repeat interval and task text, then finished ones (FIRED / MISSED / CANCELLED) with their " +
        "last outcome. Use it before cancelling, and to answer \"what have I scheduled?\"."

    override fun getDescriptionCN() =
        "列出本设备的定时任务：在前的（SCHEDULED，按触发时间排序）含触发时间、周期与任务文本，" +
        "其后是已结束的（FIRED / MISSED / CANCELLED）及其最近结果。取消前先查这里，也可回答「我定过什么时」。"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override fun execute(params: Map<String, Any>): ToolResult {
        val tasks = ScheduledTaskStore.list()
        if (tasks.isEmpty()) {
            return ToolResult.success("No scheduled tasks. Create one with schedule_task.")
        }
        val now = clock()
        val dayAndTime = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return ToolResult.success(
            tasks.joinToString("\n") { task ->
                val when_ = when {
                    task.status == ScheduledTask.Status.QUEUED -> "waiting for the running task"
                    task.isLive -> {
                        val inMinutes = ((task.fireAtMillis - now) / 60_000).coerceAtLeast(0)
                        "fires ${dayAndTime.format(Date(task.fireAtMillis))} (in ${durationText(inMinutes)})"
                    }
                    else -> task.lastOutcome ?: task.status.name
                }
                val repeat = task.repeatIntervalMillis
                    ?.let { " · every ${it / 60_000} min" }
                    ?: ""
                "[${task.status}] ${task.id} · $when_$repeat · \"${task.taskText.take(80)}\""
            }
        )
    }

    private fun durationText(minutes: Long): String = when {
        minutes < 60 -> "${minutes}min"
        minutes < 24 * 60 -> "${minutes / 60}h ${minutes % 60}min"
        else -> "${minutes / (24 * 60)}d ${minutes % (24 * 60) / 60}h"
    }
}
