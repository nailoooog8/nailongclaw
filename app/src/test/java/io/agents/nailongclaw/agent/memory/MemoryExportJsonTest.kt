// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * BACKLOG P1 — the lossless JSON export. The markdown export is a
 * human-readable share that keeps only content/tags/pinned; the JSON one must
 * carry everything a future import needs: retention state, the temporal chain,
 * graph links, and the forgetting-suppression fingerprints. Vectors must stay
 * out — they are a device-local cache keyed by embedder fingerprint.
 */
class MemoryExportJsonTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-export-json").toFile()
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

    @Test
    fun `export carries every entry field the markdown one drops`() {
        remember("用户的无线网络名称是TPLINK")
        // A contradicting write builds the temporal chain (supersedes/supersededBy).
        remember("用户的无线网络名称是TL-WR886N")
        remember("用户对咖啡因敏感", pinned = true)
        // Retention state: a recall hit buys hits + lastUsedAt.
        MemoryStore.recall("TPLINK")

        val file = MemoryStore.exportJson() ?: throw AssertionError("export must not be null")
        val json = file.readText()

        // Both live entries plus the invalidated original (temporal chain, not deletion).
        assertEquals(3, Regex("\"id\"").findAll(json).count())
        assertTrue("supersedes chain must travel", json.contains("\"supersedes\""))
        assertTrue("supersededBy must travel", json.contains("\"supersededBy\""))
        assertTrue("invalidAt must travel", json.contains("\"invalidAt\""))
        assertTrue("retention state must travel", json.contains("\"hits\""))
        assertTrue("lastUsedAt must travel", json.contains("\"lastUsedAt\""))
        assertTrue("the pinned entry is the coffee one", json.contains("咖啡因敏感"))
    }

    @Test
    fun `suppression fingerprints travel with the export`() {
        remember("用户的邮箱是a@b.c")
        // deleteByContent refuses queries shorter than 3 chars (stray-forget
        // guard) — query with 3+ characters.
        val removed = MemoryStore.deleteByContent("邮箱是")
        assertTrue("fixture needs one suppression", removed > 0)

        val file = MemoryStore.exportJson() ?: throw AssertionError("export must not be null")
        val json = file.readText()
        assertTrue("suppressions must travel", json.contains("\"suppressions\""))
        assertTrue("the fingerprint text must be in the export", json.contains("邮箱"))
    }

    @Test
    fun `vectors never ride in the export`() {
        remember("用户的无线网络名称是TPLINK")
        val file = MemoryStore.exportJson() ?: throw AssertionError("export must not be null")
        val json = file.readText()
        assertFalse("no vector payload", json.contains("\"vector"))
        assertFalse("no sidecar fingerprint key", json.contains("\"fingerprint\""))
    }

    @Test
    fun `empty bank and no suppressions exports nothing`() {
        assertTrue(MemoryStore.exportJson() == null)
    }
}
