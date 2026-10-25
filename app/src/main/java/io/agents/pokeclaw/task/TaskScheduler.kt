// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.task

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.agents.pokeclaw.utils.XLog

/**
 * Alarm wiring for scheduled tasks — the timer half; [ScheduledTaskStore]
 * holds the durable intent, this makes the OS wake us at the moment.
 *
 * Chosen over WorkManager on purpose: a 07:30 morning brief must fire at
 * 07:30, not "within a flexibility window when the system feels like it".
 * AlarmManager's exact API gives that, and the manifest already carries
 * SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM plus RECEIVE_BOOT_COMPLETED for the
 * re-arm path ([rearmAll] from BootReceiver — alarms do not survive reboots).
 *
 * Honest degradation: when exact alarms are not permitted
 * ([AlarmManager.canScheduleExactAlarms]), the task is still armed —
 * inexact — and the gap is visible in `adb` / battery settings, not silently
 * absorbed.
 */
object TaskScheduler {

    private const val TAG = "TaskScheduler"

    const val ACTION_FIRE = "io.agents.pokeclaw.SCHEDULED_TASK_FIRE"
    const val EXTRA_TASK_ID = "scheduled_task_id"

    /** Arm (or re-arm) the alarm for one live task. */
    @JvmStatic
    fun arm(context: Context, task: ScheduledTask) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            XLog.e(TAG, "arm: no AlarmManager — '${task.id}' will not fire")
            return
        }
        val pi = firePendingIntent(context, task.id)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.fireAtMillis, pi)
        } else {
            XLog.w(TAG, "arm: exact alarms not permitted — '${task.id}' may be deferred by Doze")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.fireAtMillis, pi)
        }
        XLog.i(TAG, "arm: '${task.id}' at ${task.fireAtMillis} (exact=$canExact)")
    }

    @JvmStatic
    fun disarm(context: Context, taskId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(firePendingIntent(context, taskId))
    }

    /**
     * After boot (or a store resync): reconcile what is still live — skipping
     * occurrences the device slept through — and arm every survivor.
     */
    @JvmStatic
    fun rearmAll(context: Context) {
        val live = ScheduledTaskStore.reconcileAfterReboot()
        for (task in live) {
            arm(context, task)
        }
        XLog.i(TAG, "rearmAll: ${live.size} live scheduled task(s) armed")
    }

    private fun firePendingIntent(context: Context, taskId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            Intent(context, TaskAlarmReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra(EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
