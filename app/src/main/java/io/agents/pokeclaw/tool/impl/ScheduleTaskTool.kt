// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.task.ScheduleDispatcher
import io.agents.pokeclaw.task.ScheduledTask
import io.agents.pokeclaw.task.ScheduledTaskStore
import io.agents.pokeclaw.task.TaskScheduler
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Schedule a task to run later, unattended — the durable timer the user (or
 * the agent, mid-conversation: "tomorrow at 8, remind me to…") can set.
 *
 * The names `schedule_task` / `list_scheduled_tasks` / `cancel_scheduled_task`
 * were reserved long before this tool existed (BaseTool's wait_after
 * exclusion list) — they finally have owners.
 *
 * ## What the result must say (honesty contract)
 *
 * The success message carries the id (needed to cancel), the fire time in
 * local time, and the two caveats that make a timer honest: the device must
 * be on when it fires (a missed one-shot is closed as MISSED, not run late),
 * and if another task is running at that moment this one queues visibly
 * rather than running concurrently.
 *
 * WRITE tier on purpose: this mints *future unattended execution*, which is
 * not something an unwatched remote channel should be able to create.
 */
class ScheduleTaskTool(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val arm: (ScheduledTask) -> Unit = {
        TaskScheduler.arm(ClawApplication.instance, it)
    },
) : BaseTool() {

    companion object {
        /** A schedule further out than this is a calendar entry, not a timer. */
        private const val MAX_HORIZON_MILLIS = 365L * 24 * 3_600_000

        /** Shorter than this and "every N minutes" just burns the battery. */
        private const val MIN_REPEAT_MINUTES = 15L
    }

    override fun getName() = "schedule_task"

    override fun getDisplayName() = "Schedule Task"

    override fun getDescriptionEN() =
        "Schedule a task to run later on this device, unattended (e.g. a 07:30 daily brief of " +
        "tomorrow's calendar). Give exactly one of delay_minutes or at_millis. Optionally repeat_minutes " +
        "for a recurring schedule, and calendar_anchor_hours to prepend the next N hours of the user's " +
        "calendar to the prompt when it fires. The result is delivered as a notification and recorded " +
        "in the task ledger. Caveats: the device must be on at fire time (a missed one-shot is closed " +
        "as missed, not run late); if another task is running then, this one queues visibly."

    override fun getDescriptionCN() =
        "安排一个稍后在设备上自动运行的任务（如每天 7:30 的日程简报）。delay_minutes 与 at_millis 二选一；" +
        "可选 repeat_minutes 设周期，calendar_anchor_hours 在触发时把接下来 N 小时的日历注入提示词。" +
        "结果以通知送达并记入任务账本。注意：触发时设备必须开机（错过的单次任务会记为错过、不会迟跑）；" +
        "若当时正有任务在跑，本任务会明确排队等待。"

    override fun getParameters() = listOf(
        ToolParameter(
            "task", "string",
            "The task to run at fire time, phrased as a standalone request.",
            true
        ),
        ToolParameter(
            "delay_minutes", "integer",
            "Optional: fire this many minutes from now (1+). Use for relative times.",
            false
        ),
        ToolParameter(
            "at_millis", "integer",
            "Optional: absolute fire time in epoch milliseconds. Use for calendar-style times.",
            false
        ),
        ToolParameter(
            "repeat_minutes", "integer",
            "Optional: repeat every N minutes after the first fire (minimum 15).",
            false
        ),
        ToolParameter(
            "calendar_anchor_hours", "integer",
            "Optional: 1-24. Prepend the next N hours of calendar events to the prompt at fire time.",
            false
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val taskText = params["task"]?.toString()?.trim().orEmpty()
        if (taskText.isEmpty()) return ToolResult.error("schedule_task: task is empty")

        val now = clock()
        val hasDelay = params.containsKey("delay_minutes")
        val hasAt = params.containsKey("at_millis")
        if (hasDelay == hasAt) {
            return ToolResult.error(
                "schedule_task: give exactly one of delay_minutes or at_millis — " +
                    "both (${hasDelay}) is a contradiction, neither is a no-op."
            )
        }

        val fireAt = if (hasDelay) {
            val delay = optionalLong(params, "delay_minutes", 0)
            if (delay < 1) return ToolResult.error("schedule_task: delay_minutes must be at least 1")
            now + delay * 60_000L
        } else {
            optionalLong(params, "at_millis", 0)
        }
        if (fireAt <= now + 30_000L) {
            return ToolResult.error(
                "schedule_task: that time ($fireAt) is already in the past — " +
                    "for \"now\" just run the task directly instead of scheduling it."
            )
        }
        if (fireAt > now + MAX_HORIZON_MILLIS) {
            return ToolResult.error(
                "schedule_task: that is over a year out — put it on the calendar " +
                    "(calendar_create) instead of a timer."
            )
        }

        val repeatMinutes = if (params.containsKey("repeat_minutes")) {
            val minutes = optionalLong(params, "repeat_minutes", 0)
            if (minutes < MIN_REPEAT_MINUTES) {
                return ToolResult.error(
                    "schedule_task: repeat_minutes must be at least $MIN_REPEAT_MINUTES"
                )
            }
            minutes
        } else {
            null
        }

        val anchorHours = if (params.containsKey("calendar_anchor_hours")) {
            optionalInt(params, "calendar_anchor_hours", 0).coerceIn(1, 24)
        } else {
            null
        }

        val task = ScheduledTask(
            id = "sched_${UUID.randomUUID().toString().take(8)}",
            taskText = taskText,
            createdAt = now,
            fireAtMillis = fireAt,
            repeatIntervalMillis = repeatMinutes?.let { it * 60_000L },
            calendarAnchorHours = anchorHours,
        )
        if (!ScheduledTaskStore.schedule(task)) {
            return ToolResult.error("schedule_task: id collision on '${task.id}' — try again")
        }
        arm(task)

        val fireTime = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(fireAt))
        val repeatNote = repeatMinutes?.let { ", repeating every $it min" } ?: ""
        return ToolResult.success(
            "Scheduled: \"${taskText.take(120)}\" — fires at $fireTime$repeatNote. " +
                "Id: ${task.id} (cancel_scheduled_task needs it). " +
                "Runs unattended on this device; if a task is running at that moment it queues visibly; " +
                "a one-shot missed because the device was off is closed as missed, not run late."
        )
    }
}
