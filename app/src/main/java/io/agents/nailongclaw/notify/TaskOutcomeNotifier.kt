// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.agents.nailongclaw.ClawApplication
import io.agents.nailongclaw.R
import io.agents.nailongclaw.channel.Channel
import io.agents.nailongclaw.task.TaskRecord
import io.agents.nailongclaw.task.TaskRecordStore
import io.agents.nailongclaw.task.TaskSink
import io.agents.nailongclaw.utils.XLog

/**
 * Posts a system notification when a task ends without its starter watching.
 *
 * The gap this closes: a task the user typed before walking away (or one that
 * arrived from external automation) used to end into the ledger only — the
 * user found out when they next opened the app. Scheduled tasks were covered
 * by [io.agents.nailongclaw.task.ScheduleDispatcher.notifyOutcome]; this
 * extends the same treatment to every other entry path.
 *
 * ## Why a [TaskSink], not an orchestrator hook
 *
 * The ledger already sees every task exactly once (one write point — see
 * [TaskRecordStore.onEvent]), including the direct-tool path that never
 * reaches the orchestrator. Observing terminal transitions there means no
 * entry path can be forgotten, and no orchestrator change is needed.
 *
 * ## Double-notification guard
 *
 * SCHEDULED tasks are excluded here — their dispatcher already notifies, and
 * two notifications for one task is the kind of detail that reads as a bug.
 * Records that reach a terminal status can still fire further ledger updates
 * (a late step on an absorbed record), so notified ids are remembered.
 */
object TaskOutcomeNotifier : TaskSink {

    private const val TAG = "TaskOutcomeNotifier"
    private const val CHANNEL_ID = "NailongClaw_task_channel"
    private const val NOTIFY_BASE = 3000

    /** Bounded memory of records already announced; ids are unique per task. */
    private val notifiedIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > 128
    }

    /**
     * The notify-or-not decision, pure so the matrix is unit-testable.
     *
     * Only terminal statuses notify. SCHEDULED never does (its dispatcher owns
     * that). EXTERNAL always does — the caller is another app, nobody is
     * watching this screen. Channel tasks (DISCORD/TELEGRAM/…) are the same
     * shape: the user is wherever the chat platform is, not here. LOCAL is the
     * walked-away case: the user typed it, so only speak up when the app is
     * not in the foreground anymore.
     */
    fun shouldNotify(channel: Channel, appForeground: Boolean, status: TaskRecord.Status): Boolean {
        if (status !in TaskRecord.TERMINAL_STATUSES) return false
        return when (channel) {
            Channel.SCHEDULED -> false
            Channel.LOCAL -> !appForeground
            else -> true
        }
    }

    override fun onTaskRecordAdded(record: TaskRecord) = Unit

    override fun onTaskRecordUpdated(record: TaskRecord) {
        if (!shouldNotify(record.channel, ClawApplication.isAppInForeground, record.status)) return
        synchronized(notifiedIds) {
            if (notifiedIds.put(record.id, true) == true) return
        }
        val context = try {
            ClawApplication.instance
        } catch (e: Exception) {
            return
        }
        notify(context, record)
    }

    private fun notify(context: Context, record: TaskRecord) {
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
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.notification_task_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.notification_task_channel_description)
                        // R5 顺手批: pinned explicitly so the default never
                        // drifts — lockscreen shows the banner but content
                        // respects the user's sensitive-notification setting.
                        setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                    }
                )
            }
            val (titleRes, text) = when (record.status) {
                TaskRecord.Status.COMPLETED ->
                    R.string.notification_task_done_title to (record.answer ?: record.taskText)
                TaskRecord.Status.FAILED ->
                    R.string.notification_task_failed_title to (record.error ?: record.taskText)
                TaskRecord.Status.CANCELLED ->
                    R.string.notification_task_cancelled_title to record.taskText
                TaskRecord.Status.BLOCKED ->
                    R.string.notification_task_blocked_title to record.taskText
                else -> return
            }
            // R5 顺手批: provider errors can carry the request URL (and a
            // self-configured endpoint may carry a key in its query), and
            // answers can carry URLs or account fragments — all of it would
            // sit on the lockscreen. Same scrub the debug report ZIP uses,
            // before the text leaves the process.
            val trimmed = io.agents.nailongclaw.agent.langchain.http.HeaderMask
                .scrubText(text.trim())
                .take(200)
            // Tap → the main screen, same destination as the foreground-service
            // notification: a plain "open the app" gesture, no extras contract.
            val intent = Intent(context, io.agents.nailongclaw.ui.web.ClaudeUiPreviewActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                record.id.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                NOTIFY_BASE + record.id.hashCode() % 1000,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(context.getString(titleRes))
                    .setContentText(trimmed)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(trimmed))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: Exception) {
            XLog.w(TAG, "task outcome notification failed (record '${record.id}' still kept)", e)
        }
    }

    /** Called from [ClawApplication.onCreate] — one registration, process-wide. */
    fun register() {
        TaskRecordStore.addSink(this)
    }
}
