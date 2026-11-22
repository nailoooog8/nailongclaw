// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * BACKLOG P2 — the import half of the memory round trip, against
 * [MemoryStore.exportJson]'s version-1 payload. The three invariants the
 * BACKLOG documents are each pinned here:
 *
 *  1. hits/lastUsedAt reset — device-local reinforcement does not travel;
 *  2. pinned travels — it is the user's explicit decision;
 *  3. suppression fingerprints travel — a fact deleted on device A must not
 *     be re-learned on device B.
 *
 * Plus the defensive half: refusals leave the bank untouched, and links
 * pointing outside bank ∪ payload are dropped, not planted.
 */
class MemoryImportJsonTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-import-test").toFile()
        MemoryStore.storageDirOverride = dir
        MemoryStore.enabledOverride = true
        MemoryStore.invalidate()
    }

    @After
    fun tearDown() {
        MemoryStore.awaitVectorBackfillForTest()
        MemoryStore.storageDirOverride = null
        MemoryStore.enabledOverride = null
        MemoryStore.invalidate()
        dir.deleteRecursively()
    }

    private fun remember(content: String, pinned: Boolean = false): MemoryStore.RememberResult.Stored {
        val r = MemoryStore.remember(content, pinned = pinned, fromAgent = false)
        assertTrue("expected Stored but was $r", r is MemoryStore.RememberResult.Stored)
        return r as MemoryStore.RememberResult.Stored
    }

    private fun exportText(): String {
        val file = MemoryStore.exportJson() ?: throw AssertionError("export must not be null")
        return file.readText()
    }

    @Test
    fun `round trip restores the chain, keeps pinned, resets retention`() {
        // The proven contradiction pair (MemoryStoreTest uses the same): the
        // skeleton matches and the values differ, so the temporal chain fires.
        remember("用户的手机是Pixel。")
        val newer = remember("用户的手机是小米。")
        remember("用户对咖啡因敏感", pinned = true)
        MemoryStore.recall("TPLINK") // retention state on the invalidated original

        val exported = exportText()

        // Device B: an empty bank imports the whole payload.
        val fresh = Files.createTempDirectory("memory-import-fresh").toFile()
        MemoryStore.storageDirOverride = fresh
        MemoryStore.invalidate()

        val report = MemoryStore.importJson(exported)
        assertNull("round trip must not be refused", report.refusedReason)
        assertEquals(3, report.imported)
        assertEquals(0, report.skippedDuplicates)

        // Contents restored — including the invalidated original (temporal chain).
        val all = MemoryStore.all()
        assertEquals(3, all.size)
        assertTrue(all.any { it.content.contains("Pixel") && it.isInvalidated })
        val live = all.first { it.content.contains("小米") }
        val original = all.first { it.content.contains("Pixel") }
        // Chain direction: the VICTIM carries invalidAt + supersededBy=claim;
        // the claim carries supersedes=[victim].
        assertTrue(
            "victim must stay invalidated with supersededBy=claim id",
            original.isInvalidated && original.supersededBy == live.id,
        )
        assertTrue("claim must carry supersedes=[victim]", live.supersedes.contains(original.id))
        assertTrue(all.first { it.content.contains("咖啡因") }.pinned)

        // Invariant 1: retention state does not travel.
        assertTrue("hits must reset", all.all { it.hits == 0 })
        assertTrue("lastUsedAt must reset", all.all { it.lastUsedAt == 0L })

        // And the live entry's id survived verbatim (content-hash of same text).
        assertEquals(newer.entry.id, live.id)
    }

    @Test
    fun `importing into a bank that already has an entry skips the duplicate`() {
        remember("用户的无线网络名称是TPLINK")
        remember("用户对咖啡因敏感")
        val exported = exportText() // payload: both entries

        remember("用户新增的一条记忆") // the bank now also has one entry the payload lacks

        val report = MemoryStore.importJson(exported)
        assertNull(report.refusedReason)
        assertEquals("the shared entries are skipped, not duplicated", 2, report.skippedDuplicates)
        assertEquals("nothing new came in — the payload was fully present", 0, report.imported)
        assertEquals(3, MemoryStore.all().size)
    }

    @Test
    fun `suppression fingerprints travel`() {
        remember("用户的邮箱是a@b.c")
        assertTrue("fixture needs the deletion", MemoryStore.deleteByContent("邮箱是") > 0)
        val exported = exportText()

        val fresh = Files.createTempDirectory("memory-import-suppression").toFile()
        MemoryStore.storageDirOverride = fresh
        MemoryStore.invalidate()

        val report = MemoryStore.importJson(exported)
        assertTrue(
            "suppression must be restored (invariant 3)",
            report.suppressions > 0,
        )
        assertEquals(1, MemoryStore.suppressionCount())

        // And it actually suppresses: the agent cannot re-learn the fact.
        // The suppression gate is agent-side by design (a user-facing write
        // bypasses it — the user is explicitly asking). Re-LEARNING is what
        // memory_save does, so drive that path.
        val relearn = MemoryStore.remember("用户的邮箱是a@b.c", fromAgent = true)
        assertTrue(
            "the re-learn must be refused by the fingerprint",
            relearn is MemoryStore.RememberResult.Suppressed,
        )
    }

    @Test
    fun `corrupt json is refused and leaves the bank untouched`() {
        remember("用户的无线网络名称是TPLINK")
        val before = MemoryStore.all().size

        assertTrue(
            "corrupt json must be refused",
            MemoryStore.importJson("this is not json").refusedReason != null,
        )
        assertEquals(before, MemoryStore.all().size)
    }

    @Test
    fun `wrong version is refused`() {
        val bad = """{"version":2,"entries":[],"suppressions":[]}"""
        val report = MemoryStore.importJson(bad)
        assertTrue(report.refusedReason!!.contains("version"))
    }

    @Test
    fun `payload over the bank cap is refused outright`() {
        val entries = (1..(MemoryStore.MAX_ENTRIES + 1)).joinToString(",") { i ->
            """{"id":"id$i","content":"memory number $i"}"""
        }
        val big = """{"version":1,"entries":[$entries],"suppressions":[]}"""
        val report = MemoryStore.importJson(big)
        assertTrue(report.refusedReason!!.contains("over the bank cap"))
        assertEquals("nothing was written", 0, MemoryStore.all().size)
    }

    @Test
    fun `links pointing outside the payload and bank are dropped`() {
        assertTrue("empty bank exports nothing", MemoryStore.exportJson() == null)

        // Hand-built payload: entry A links to ghostId, which exists nowhere.
        val payload = """
            {"version":1,"entries":[
              {"id":"aaaaaaaaaaaa","content":"entry with a dangling link","relatedIds":["ghost00000000"]},
              {"id":"bbbbbbbbbbbb","content":"entry with a clean link","relatedIds":["aaaaaaaaaaaa"]}
            ],"suppressions":[]}
        """.trimIndent()
        val report = MemoryStore.importJson(payload)
        assertNull(report.refusedReason)
        assertEquals(2, report.imported)
        assertEquals(1, report.droppedDanglingLinks)

        val a = MemoryStore.all().first { it.id == "aaaaaaaaaaaa" }
        assertTrue("dangling id dropped", a.relatedIds.isEmpty())
        val b = MemoryStore.all().first { it.id == "bbbbbbbbbbbb" }
        assertEquals("in-payload link survives", listOf("aaaaaaaaaaaa"), b.relatedIds)
    }
}
