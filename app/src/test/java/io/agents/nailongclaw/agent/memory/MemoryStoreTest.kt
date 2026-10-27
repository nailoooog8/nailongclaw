// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the order-sensitive invariants inside [MemoryStore].
 *
 * These could not be tested before `storageDirOverride` and `clock` existed:
 * every invariant lived in a private method and read the wall clock or a
 * `Context` path. Both seams default to production behaviour, so the class is
 * unchanged when no test is running.
 *
 * What each case protects, and why it matters:
 *  - **suppression before merge** — the check is the only thing stopping a
 *    forgotten fact from being merged straight back in. Reordering the two
 *    blocks would silently reinstate every deleted memory.
 *  - **two-sided conflictsWith** — a dangling id on the surviving side points
 *    the model at a memory that no longer exists.
 *  - **eviction leaves no fingerprint** — running out of room is not evidence
 *    the user stopped wanting something. A fingerprint here would make the
 *    agent permanently unable to re-learn facts nobody asked it to forget.
 *  - **id is derived from content** — editing content is a new identity.
 */
class MemoryStoreTest {

    private lateinit var dir: File
    private var now = 1_788_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-store-test").toFile()
        MemoryStore.storageDirOverride = dir
        MemoryStore.clock = { now }
        // briefing() gates on the enabled flag, which lives in MMKV and does
        // not exist in a JVM test.
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

    private fun rememberAndStore(
        content: String,
        kind: String = MemoryKind.FACT,
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
        fromAgent: Boolean = false
    ): MemoryStore.RememberResult.Stored = stored(
        MemoryStore.remember(content, kind, tags, pinned, fromAgent = fromAgent)
    )

    /** 0→a, 25→z, 26→aa … distinct lowercase ids for eviction fillers. */
    private fun letterIndex(i: Int): String {
        var n = i
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('a' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    // ── Suppression must run before the near-duplicate merge ──────────────────

    @Test
    fun `an agent re-adding a forgotten memory is refused`() {
        val e = rememberAndStore("用户每周跑3次。")
        assertTrue(MemoryStore.delete(e.entry.id))

        val again = MemoryStore.remember("用户每周跑3次。", fromAgent = true)
        assertTrue("should be suppressed, was $again", again is MemoryStore.RememberResult.Suppressed)
        assertEquals(0, MemoryStore.count())
    }

    @Test
    fun `suppression is checked BEFORE the merge, so overlap cannot reinstate it`() {
        // The whole point of the ordering. This forgotten fact has a live
        // sibling that overlaps it heavily. If the merge ran first, the new
        // text would be folded into the sibling and returned as Stored --
        // quietly putting the forgotten fact back, which is the exact outcome
        // suppression exists to prevent.
        val sibling = rememberAndStore("用户每周跑3次，跑步能让心情好些。")
        val forgotten = rememberAndStore("用户每周跑3次。")
        assertTrue(MemoryStore.delete(forgotten.entry.id))
        assertEquals(1, MemoryStore.count())

        val retry = MemoryStore.remember("用户每周跑3次。", fromAgent = true)

        assertTrue(
            "overlapping re-add must be refused, was $retry",
            retry is MemoryStore.RememberResult.Suppressed
        )
        // And the sibling must be untouched: no merge, no hits bump.
        val stillThere = MemoryStore.findById(sibling.entry.id)
        assertNotNull(stillThere)
        assertEquals(0, stillThere!!.hits)
    }

    @Test
    fun `a manual edit is exempt from suppression`() {
        val e = rememberAndStore("用户每周跑3次。")
        assertTrue(MemoryStore.delete(e.entry.id))

        // The user asking for it back outranks the fingerprint: that is the
        // documented carve-out, and it is why `fromAgent` exists at all.
        rememberAndStore("用户每周跑3次。", fromAgent = false)
        assertEquals(1, MemoryStore.count())
        // The fingerprint is deliberately NOT lifted -- only restoreSuppression()
        // does that. A manual re-add satisfies the user without erasing the
        // record that they asked for this to be forgotten once.
        assertEquals(1, MemoryStore.suppressionCount())
    }

    @Test
    fun `a blocked write bumps the fingerprint's blocked counter`() {
        val e = rememberAndStore("用户每周跑3次。")
        assertTrue(MemoryStore.delete(e.entry.id))
        repeat(3) { MemoryStore.remember("用户每周跑3次。", fromAgent = true) }

        val traces = MemoryStore.suppressions()
        assertEquals(1, traces.size)
        assertEquals(3, traces.single().blocked)
    }

    @Test
    fun `restoring a forgotten memory lifts its fingerprint`() {
        val e = rememberAndStore("用户每周跑3次。", kind = MemoryKind.PREFERENCE)
        val id = e.entry.id
        assertTrue(MemoryStore.delete(id))
        assertEquals(1, MemoryStore.suppressionCount())

        assertTrue(MemoryStore.restoreSuppression(id))
        assertEquals(0, MemoryStore.suppressionCount())
        assertEquals(1, MemoryStore.count())
        // The kind travels, so a restore does not flatten everything to "fact".
        assertEquals(MemoryKind.PREFERENCE, MemoryStore.all().single().kind)
    }

    @Test
    fun `clearing suppressions lets the agent learn the fact again`() {
        val e = rememberAndStore("用户每周跑3次。")
        assertTrue(MemoryStore.delete(e.entry.id))
        assertEquals(1, MemoryStore.suppressionCount())

        assertEquals(1, MemoryStore.clearSuppressions())
        rememberAndStore("用户每周跑3次。", fromAgent = true)
        assertEquals(1, MemoryStore.count())
    }

    // ── Temporal supersession (graphiti's validity interval) ─────────────────

    @Test
    fun `a contradiction supersedes the older fact instead of coexisting with it`() {
        // The swapped value must be something the lexical matcher can see as a
        // slot (a number, version or quoted span). "用户在杭州工作。" ->
        // "用户在上海工作。" does NOT fire: there is no value marker, so it
        // reads as a plain word swap. That limit is deliberate and already
        // pinned by MemorySimilarityTest.
        val old = rememberAndStore("用户的手机是Pixel。")
        val neu = stored(MemoryStore.remember("用户的手机是小米。"))

        // The write reports what it displaced — an unreported invalidation
        // would be the silent adjudication this design refuses.
        assertEquals(1, neu.superseded.size)
        assertEquals(old.entry.id, neu.superseded.single().id)
        assertTrue(neu.conflicts.isEmpty())

        val victim = MemoryStore.findById(old.entry.id)!!
        assertTrue("the superseded fact must be invalidated, not deleted", victim.isInvalidated)
        assertEquals(neu.entry.id, victim.supersededBy)
        assertEquals(listOf(old.entry.id), MemoryStore.findById(neu.entry.id)!!.supersedes)
        // A superseded entry's conflict flags die with the claim they
        // described: ⚠ next to 已作废 would re-open what time already settled.
        assertEquals(emptyList<String>(), victim.conflictsWith)
    }

    @Test
    fun `a pinned memory is flagged, never auto-superseded`() {
        val pinnedOld = rememberAndStore("用户的手机是Pixel。", pinned = true)
        val neu = stored(MemoryStore.remember("用户的手机是小米。"))

        // A pin is the user's explicit override — time may not invalidate it.
        assertTrue(neu.superseded.isEmpty())
        assertEquals(1, neu.conflicts.size)
        val still = MemoryStore.findById(pinnedOld.entry.id)!!
        assertFalse("pinned entries must stay valid", still.isInvalidated)
        assertEquals(listOf(neu.entry.id), still.conflictsWith)
        assertEquals(listOf(pinnedOld.entry.id), neu.entry.conflictsWith)
    }

    @Test
    fun `event-kind contradictions keep the honest flag pair instead of superseding`() {
        // Events accumulate: "5月去东京" and "6月去东京" share a frame but are
        // two different happenings, not one claim updated. Lexical matching
        // cannot tell those apart (graphiti would use LLM extraction; we
        // cannot), so time must not adjudicate them.
        val may = rememberAndStore("用户5月去了东京旅行。", kind = MemoryKind.EVENT)
        val june = stored(MemoryStore.remember("用户6月去了东京旅行。", kind = MemoryKind.EVENT))

        assertTrue(june.superseded.isEmpty())
        assertEquals(listOf(may.entry.id), june.entry.conflictsWith)
        val mayEntry = MemoryStore.findById(may.entry.id)!!
        assertFalse("events must stay live", mayEntry.isInvalidated)
        assertEquals(listOf(june.entry.id), mayEntry.conflictsWith)
    }

    @Test
    fun `the briefing injects only currently-valid memories`() {
        rememberAndStore("用户的手机是Pixel。")
        rememberAndStore("用户的手机是小米。")

        val brief = MemoryStore.briefing("手机 型号")
        assertTrue("live fact must be injected", brief.contains("小米"))
        assertFalse("superseded fact must not sit next to its replacement", brief.contains("Pixel"))
    }

    @Test
    fun `recall excludes superseded entries unless history is requested`() {
        rememberAndStore("用户的手机是Pixel。")
        rememberAndStore("用户的手机是小米。")

        val live = MemoryStore.recall("手机")
        assertEquals(1, live.size)
        assertTrue(live.single().entry.content.contains("小米"))

        val history = MemoryStore.recall("手机", includeHistory = true)
        assertEquals(2, history.size)
    }

    @Test
    fun `recalling history does not reinforce the dead entries`() {
        val old = rememberAndStore("用户的手机是Pixel。")
        rememberAndStore("用户的手机是小米。")

        MemoryStore.recall("手机", includeHistory = true)

        val dead = MemoryStore.findById(old.entry.id)!!
        assertEquals("history must not buy retention for a dead entry", 0, dead.hits)
    }

    @Test
    fun `restoreSuperseded swaps the pair instead of reviving both`() {
        val old = rememberAndStore("用户的手机是Pixel。")
        val neu = rememberAndStore("用户的手机是小米。")

        assertTrue(MemoryStore.restoreSuperseded(old.entry.id))

        val revived = MemoryStore.findById(old.entry.id)!!
        val displaced = MemoryStore.findById(neu.entry.id)!!
        assertFalse("restored entry is valid again", revived.isInvalidated)
        assertTrue("the entry that replaced it is invalidated in turn", displaced.isInvalidated)
        assertEquals(old.entry.id, displaced.supersededBy)
        assertTrue(revived.supersedes.contains(neu.entry.id))
        // And the live half of the pair is exactly one memory again.
        assertEquals(1, MemoryStore.all().count { !it.isInvalidated })
    }

    @Test
    fun `restoreSuperseded refuses a live or unknown entry`() {
        val e = rememberAndStore("用户在杭州工作。")
        assertFalse(MemoryStore.restoreSuperseded(e.entry.id))
        assertFalse(MemoryStore.restoreSuperseded("no-such-id"))
    }

    @Test
    fun `restoring through a chain supersedes the live tail`() {
        // A→B→C with C live. The old id-based swap only looked at A's direct
        // superseder B (dead), so reviving A left BOTH A and C live and
        // unflagged — the state temporal supersession exists to prevent.
        val a = rememberAndStore("用户的手机是Pixel。")
        val b = rememberAndStore("用户的手机是小米。")
        val c = rememberAndStore("用户的手机是华为。")

        assertTrue(MemoryStore.restoreSuperseded(a.entry.id))

        val revived = MemoryStore.findById(a.entry.id)!!
        val middle = MemoryStore.findById(b.entry.id)!!
        val tail = MemoryStore.findById(c.entry.id)!!
        assertFalse("restored head is live", revived.isInvalidated)
        assertTrue("dead middle stays history", middle.isInvalidated)
        assertTrue("live tail is superseded by the restored head", tail.isInvalidated)
        assertEquals(a.entry.id, tail.supersededBy)
        assertTrue(revived.supersedes.contains(c.entry.id))
        assertEquals("exactly one live fact remains", 1, MemoryStore.all().count { !it.isInvalidated })
    }

    @Test
    fun `re-asserting a superseded fact reactivates it and supersedes its replacement`() {
        val pixel = rememberAndStore("用户的手机是Pixel。")
        rememberAndStore("用户的手机是小米。")
        assertEquals(1, MemoryStore.all().count { !it.isInvalidated })

        // The user says Pixel again. Same content → same id, so this must
        // reactivate the dead entry rather than duplicate the id, and the live
        // 小米 must lose its claim in turn.
        val again = rememberAndStore("用户的手机是Pixel。")

        assertEquals(pixel.entry.id, again.entry.id)
        assertEquals(1, MemoryStore.all().count { it.id == pixel.entry.id })
        assertFalse(again.entry.isInvalidated)
        assertTrue(MemoryStore.findById(again.entry.id)!!.supersedes.isNotEmpty())
        assertEquals(1, MemoryStore.all().count { !it.isInvalidated })
    }

    @Test
    fun `editing a superseded entry restores it to valid`() {
        val old = rememberAndStore("用户的手机是Pixel。")
        rememberAndStore("用户的手机是小米。")

        stored(
            MemoryStore.remember(
                content = "用户的手机是Pixel 8。",
                id = old.entry.id
            )
        )

        val edited = MemoryStore.findById(old.entry.id)!!
        assertFalse("a manual edit is the newest statement — it comes back valid", edited.isInvalidated)
        assertEquals("用户的手机是Pixel 8。", edited.content)
    }

    @Test
    fun `eviction drops invalidated entries before live ones and leaves no fingerprint`() {
        rememberAndStore("用户的手机是Pixel。")
        val live = rememberAndStore("用户的手机是小米。")
        rememberAndStore("用户喜欢喝美式。")
        // Letter-only fillers: templated NUMBERS would share one skeleton and
        // supersede each other into a chain, and Unicode-indexed characters
        // collide under lowercasing (both fold filler writes into merges).
        // Pure a-z indices keep every filler a distinct, unconnected fact.
        repeat(MemoryStore.MAX_ENTRIES + 20) {
            rememberAndStore("用户记得第${letterIndex(it)}件不相干的小事。")
        }

        assertEquals(MemoryStore.MAX_ENTRIES, MemoryStore.count())
        assertEquals(0, MemoryStore.suppressionCount())
        // Dead history is the cheapest thing to give up — it can never enter a
        // briefing again — so it must not crowd out live memories.
        assertEquals(0, MemoryStore.invalidatedCount())
        assertNotNull("the live replacement must survive the pressure", MemoryStore.findById(live.entry.id))
    }

    // ── Supersession chains are pruned on every removal path ─────────────────

    @Test
    fun `deleting the superseder prunes the victim's link but leaves it restorable`() {
        val old = rememberAndStore("用户的手机是Pixel。")
        val neu = rememberAndStore("用户的手机是小米。")

        assertTrue(MemoryStore.delete(neu.entry.id))

        val victim = MemoryStore.findById(old.entry.id)!!
        // Deleting the newer fact is not a ruling that the older one was
        // right — that is restoreSuperseded's job — but the dangling id must
        // still go, or the chain points at a memory that no longer exists.
        assertTrue(victim.isInvalidated)
        assertEquals("", victim.supersededBy)
        // …and with the link gone, restoring is still possible.
        assertTrue(MemoryStore.restoreSuperseded(victim.id))
    }

    @Test
    fun `deleting the superseded entry repairs the survivor's chain`() {
        val old = rememberAndStore("用户的手机是Pixel。")
        val neu = rememberAndStore("用户的手机是小米。")

        assertTrue(MemoryStore.delete(old.entry.id))

        assertEquals(
            emptyList<String>(),
            MemoryStore.findById(neu.entry.id)!!.supersedes
        )
    }

    // ── Eviction deliberately leaves no fingerprint ──────────────────────────

    @Test
    fun `evicting over the cap leaves no suppression fingerprint`() {
        // Fill past the cap with clearly distinct facts.
        MemoryStore.clear()
        repeat(MemoryStore.MAX_ENTRIES + 20) {
            rememberAndStore("用户记得第${it}件不相干的小事，编号${it * 7}。")
        }
        assertEquals(MemoryStore.MAX_ENTRIES, MemoryStore.count())

        // Nothing was deleted by the user, so nothing may be suppressed. An
        // eviction fingerprint would permanently forbid the agent from
        // re-learning facts the user never asked it to forget.
        assertEquals(0, MemoryStore.suppressionCount())
        assertTrue(
            "the agent must still be able to re-learn an evicted fact",
            MemoryStore.remember("用户记得第5000件不相干的小事，编号35000。", fromAgent = true)
                is MemoryStore.RememberResult.Stored
        )
    }

    @Test
    fun `eviction keeps pinned entries even though they rank lowest`() {
        MemoryStore.clear()
        val pinned = rememberAndStore("用户的置顶记忆，内容很长用于避免与其它记忆重合。", pinned = true)
        repeat(MemoryStore.MAX_ENTRIES + 20) {
            rememberAndStore("用户记得第${it}件不相干的小事，编号${it * 7}。")
        }
        assertEquals(MemoryStore.MAX_ENTRIES, MemoryStore.count())
        assertNotNull("pinned entry must survive eviction", MemoryStore.findById(pinned.entry.id))
    }

    // ── id derives from content ───────────────────────────────────────────────

    @Test
    fun `the same content always yields the same id`() {
        val a = rememberAndStore("用户的通勤方式是地铁。")
        MemoryStore.clear()
        MemoryStore.invalidate()
        val b = rememberAndStore("用户的通勤方式是地铁。")
        assertEquals(a.entry.id, b.entry.id)
    }

    @Test
    fun `identical content collapses onto one entry via the merge path`() {
        // The id is a hash of normalized content, so re-adding the same
        // sentence after the entry is gone cannot resurrect the old identity
        // -- it recreates the same one, and the near-duplicate merge means no
        // second row appears.
        val first = rememberAndStore("用户喜欢喝美式。")
        MemoryStore.clear()
        val second = rememberAndStore("用户喜欢喝美式。")
        assertEquals(first.entry.id, second.entry.id)
        assertEquals(1, MemoryStore.count())
    }

    // ── Duplicate merging ─────────────────────────────────────────────────────

    @Test
    fun `a near-duplicate merges instead of creating a second entry`() {
        val first = rememberAndStore("用户喜欢喝美式咖啡。")
        val second = stored(MemoryStore.remember("用户喜欢喝美式咖啡", tags = listOf("daily")))

        assertTrue("expected a merge", second.merged)
        assertEquals(1, MemoryStore.count())
        // Tags accumulate across merges rather than being replaced.
        assertEquals(listOf("daily"), second.entry.tags)
        assertEquals(first.entry.id, second.entry.id)
    }

    // ── deleteByContent guards against stray input ───────────────────────────

    @Test
    fun `a very short delete query is refused outright`() {
        rememberAndStore("用户在杭州工作。")
        rememberAndStore("用户喜欢喝美式。")

        // Each victim leaves a permanent fingerprint, so a stray 1-2 char
        // "forget" would wipe the bank and block those memories forever.
        assertEquals(0, MemoryStore.deleteByContent("工"))
        assertEquals(0, MemoryStore.deleteByContent(" "))
        assertEquals(2, MemoryStore.count())
        assertEquals(0, MemoryStore.suppressionCount())
    }

    @Test
    fun `deleteByContent removes matches and suppresses them`() {
        rememberAndStore("用户每周跑3次。")
        rememberAndStore("用户喜欢喝美式。")

        assertEquals(1, MemoryStore.deleteByContent("跑3次"))
        assertEquals(1, MemoryStore.count())
        assertEquals(1, MemoryStore.suppressionCount())
    }

    @Test
    fun `deleteByContent also matches tags`() {
        rememberAndStore("用户有一台备用机。", tags = listOf("device"))
        assertEquals(1, MemoryStore.deleteByContent("device"))
        assertEquals(0, MemoryStore.count())
    }

    @Test
    fun `deleting an unknown id reports failure rather than throwing`() {
        rememberAndStore("用户在杭州工作。")
        assertFalse(MemoryStore.delete("no-such-id"))
        assertEquals(1, MemoryStore.count())
    }

    // ── Pinning and clearing ──────────────────────────────────────────────────

    @Test
    fun `setPinned updates an entry and rejects an unknown id`() {
        val e = rememberAndStore("用户在杭州工作。")
        assertFalse(MemoryStore.setPinned("no-such-id", true))
        assertTrue(MemoryStore.setPinned(e.entry.id, true))
        assertTrue(MemoryStore.findById(e.entry.id)!!.pinned)
    }

    @Test
    fun `clear empties the bank and its fingerprints together`() {
        rememberAndStore("用户在杭州工作。")
        val e = rememberAndStore("用户喜欢喝美式。")
        assertTrue(MemoryStore.delete(e.entry.id))

        assertEquals(1, MemoryStore.clear())
        assertEquals(0, MemoryStore.count())
        assertEquals(0, MemoryStore.suppressionCount())
    }

    @Test
    fun `remember refuses blank content`() {
        val error = runCatching { MemoryStore.remember("   ") }.exceptionOrNull()
        assertNotNull("blank content should be rejected", error)
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `an unknown kind is stored as fact rather than rejected`() {
        val e = rememberAndStore("用户在杭州工作。", kind = "nonsense")
        assertEquals(MemoryKind.FACT, e.entry.kind)
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    @Test
    fun `entries survive a reload from disk`() {
        rememberAndStore("用户在杭州工作。", kind = MemoryKind.PROFILE, pinned = true)
        // Drop the in-memory cache so the next read parses the file.
        MemoryStore.invalidate()

        val reloaded = MemoryStore.all().single()
        assertEquals("用户在杭州工作。", reloaded.content)
        assertEquals(MemoryKind.PROFILE, reloaded.kind)
        assertTrue(reloaded.pinned)
    }

    @Test
    fun `a corrupt memories file degrades to an empty bank instead of throwing`() {
        rememberAndStore("用户在杭州工作。")
        File(dir, "memories.json").writeText("{ this is not json")

        MemoryStore.invalidate()
        assertEquals(0, MemoryStore.count())
    }

    @Test
    fun `the clock seam drives written timestamps`() {
        now = 1_600_000_000_000L
        val e = rememberAndStore("用户在杭州工作。")
        assertEquals(1_600_000_000_000L, e.entry.createdAt)
        assertEquals(1_600_000_000_000L, e.entry.updatedAt)
    }

    @Test
    fun `no memories file is written for a bank that was never written to`() {
        assertNull(MemoryStore.findById("anything"))
        assertFalse(File(dir, "memories.json").exists())
    }
}
