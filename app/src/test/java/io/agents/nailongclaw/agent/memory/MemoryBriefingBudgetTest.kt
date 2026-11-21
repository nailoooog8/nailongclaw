// Copyright 2026 NailongClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the total character budget of [MemoryStore.briefing].
 *
 * The briefing used to have two half-budgets: the injected entries were
 * capped at a fixed char count, but the rules block and the count footer
 * grew with the code, and a single oversized entry could blow through the
 * entry cap anyway. On the local model (8192-token window) that mattered:
 * the briefing is part of the fixed cost that pushed requests past the
 * window. Now one budget covers entries + rules together, and when it runs
 * out the memory text yields first while the rules block stays intact.
 *
 * Every case here runs on the test seams (`storageDirOverride`, `clock`,
 * `enabledOverride`) with a fixed clock, so ranking is deterministic and
 * the picked subset is reproducible.
 */
class MemoryBriefingBudgetTest {

    private lateinit var dir: File
    private var now = 1_788_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-brief-budget-test").toFile()
        MemoryStore.storageDirOverride = dir
        MemoryStore.clock = { now }
        MemoryStore.enabledOverride = true
        MemoryStore.invalidate()
    }

    @After
    fun tearDown() {
        MemoryStore.storageDirOverride = null
        MemoryStore.clock = { System.currentTimeMillis() }
        MemoryStore.enabledOverride = null
        MemoryStore.invalidate()
        dir.deleteRecursively()
    }

    private fun stored(result: MemoryStore.RememberResult): MemoryStore.RememberResult.Stored {
        assertTrue("expected Stored but was $result", result is MemoryStore.RememberResult.Stored)
        return result as MemoryStore.RememberResult.Stored
    }

    /**
     * A filler memory of an exact char length. The per-entry token uses
     * letters only (no digits): digits are the value slots that
     * MemorySimilarity.contradicts keys on, and two fillers sharing a
     * skeleton with different numbers would supersede each other mid-test.
     * The shared prefix stops at 4 chars, below slotSwaps' 5-char minimum.
     */
    private fun filler(index: Int, targetChars: Int): String {
        val token = "${'a' + index / 26}${'a' + index % 26}"
        var s = "记忆样本$token：一条用于预算测试的记忆，写的是日常琐事，" +
            "比如天气晴朗、附近新开了一家小店、周末散步的路线等等。"
        while (s.length < targetChars) s += "再补几句细节让长度凑够，避免出现完全相同的长句。"
        return s.take(targetChars)
    }

    private fun rememberFillers(count: Int, targetChars: Int): List<MemoryEntry> =
        (0 until count).map { i ->
            stored(MemoryStore.remember(filler(i, targetChars))).entry
        }

    // ── Small banks: behaviour unchanged ──────────────────────────────────────

    @Test
    fun `a small bank injects every entry, the rules, and stays inside the budget`() {
        val entries = rememberFillers(3, 60)

        val brief = MemoryStore.briefing("预算测试")

        for (e in entries) assertTrue("entry must be injected: ${e.id}", brief.contains(e.content))
        assertTrue(brief.contains("记忆使用规则"))
        assertTrue(brief.contains("不要硬凑"))
        assertTrue(
            "briefing (${brief.length}) must fit the total budget ${MemoryStore.BRIEFING_TOTAL_CHAR_BUDGET}",
            brief.length <= MemoryStore.BRIEFING_TOTAL_CHAR_BUDGET
        )
    }

    @Test
    fun `an empty bank states the fact and omits the rules block`() {
        val brief = MemoryStore.briefing("anything")
        assertTrue(brief.contains("记忆库目前没有仍有效的记忆"))
        assertFalse(brief.contains("记忆使用规则"))
    }

    // ── Over budget: memory text yields first ─────────────────────────────────

    @Test
    fun `over budget the entries are cut while the rules block stays intact`() {
        val entries = rememberFillers(count = 30, targetChars = 180)

        val brief = MemoryStore.briefing("预算测试")

        // Rules are the teaching block — never trimmed, head and tail both in.
        assertTrue(brief.contains("0. <memory>…</memory> 之间是记忆正文"))
        assertTrue(brief.contains("不要硬凑"))
        // The injected lines are ranked and cut to what fits…
        assertTrue(brief.contains(filler(0, 180)))
        assertFalse("low-ranked fillers must be dropped, not all injected", brief.contains(filler(29, 180)))
        // …and the whole section stays inside the total budget.
        assertTrue(
            "briefing (${brief.length}) must fit the total budget ${MemoryStore.BRIEFING_TOTAL_CHAR_BUDGET}",
            brief.length <= MemoryStore.BRIEFING_TOTAL_CHAR_BUDGET
        )
        assertEquals(
            "no entry may be dropped from the bank by a briefing cut",
            entries.size,
            MemoryStore.count()
        )
    }

    @Test
    fun `a pinned memory survives budget pressure`() {
        val pinned = stored(
            MemoryStore.remember("用户置顶的偏好：咖啡只喝美式，不加糖。", pinned = true)
        ).entry
        rememberFillers(count = 30, targetChars = 180)

        val brief = MemoryStore.briefing("预算测试")

        assertTrue("pinned entries outrank the fillers and must be injected", brief.contains(pinned.content))
    }

    // ── One oversized entry: truncated head, disk untouched ───────────────────

    @Test
    fun `a single memory larger than the whole budget is truncated, not dropped`() {
        val giant = rememberFillers(1, 6000).single()

        val brief = MemoryStore.briefing("预算测试")

        // The head is injected behind the fence, cut where the budget ends.
        assertTrue("the truncated head must be injected", brief.contains(giant.content.take(30)))
        assertTrue("the truncation marker must be visible to the model", brief.contains("…</memory>"))
        assertFalse("no content beyond the budget may leak", brief.contains(giant.content.take(5000)))
        assertTrue(
            "briefing (${brief.length}) must fit the total budget ${MemoryStore.BRIEFING_TOTAL_CHAR_BUDGET}",
            brief.length <= MemoryStore.BRIEFING_TOTAL_CHAR_BUDGET
        )
        // The budget governs the prompt, never the stored fact.
        val onDisk = MemoryStore.findById(giant.id)
        assertNotNull(onDisk)
        assertEquals(6000, onDisk!!.content.length)
    }

    @Test
    fun `briefing accounting uses the rendered line, not a content-only estimate`() {
        // Tags and conflict markers are part of the injected line; the budget
        // counts the whole line so the section stays bounded as the format
        // grows. A tagged entry counts its tag chars too.
        val tagged = stored(MemoryStore.remember("用户喜欢喝美式咖啡，工作日上午一杯。", tags = listOf("daily", "coffee"))).entry
        rememberFillers(count = 30, targetChars = 180)

        val brief = MemoryStore.briefing("美式 coffee")

        assertTrue(brief.contains(tagged.content))
        assertTrue("tags render inside the line and count toward the budget", brief.contains("（daily、coffee）"))
        assertTrue(
            "briefing (${brief.length}) must fit the total budget ${MemoryStore.BRIEFING_TOTAL_CHAR_BUDGET}",
            brief.length <= MemoryStore.BRIEFING_TOTAL_CHAR_BUDGET
        )
    }

    // ── entriesBudget: the reservation arithmetic ─────────────────────────────

    @Test
    fun `entriesBudget reserves the fixed chrome and stays positive`() {
        val small = MemoryStore.entriesBudget(5)
        assertTrue("entries budget must leave room for memories", small > 0)
        assertTrue(
            "entries budget must sit below the total — the rules block and chrome are reserved first",
            small < MemoryStore.BRIEFING_TOTAL_CHAR_BUDGET
        )

        // The footer is the only chrome whose length moves with the entry
        // count — one extra digit at the 9→10 boundary costs one char.
        assertEquals(MemoryStore.entriesBudget(9), MemoryStore.entriesBudget(10) + 1)
        assertEquals(MemoryStore.entriesBudget(1), MemoryStore.entriesBudget(9))
        // The budget is the room for the rendered entry lines, not a per-entry
        // reservation, so a huge bank never drives it negative — the footer's
        // digit growth is the only thing that shrinks it ("100000" has five
        // more digits than "9").
        assertEquals(
            "an absurd count must never go negative",
            MemoryStore.entriesBudget(9) - 5,
            MemoryStore.entriesBudget(100_000)
        )
        assertTrue(MemoryStore.entriesBudget(Int.MAX_VALUE) >= 0)
    }
}
