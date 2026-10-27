// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl

import io.agents.nailongclaw.ClawApplication
import io.agents.nailongclaw.task.ScheduledTaskStore
import io.agents.nailongclaw.task.TaskScheduler
import io.agents.nailongclaw.tool.BaseTool
import io.agents.nailongclaw.tool.ToolParameter
import io.agents.nailongclaw.tool.ToolResult

/**
 * Cancel a scheduled task by id. WRITE tier: removing a future run the user
 * asked for is a real change, recoverable only by scheduling it again.
 */
class CancelScheduledTaskTool(
    private val disarm: (String) -> Unit = {
        TaskScheduler.disarm(ClawApplication.instance, it)
    },
) : BaseTool() {

    override fun getName() = "cancel_scheduled_task"

    override fun getDisplayName() = "Cancel Scheduled Task"

    override fun getDescriptionEN() =
        "Cancel a scheduled task by its id (from list_scheduled_tasks / schedule_task's result). " +
        "Only live tasks (SCHEDULED or QUEUED) can be cancelled. If unsure of the id, list first."

    override fun getDescriptionCN() =
        "按 id 取消一个定时任务（id 来自 list_scheduled_tasks 或 schedule_task 的结果）。" +
        "只有未触发的（SCHEDULED / QUEUED）可取消；不确定 id 就先查列表。"

    override fun getParameters() = listOf(
        ToolParameter(
            "id", "string",
            "The scheduled task id, e.g. sched_ab12cd34.",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val id = params["id"]?.toString()?.trim().orEmpty()
        if (id.isEmpty()) return ToolResult.error("cancel_scheduled_task: id is required")

        if (!ScheduledTaskStore.cancel(id)) {
            val live = ScheduledTaskStore.list().filter { it.isLive }.map { it.id }
            val hint = if (live.isEmpty()) {
                "There are no live scheduled tasks."
            } else {
                "Live ids: ${live.joinToString(", ")}."
            }
            return ToolResult.error(
                "cancel_scheduled_task: '$id' is unknown or already finished. $hint"
            )
        }
        disarm(id)
        return ToolResult.success("Cancelled scheduled task '$id'.")
    }
}
