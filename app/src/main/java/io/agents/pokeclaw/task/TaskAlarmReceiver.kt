// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.agents.pokeclaw.utils.XLog

/**
 * The alarm's landing point: wake up, hand the id to [ScheduleDispatcher],
 * get out of the way.
 *
 * Exported=false and reachable only through the explicit [TaskScheduler]
 * PendingIntent — no other app can forge a fire. Dispatch work happens off
 * the main thread via `goAsync()` because starting a task can initialize the
 * agent (a local model load is seconds, and a broadcast's main-thread budget
 * is ten).
 */
class TaskAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TaskAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskScheduler.ACTION_FIRE) return
        val taskId = intent.getStringExtra(TaskScheduler.EXTRA_TASK_ID)
        if (taskId.isNullOrBlank()) {
            XLog.w(TAG, "fire without a task id — ignored")
            return
        }
        val pending = goAsync()
        Thread({
            try {
                ScheduleDispatcher.onAlarm(context.applicationContext, taskId)
            } catch (e: Exception) {
                XLog.e(TAG, "dispatching '$taskId' failed", e)
            } finally {
                pending.finish()
            }
        }, "sched-fire-$taskId").start()
    }
}
