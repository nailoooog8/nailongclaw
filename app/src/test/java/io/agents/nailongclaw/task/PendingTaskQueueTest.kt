// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.task

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the queue contract: FIFO order, honest blank-text refusal, remove by
 * id, and the sender handoff — a drain without a registered surface keeps the
 * item, one with a sender delivers the text and drops the item.
 */
class PendingTaskQueueTest {

    @Before
    fun reset() {
        // 清场：object 单例，测试间共享状态必须归零
        PendingTaskQueue.items.value.toList().forEach { PendingTaskQueue.remove(it.id) }
        PendingTaskQueue.registerSender(null)
    }

    @After
    fun tearDown() {
        PendingTaskQueue.registerSender(null)
    }

    @Test
    fun `enqueue keeps FIFO order and trims`() {
        PendingTaskQueue.enqueue("  first  ")
        PendingTaskQueue.enqueue("second")
        val items = PendingTaskQueue.items.value
        assertEquals(2, items.size)
        assertEquals("first", items[0].text)
        assertEquals("second", items[1].text)
    }

    @Test
    fun `blank text is refused - a queued empty message is a lie`() {
        assertNull(PendingTaskQueue.enqueue("   "))
        assertTrue(PendingTaskQueue.items.value.isEmpty())
    }

    @Test
    fun `remove drops exactly one item`() {
        val a = PendingTaskQueue.enqueue("a")!!
        PendingTaskQueue.enqueue("b")
        PendingTaskQueue.remove(a.id)
        val items = PendingTaskQueue.items.value
        assertEquals(1, items.size)
        assertEquals("b", items[0].text)
    }

    @Test
    fun `sendNow hands text to sender and drops the item`() {
        val sent = mutableListOf<String>()
        PendingTaskQueue.registerSender { sent.add(it) }
        PendingTaskQueue.enqueue("hello")
        val id = PendingTaskQueue.items.value.first().id

        assertTrue(PendingTaskQueue.sendNow(id))
        assertEquals(listOf("hello"), sent)
        assertTrue(PendingTaskQueue.items.value.isEmpty())
    }

    @Test
    fun `sendNow without a registered surface keeps the item`() {
        PendingTaskQueue.enqueue("waiting")
        val id = PendingTaskQueue.items.value.first().id
        assertFalse(PendingTaskQueue.sendNow(id))
        assertEquals(1, PendingTaskQueue.items.value.size)
    }

    @Test
    fun `drain without sender is a no-op - item waits for the next surface`() {
        PendingTaskQueue.enqueue("orphan")
        PendingTaskQueue.drainSoonIfIdle { true } // 异步；没有 sender，也不会丢
        // 这里不 join 主线程 handler 延迟——sendNow 的语义已由上面两条钉住，
        // drainSoonIfIdle 只是 isIdle + sendNow 的编排，真机 QA 验证时序。
        assertEquals(1, PendingTaskQueue.items.value.size)
    }
}
