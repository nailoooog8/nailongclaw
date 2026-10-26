// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import com.google.gson.Gson
import io.agents.pokeclaw.task.ScheduledTask
import io.agents.pokeclaw.task.ScheduledTaskStore
import io.agents.pokeclaw.utils.XLog

/**
 * The only read path between the schedule queue ([ScheduledTaskStore]) and
 * the schedule page (`mockup/claude-ui`, through the `PokeClawChat.readScheduleList`
 * / `readScheduleDetail` bridge methods). The observer half lives in
 * [WebScheduleLedgerSink].
 *
 * ## Why a mapping layer exists at all
 *
 * Same reason as [TaskLedgerBridge]: the store's model is the record of
 * truth ([ScheduledTask] with its five statuses and outcome strings), the
 * page's contract is what a queue view needs to draw (display labels, the
 * interval in the user's words, a next-fire moment to count down to).
 * Keeping the translation here means 部一 can evolve the queue format and
 * 部二 can evolve the page without a shared edit on every field. The mapping
 * is pure and unit-tested (ScheduleLedgerBridgeTest) — a silent drift here
 * shows up as a wrong row, not a crash.
 *
 * ## Honesty rules the page depends on
 *
 * - An empty queue serialises to `[]`; the page then shows its empty state.
 *   Nothing here ever fabricates a task.
 * - A read failure (store never initialised, unreadable file) also yields
 *   `[]` / `null` **and logs** — the page cannot tell the two apart, logcat
 *   can.
 * - Timestamps are epoch millis all the way through; the page computes
 *   "next run in 16 hours" itself so a running countdown never goes stale.
 * - `nextFireAt` is null unless the task is SCHEDULED: a QUEUED task already
 *   fired and waits for the running task to finish (an event, not a time),
 *   and a terminal task has no next at all. The page draws the queue
 *   explanation from the state, not from an invented timestamp.
 */
object ScheduleLedgerBridge {

    private val gson = Gson()

    /** Queue summaries, soonest-first — what the page's list level draws. */
    fun readList(): String = runCatching {
        gson.toJson(ScheduledTaskStore.list().map { summaryOf(it) })
    }.getOrElse { e ->
        XLog.e(TAG, "readList failed — reporting an empty queue", e)
        "[]"
    }

    /** One task with its full intent, or `null` when the id is unknown. */
    fun readDetail(id: String?): String {
        val wanted = id?.takeIf { it.isNotBlank() } ?: return "null"
        val task = runCatching { ScheduledTaskStore.get(wanted) }.getOrElse { e ->
            XLog.e(TAG, "readDetail('$wanted') failed — reporting a missing task", e)
            null
        } ?: return "null"
        return runCatching { gson.toJson(detailOf(task)) }.getOrElse { e ->
            XLog.e(TAG, "readDetail('$wanted') failed to serialise", e)
            "null"
        }
    }

    // ── Mapping (pure — see ScheduleLedgerBridgeTest) ─────────────────────────

    internal fun summaryOf(task: ScheduledTask): Map<String, Any?> = mapOf(
        "id" to task.id,
        "title" to task.taskText,
        "state" to task.status.name,
        "repeat" to repeatLabel(task.repeatIntervalMillis),
        // 只有 SCHEDULED 有“下一次”可言：QUEUED 是等前一个任务结束的事件，
        // 终态没有下一次。见类 KDoc 的诚实规则。
        "nextFireAt" to task.fireAtMillis.takeIf { task.status == ScheduledTask.Status.SCHEDULED },
        "lastFiredAt" to task.lastFiredAt,
        // 终态载荷：排队原因、错过原因、上次运行结果。没有它，QUEUED/MISSED
        // 在设备上就只剩一个没有下文的标签。
        "outcome" to task.lastOutcome,
        "calendarAnchorHours" to task.calendarAnchorHours,
        "createdAt" to task.createdAt,
    )

    internal fun detailOf(task: ScheduledTask): Map<String, Any?> =
        summaryOf(task) + mapOf(
            "prompt" to task.taskText,
            "fireAt" to task.fireAtMillis,
        )

    /**
     * The repeat interval as the user would say it. `schedule_task` only
     * mints minute-granular intervals (minimum 15, see ScheduleTaskTool), so
     * this never sees seconds and a null interval is the one-shot.
     */
    internal fun repeatLabel(intervalMillis: Long?): String {
        if (intervalMillis == null) return "单次"
        val minutes = intervalMillis / 60_000
        return when {
            minutes % 1440 == 0L -> when (val days = minutes / 1440) {
                1L -> "每天"
                7L -> "每周"
                else -> "每 $days 天"
            }
            minutes % 60 == 0L -> {
                val hours = minutes / 60
                if (hours == 1L) "每小时" else "每 $hours 小时"
            }
            else -> "每 $minutes 分钟"
        }
    }

    private const val TAG = "ScheduleLedgerBridge"
}
