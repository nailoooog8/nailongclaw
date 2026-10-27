// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import com.google.gson.JsonObject
import io.agents.nailongclaw.utils.XLog

/**
 * A task the user (via the agent) asked to run *later*, on a timer — the
 * durable half of the scheduling pair:
 *
 * - [ScheduledTaskStore] + [TaskScheduler] hold the *intent* ("run this at
 *   07:30 every day"), surviving reboots by re-arming;
 * - when it fires, [ScheduleDispatcher] runs it as an ordinary task, which
 *   lands in [TaskRecordStore] like any other, with `originActivity = null`
 *   (no screen started it) and `channel = LOCAL`.
 *
 * ## Honest statuses
 *
 * QUEUED is the queue's honesty: a task that fired while another was running
 * says so instead of pretending it ran. MISSED is the reboot honesty: a
 * one-shot whose window passed while the device was off is closed out as
 * missed, not silently fired late — a 7:30 brief that runs at 11:00 because
 * the phone was off is worse than one that admits it did not run.
 */
data class ScheduledTask(
    val id: String,
    val taskText: String,
    val createdAt: Long,
    val fireAtMillis: Long,
    /** Repeat interval, or null for a one-shot. */
    val repeatIntervalMillis: Long? = null,
    /** When set, the fired prompt is anchored with the next N hours of calendar. */
    val calendarAnchorHours: Int? = null,
    val status: Status = Status.SCHEDULED,
    val lastFiredAt: Long? = null,
    val lastOutcome: String? = null,
) {

    enum class Status {
        /** Armed; waiting for its alarm. */
        SCHEDULED,

        /** Fired while another task was running; waiting for its turn. */
        QUEUED,

        /** One-shot whose window passed (device off); closed, never fires. */
        MISSED,

        /** One-shot that fired; [lastOutcome] and the task ledger hold the result. */
        FIRED,

        /** Cancelled by the user/agent. */
        CANCELLED,
    }

    val isLive: Boolean
        get() = status == Status.SCHEDULED || status == Status.QUEUED

    /**
     * Next occurrence strictly after [now] for a repeating task; the armed
     * time for a one-shot. A device that was off for three days skips the
     * missed occurrences instead of firing them all at once.
     */
    fun nextOccurrenceAfter(now: Long): Long {
        val interval = repeatIntervalMillis ?: return fireAtMillis
        if (fireAtMillis > now) return fireAtMillis
        val skipped = ((now - fireAtMillis) / interval) + 1
        return fireAtMillis + skipped * interval
    }

    companion object {

        private const val TAG = "ScheduledTask"

        fun parse(obj: JsonObject): ScheduledTask? {
            val id = obj.stringOrNull("id")
            val taskText = obj.stringOrNull("taskText")
            if (id.isNullOrBlank() || taskText == null) {
                XLog.w(TAG, "parse: scheduled task without id/text skipped")
                return null
            }
            if (!obj.has("fireAtMillis")) {
                XLog.w(TAG, "parse: scheduled task '$id' without fireAtMillis — skipped")
                return null
            }
            val statusName = obj.stringOrNull("status")
            val status = Status.values().firstOrNull { it.name == statusName }
            if (status == null) {
                XLog.w(TAG, "parse: scheduled task '$id' has unreadable status '$statusName' — skipped")
                return null
            }
            return ScheduledTask(
                id = id,
                taskText = taskText,
                createdAt = obj.longOr("createdAt", 0L),
                fireAtMillis = obj.get("fireAtMillis").asLong,
                repeatIntervalMillis = obj.longOrNull("repeatIntervalMillis"),
                calendarAnchorHours = obj.longOrNull("calendarAnchorHours")?.toInt(),
                status = status,
                lastFiredAt = obj.longOrNull("lastFiredAt"),
                lastOutcome = obj.stringOrNull("lastOutcome"),
            )
        }

        fun toJson(task: ScheduledTask): JsonObject = JsonObject().apply {
            addProperty("id", task.id)
            addProperty("taskText", task.taskText)
            addProperty("createdAt", task.createdAt)
            addProperty("fireAtMillis", task.fireAtMillis)
            task.repeatIntervalMillis?.let { addProperty("repeatIntervalMillis", it) }
            task.calendarAnchorHours?.let { addProperty("calendarAnchorHours", it) }
            addProperty("status", task.status.name)
            task.lastFiredAt?.let { addProperty("lastFiredAt", it) }
            task.lastOutcome?.let { addProperty("lastOutcome", it) }
        }

        private fun JsonObject.stringOrNull(key: String): String? =
            get(key)?.takeIf { !it.isJsonNull }?.asString

        private fun JsonObject.longOr(key: String, default: Long): Long =
            get(key)?.takeIf { !it.isJsonNull }?.asLong ?: default

        private fun JsonObject.longOrNull(key: String): Long? =
            get(key)?.takeIf { !it.isJsonNull }?.asLong
    }
}
