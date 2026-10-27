// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.agents.nailongclaw.ClawApplication
import io.agents.nailongclaw.TaskEvent
import io.agents.nailongclaw.agent.ContextFence
import io.agents.nailongclaw.agent.calendar.CalendarGatewayImpl
import io.agents.nailongclaw.agent.calendar.CalendarReadTool
import io.agents.nailongclaw.channel.Channel
import io.agents.nailongclaw.utils.XLog

/**
 * Runs scheduled tasks when their alarm fires — the bridge from the timer
 * half ([ScheduledTaskStore] / [TaskScheduler]) to the ordinary task path.
 *
 * ## The queue's honesty
 *
 * The app runs one task at a time (the orchestrator's lock). A scheduled
 * task that fires while another runs is **queued, visibly** — its status
 * says QUEUED, its outcome says "queued: another task was running", and the
 * user gets a notification saying so. It is never dropped and never run
 * concurrently; when any task finishes, the longest-waiting queued task
 * drains ([onAnyTaskFinished], wired from AppViewModel's task-finished hook).
 *
 * ## Provenance
 *
 * A fired task enters [TaskRecordStore] like any other task: channel
 * [Channel.SCHEDULED] — *not* LOCAL, because the alarm goes off with nobody in
 * front of the screen, which is the same reason [Channel.EXTERNAL] is not
 * LOCAL. That single choice is what puts the READ_ONLY ceiling in
 * [io.agents.nailongclaw.tool.ToolRiskPolicy] between a fired task and the tools
 * that dial, type, and press the key that approves system dialogs. A scheduled
 * brief can read the calendar and answer; it cannot act, and the refusal says
 * so to the model in words it can repeat to the user. originActivity is null
 * (no screen started it).
 *
 * ## Calendar anchor
 *
 * A task scheduled with `calendar_anchor_hours` gets the next N hours of the
 * device calendar prepended to its prompt at fire time — the morning-brief
 * primitive. The anchor is fenced ([ContextFence]): event text is other
 * people's writing. If the calendar is unreadable at fire time the task still
 * runs, with an honest note instead of a silent gap.
 */
object ScheduleDispatcher {

    private const val TAG = "ScheduleDispatcher"
    private const val CHANNEL_ID = "NailongClaw_scheduled_channel"
    private const val CHANNEL_NAME = "Scheduled tasks"
    private const val NOTIFY_QUEUED = 2001
    private const val NOTIFY_OUTCOME_BASE = 2100

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called by [TaskAlarmReceiver] on its worker thread. */
    @JvmStatic
    fun onAlarm(context: Context, taskId: String) {
        // startTask initializes the agent and touches single-slot state that
        // every other entry point reaches from the main thread — join them.
        mainHandler.post {
            val task = ScheduledTaskStore.get(taskId) ?: return@post
            if (!task.isLive) return@post

            if (task.repeatIntervalMillis != null) {
                // Advance to the next occurrence first, then re-arm — a dispatch
                // that crashes must not also lose the series.
                val advanced = ScheduledTaskStore.markFired(taskId)
                if (advanced != null) {
                    TaskScheduler.arm(context, advanced)
                }
            }

            fireOrQueue(context, ScheduledTaskStore.get(taskId) ?: task)
        }
    }

    private fun fireOrQueue(context: Context, task: ScheduledTask) {
        val appViewModel = try {
            ClawApplication.appViewModelInstance
        } catch (e: Exception) {
            XLog.e(TAG, "no AppViewModel — cannot dispatch '${task.id}'", e)
            return
        }
        if (appViewModel.isTaskRunning()) {
            if (task.status == ScheduledTask.Status.SCHEDULED) {
                ScheduledTaskStore.markQueued(task.id)
                XLog.i(TAG, "'${task.id}' fired while another task runs — queued")
                notify(
                    context,
                    NOTIFY_QUEUED,
                    "Scheduled task queued",
                    "\"${task.taskText}\" is waiting for the running task to finish.",
                )
            }
            return
        }
        fireNow(context, task)
    }

    /** Runs a scheduled task now. Caller must have checked nothing else runs. */
    @JvmStatic
    fun fireNow(context: Context, task: ScheduledTask) {
        val appViewModel = try {
            ClawApplication.appViewModelInstance
        } catch (e: Exception) {
            XLog.e(TAG, "no AppViewModel — cannot fire '${task.id}'", e)
            return
        }
        val fired = ScheduledTaskStore.markFired(task.id) ?: task
        val recordId = "sched_${fired.id}_${System.currentTimeMillis()}"
        val prompt = buildPrompt(context, fired)

        notify(
            context,
            NOTIFY_OUTCOME_BASE + recordId.hashCode() % 1000,
            "Scheduled task started",
            fired.taskText,
        )

        try {
            appViewModel.startTask(
                task = prompt,
                taskId = recordId,
                originActivity = null,
                channel = Channel.SCHEDULED,
                // The record must name the user's request, not the envelope
                // the task runs with (calendar anchor + fence + delivery
                // instructions) — the orchestrator opens the record itself.
                ledgerTaskText = fired.taskText,
            ) { event ->
                if (event is TaskEvent.Completed || event is TaskEvent.Failed ||
                    event is TaskEvent.Cancelled || event is TaskEvent.Blocked
                ) {
                    ScheduledTaskStore.recordOutcome(fired.id, outcomeText(event))
                    notifyOutcome(context, fired, event)
                    // The orchestrator released the session before delivering
                    // the terminal event, so the next queued task may start.
                    mainHandler.postDelayed({ drainQueue(context) }, 1_000)
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "firing '${fired.id}' failed", e)
            ScheduledTaskStore.recordOutcome(fired.id, "failed to start: ${e.message}")
            TaskRecordStore.onEvent(recordId, TaskEvent.Failed(e.message ?: "could not start"))
            drainQueue(context)
        }
    }

    /** Wired from AppViewModel.onTaskFinished — drain after ANY task ends. */
    @JvmStatic
    fun onAnyTaskFinished() {
        val context = try {
            ClawApplication.instance
        } catch (e: Exception) {
            return
        }
        mainHandler.postDelayed({ drainQueue(context) }, 1_000)
    }

    private fun drainQueue(context: Context) {
        val appViewModel = try {
            ClawApplication.appViewModelInstance
        } catch (e: Exception) {
            return
        }
        if (appViewModel.isTaskRunning()) return
        val next = ScheduledTaskStore.nextQueued() ?: return
        XLog.i(TAG, "drain: firing queued '${next.id}'")
        fireNow(context, next)
    }

    /** The prompt a fired task runs with, including the calendar anchor. */
    internal fun buildPrompt(context: Context, task: ScheduledTask): String {
        val anchorHours = task.calendarAnchorHours ?: return task.taskText
        val anchor = try {
            val events = CalendarGatewayImpl(context).eventsBetween(
                System.currentTimeMillis(),
                System.currentTimeMillis() + anchorHours * 3_600_000L,
                maxEvents = 15,
            )
            "The user's calendar for the next $anchorHours hours:\n" +
                ContextFence.wrap("calendar", CalendarReadTool.formatEvents(events, anchorHours))
        } catch (e: Exception) {
            // Honest gap: the brief still runs, and says why it is thinner.
            "(Calendar unavailable: ${e.message})"
        }
        return "$anchor\n\nScheduled request (runs unattended; answer briefly — the " +
            "result is delivered as a notification, not a conversation):\n${task.taskText}"
    }

    private fun outcomeText(event: TaskEvent): String = when (event) {
        is TaskEvent.Completed -> "completed: ${event.answer.take(200)}"
        is TaskEvent.Failed -> "failed: ${event.error.take(200)}"
        TaskEvent.Cancelled -> "cancelled"
        TaskEvent.Blocked -> "blocked by a system dialog"
        else -> "ended"
    }

    private fun notifyOutcome(context: Context, task: ScheduledTask, event: TaskEvent) {
        val (title, text) = when (event) {
            is TaskEvent.Completed -> "Scheduled task done" to event.answer.take(200)
            is TaskEvent.Failed -> "Scheduled task failed" to event.error.take(200)
            TaskEvent.Cancelled -> "Scheduled task cancelled" to task.taskText
            TaskEvent.Blocked -> "Scheduled task blocked" to "A system dialog stopped it."
            else -> return
        }
        notify(context, NOTIFY_OUTCOME_BASE + task.id.hashCode() % 1000, title, text)
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return // no grant, no notification — the ledger still records it
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            nm.notify(
                id,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(io.agents.nailongclaw.R.drawable.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: Exception) {
            XLog.w(TAG, "notification failed (task record still kept)", e)
        }
    }
}
