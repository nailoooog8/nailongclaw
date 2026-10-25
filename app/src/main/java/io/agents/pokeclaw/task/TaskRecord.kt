// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.task

import com.google.gson.JsonObject
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.utils.XLog

/**
 * One durable entry in the task ledger — the honest record of a task the app
 * accepted: what was asked, where it came from, what it did, and how it ended.
 *
 * [TaskSessionStore] answers "what is running *right now*" and dies with the
 * process; this answers "what has run *at all*" and survives restarts. The
 * task page reads only from here, never from the session store, so a record
 * outlives both the Activity that started it and the process that ran it.
 *
 * ## Why `channel` and `originActivity` are non-negotiable fields
 *
 * Both were paid for already: the channel decides what the task was allowed
 * to do ([io.agents.pokeclaw.tool.ToolRiskPolicy] gates unwatched sources),
 * and the origin decides where the user should land when the task finishes
 * (the hardcoded-return bug, see the "Promote the Claude UI mockup" backlog
 * item). A ledger entry without them cannot answer "why did this task behave
 * like that" a day later, so both ride with the record instead of living in
 * the session that happened to start it.
 *
 * ## JSON shape
 *
 * Written and read only through [toJson]/[parse] in this file — the round
 * trip is pinned by a unit test because a format whose reader and writer
 * drift apart is exactly the class of bug the chat transcript format had
 * (save/load asymmetry, BACKLOG 2026-10-22).
 */
data class TaskRecord(
    /** Same id the orchestrator uses as the session's messageId. */
    val id: String,
    /** The task text as the user phrased it. */
    val taskText: String,
    /** Where the task came from — decides what its tools were allowed to do. */
    val channel: Channel,
    /**
     * FQCN of the Activity that started the task, or null for entries with no
     * UI origin (scheduled/queued tasks). A String, never an Activity: the
     * record outlives the Activity.
     */
    val originActivity: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val status: Status = Status.RUNNING,
    /** What the task actually did, oldest first — the detail page's ledger. */
    val steps: List<Step> = emptyList(),
    /** True once older steps were dropped to stay inside the step cap. */
    val stepsTruncated: Boolean = false,
    /** Final answer, set on COMPLETED. */
    val answer: String? = null,
    /** Why the task ended badly, set on FAILED / BLOCKED / interrupted. */
    val error: String? = null,
) {

    enum class Status {
        /** Accepted and executing. */
        RUNNING,

        /**
         * Parked on a human decision — an MCP confirmation card is on screen
         * and the agent thread is blocked behind it. Entered and left only
         * through [TaskRecordStore.noteWaitingConfirm] /
         * [TaskRecordStore.noteConfirmResolved]; no [io.agents.pokeclaw.TaskEvent]
         * carries this state, because a confirmation is not a task event.
         */
        WAITING_CONFIRM,

        COMPLETED,
        FAILED,
        CANCELLED,
        BLOCKED,
    }

    enum class StepKind { TOOL, PROGRESS, NOTE }

    data class Step(
        val at: Long,
        val kind: StepKind,
        val text: String,
        val toolName: String? = null,
        val success: Boolean? = null,
    )

    val isTerminal: Boolean
        get() = status in TERMINAL_STATUSES

    /** How long the task was live; for terminal records this is total duration. */
    val durationMillis: Long
        get() = (updatedAt - createdAt).coerceAtLeast(0)

    companion object {
        private const val TAG = "TaskRecord"

        val TERMINAL_STATUSES = setOf(
            Status.COMPLETED, Status.FAILED, Status.CANCELLED, Status.BLOCKED,
        )

        /** Cap on steps kept per record; older steps are dropped, not silently. */
        const val MAX_STEPS = 120

        /** Defensive parse: a record missing an identity field is skipped, not guessed at. */
        fun parse(obj: JsonObject): TaskRecord? {
            val id = obj.stringOrNull("id")
            if (id.isNullOrBlank()) {
                XLog.w(TAG, "parse: record without id skipped")
                return null
            }
            // channel is part of the record's contract. An unknown channel name
            // means the file came from a newer build than this one — keeping the
            // record would fabricate LOCAL provenance for a task that may have
            // been gated differently, so the whole record is skipped.
            val channelName = obj.stringOrNull("channel")
            val channel = Channel.values().firstOrNull { it.name == channelName }
            if (channel == null) {
                XLog.w(TAG, "parse: record '$id' has unreadable channel '$channelName' — skipped")
                return null
            }
            val statusName = obj.stringOrNull("status")
            val status = Status.values().firstOrNull { it.name == statusName }
            if (status == null) {
                XLog.w(TAG, "parse: record '$id' has unreadable status '$statusName' — skipped")
                return null
            }
            if (!obj.has("createdAt")) {
                XLog.w(TAG, "parse: record '$id' without createdAt — skipped")
                return null
            }
            val steps = (obj.getAsJsonArray("steps"))?.mapNotNull { el ->
                val stepObj = el.asJsonObjectOrNull() ?: return@mapNotNull null
                parseStep(stepObj)
            } ?: emptyList()
            return TaskRecord(
                id = id,
                taskText = obj.stringOrNull("taskText").orEmpty(),
                channel = channel,
                originActivity = obj.stringOrNull("originActivity"),
                createdAt = obj.get("createdAt").asLong,
                updatedAt = obj.longOr("updatedAt", obj.get("createdAt").asLong),
                status = status,
                steps = steps,
                stepsTruncated = obj.boolOr("stepsTruncated", false),
                answer = obj.stringOrNull("answer"),
                error = obj.stringOrNull("error"),
            )
        }

        private fun parseStep(obj: JsonObject): Step? {
            val kind = StepKind.values().firstOrNull { it.name == obj.stringOrNull("kind") }
                ?: return null
            val text = obj.stringOrNull("text") ?: return null
            return Step(
                at = obj.longOr("at", 0L),
                kind = kind,
                text = text,
                toolName = obj.stringOrNull("toolName"),
                success = if (obj.has("success") && !obj.get("success").isJsonNull) obj.get("success").asBoolean else null,
            )
        }

        fun toJson(record: TaskRecord): JsonObject = JsonObject().apply {
            addProperty("id", record.id)
            addProperty("taskText", record.taskText)
            addProperty("channel", record.channel.name)
            record.originActivity?.let { addProperty("originActivity", it) }
            addProperty("createdAt", record.createdAt)
            addProperty("updatedAt", record.updatedAt)
            addProperty("status", record.status.name)
            add("steps", com.google.gson.JsonArray().apply {
                record.steps.forEach { step ->
                    add(JsonObject().apply {
                        addProperty("at", step.at)
                        addProperty("kind", step.kind.name)
                        addProperty("text", step.text)
                        step.toolName?.let { addProperty("toolName", it) }
                        step.success?.let { addProperty("success", it) }
                    })
                }
            })
            if (record.stepsTruncated) addProperty("stepsTruncated", true)
            record.answer?.let { addProperty("answer", it) }
            record.error?.let { addProperty("error", it) }
        }

        private fun JsonObject.stringOrNull(key: String): String? =
            get(key)?.takeIf { !it.isJsonNull }?.asString

        private fun JsonObject.longOr(key: String, default: Long): Long =
            get(key)?.takeIf { !it.isJsonNull }?.asLong ?: default

        private fun JsonObject.boolOr(key: String, default: Boolean): Boolean =
            get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: default

        private fun com.google.gson.JsonElement.asJsonObjectOrNull(): JsonObject? =
            takeIf { !it.isJsonNull && isJsonObject }?.asJsonObject
    }
}
