// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.task.TaskRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sink is a doorbell, not a reader: both callbacks must ring it, and it
 * must not touch the store or a WebView itself (the owner does both, on the
 * right thread). Pinned here so a future "optimisation" cannot quietly turn
 * it into a second read path.
 */
class WebTaskLedgerSinkTest {

    @Test
    fun `both callbacks ring the bell`() {
        var rings = 0
        val sink = WebTaskLedgerSink { rings += 1 }
        val record = TaskRecord(
            id = "t1",
            taskText = "do a thing",
            channel = Channel.LOCAL,
            originActivity = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        sink.onTaskRecordAdded(record)
        sink.onTaskRecordUpdated(record)
        sink.onTaskRecordUpdated(record)
        assertEquals(3, rings)
    }

    @Test
    fun `the record itself is not read by the sink`() {
        // 回调签名收了 record，但实现不看它—— payload 由 owner 决定去桥里
        // 取全量快照。这里用断言固定「doorbell」语义：传什么都不会改变行为。
        var rings = 0
        val sink = WebTaskLedgerSink { rings += 1 }
        sink.onTaskRecordUpdated(
            TaskRecord(
                id = "other",
                taskText = "unread",
                channel = Channel.WECHAT,
                originActivity = "x",
                createdAt = 9L,
                updatedAt = 9L,
            )
        )
        assertEquals(1, rings)
    }
}
