// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.notify

import io.agents.nailongclaw.channel.Channel
import io.agents.nailongclaw.task.TaskRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the notify matrix of [TaskOutcomeNotifier.shouldNotify]: the whole
 * double-notification defence rests on SCHEDULED being excluded here (its
 * dispatcher already notifies), and the walked-away LOCAL case resting on the
 * foreground flag.
 */
class TaskOutcomeNotifierTest {

    // ---- Only terminal statuses notify ----

    @Test
    fun `non-terminal statuses never notify`() {
        for (status in listOf(TaskRecord.Status.RUNNING, TaskRecord.Status.WAITING_CONFIRM)) {
            assertFalse(TaskOutcomeNotifier.shouldNotify(Channel.LOCAL, appForeground = false, status))
        }
    }

    @Test
    fun `all terminal statuses can notify`() {
        for (status in TaskRecord.TERMINAL_STATUSES) {
            assertTrue(
                "EXTERNAL should notify on $status",
                TaskOutcomeNotifier.shouldNotify(Channel.EXTERNAL, appForeground = true, status),
            )
        }
    }

    // ---- SCHEDULED is the double-notification exclusion ----

    @Test
    fun `scheduled never notifies - its dispatcher already does`() {
        for (status in TaskRecord.TERMINAL_STATUSES) {
            assertFalse(
                TaskOutcomeNotifier.shouldNotify(Channel.SCHEDULED, appForeground = false, status),
            )
        }
    }

    // ---- LOCAL only speaks up when the user walked away ----

    @Test
    fun `local notifies only when app is in background`() {
        assertTrue(
            TaskOutcomeNotifier.shouldNotify(Channel.LOCAL, appForeground = false, TaskRecord.Status.COMPLETED),
        )
        assertFalse(
            TaskOutcomeNotifier.shouldNotify(Channel.LOCAL, appForeground = true, TaskRecord.Status.COMPLETED),
        )
    }

    // ---- Unattended channels always notify ----

    @Test
    fun `external and channel tasks notify even with app foreground`() {
        for (channel in listOf(
            Channel.EXTERNAL, Channel.DISCORD, Channel.TELEGRAM,
            Channel.WECHAT, Channel.QQ, Channel.DOUYIN,
        )) {
            assertTrue(
                "$channel should notify regardless of foreground",
                TaskOutcomeNotifier.shouldNotify(channel, appForeground = true, TaskRecord.Status.FAILED),
            )
        }
    }
}
