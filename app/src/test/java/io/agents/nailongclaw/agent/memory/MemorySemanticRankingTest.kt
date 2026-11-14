// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for hybrid (semantic) ranking in [MemoryStore], driven by a fake
 * embedder through the `embedderOverride` seam — no MMKV, no model file, no
 * LiteRT runtime.
 *
 * The fake maps each text onto a sparse vector over synonym classes
 * ("wifi"/"无线"/"热点" all light dimension 0), which is exactly the shape
 * the real EmbeddingGemma output has for this purpose: same meaning, same
 * neighbourhood, zero shared characters.
 *
 * What each case protects:
 *  - **the paraphrase is the whole point** — the bigram ranker cannot match
 *    "WiFi" against "无线网络"; with the embedder on, that recall works, and
 *    with it off nothing regresses.
 *  - **the floor** — a vectorized-but-unrelated entry is not a hit; without
 *    the floor a recall would return the entire bank, ranked.
 *  - **per-entry fallback** — an entry whose vector is missing (fresh write,
 *    failed backfill) still ranks lexically rather than invisibly.
 *  - **merge drops the stale vector** — the merge path keeps the original id
 *    while replacing the text, so the id-keyed prune cannot see it.
 *
 * What these do NOT prove: that EmbeddingGemma's vectors are any good. That
 * is a device measurement (BACKLOG H-ticket); a JVM test can only prove the
 * plumbing uses the signal it is given.
 *
 * Sequencing note: entries are written with the override unset so no
 * background backfill races the assertions; the fake is installed afterwards
 * and the sidecar seeded by hand, which is exactly the state a completed
 * backfill leaves behind.
 */
class MemorySemanticRankingTest {

    private lateinit var dir: File

    /** Deterministic stand-in for EmbeddingGemma: synonym classes → dimensions. */
    private class FakeEmbedder(
        override val dimension: Int = 3,
        override val fingerprint: String = "fake-embedder:3"
    ) : MemoryEmbedder {
        override val available: Boolean = true

        override fun embed(text: String): FloatArray? {
            val t = text.lowercase()
            val v = FloatArray(dimension)
            SYNONYM_CLASSES.forEachIndexed { i, words ->
                if (words.any { it in t }) v[i] = 1f
            }
            return v
        }
    }

    private val fake = FakeEmbedder()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-semantic-test").toFile()
        MemoryStore.storageDirOverride = dir
        MemoryStore.enabledOverride = true
        MemoryStore.embedderOverride = null
        MemoryStore.invalidate()
    }

    @After
    fun tearDown() {
        // Drain the backfill daemon FIRST: MemoryStore is a process-global
        // singleton, and a pass still running would keep writing into
        // whatever storageDirOverride the next test installs.
        MemoryStore.awaitVectorBackfillForTest()
        MemoryStore.storageDirOverride = null
        MemoryStore.enabledOverride = null
        MemoryStore.embedderOverride = null
        MemoryStore.invalidate()
        dir.deleteRecursively()
    }

    private fun rememberAndStore(content: String): MemoryStore.RememberResult.Stored {
        val result = MemoryStore.remember(content, fromAgent = false)
        assertTrue("expected Stored but was $result", result is MemoryStore.RememberResult.Stored)
        return result as MemoryStore.RememberResult.Stored
    }

    /** Seeds the sidecar the way a completed backfill would have. */
    private fun seedVector(id: String, vec: FloatArray) {
        val existing = MemoryVectorCache.load(dir, fake.fingerprint, fake.dimension)
        existing[id] = vec
        MemoryVectorCache.save(dir, fake.fingerprint, existing)
    }

    // ── The paraphrase is the whole point ────────────────────────────────────

    @Test
    fun `semantic recall finds the paraphrase the bigram ranker cannot`() {
        val wifi = rememberAndStore("用户的无线网络名称是TPLINK")
        val coffee = rememberAndStore("用户喜欢深烘咖啡豆") // orthogonal: same bank, different topic
        MemoryStore.embedderOverride = fake
        seedVector(wifi.entry.id, floatArrayOf(1f, 0f, 0f))
        MemoryStore.invalidate() // force the sidecar to be re-read from disk

        val hits = MemoryStore.recall("WiFi 怎么连")

        assertEquals("only the wireless memory is a hit", 1, hits.size)
        assertTrue(hits[0].entry.content.contains("无线网络"))
    }

    @Test
    fun `with the embedder off the same recall finds nothing, as before`() {
        // Override stays null: the documented default must be untouched.
        rememberAndStore("用户的无线网络名称是TPLINK")
        rememberAndStore("用户喜欢深烘咖啡豆")

        assertTrue(
            "lexical mode must be unchanged: no shared bigram, no hit",
            MemoryStore.recall("WiFi 怎么连").isEmpty()
        )
    }

    @Test
    fun `briefing puts the paraphrase above the orthogonal entry`() {
        val wifi = rememberAndStore("用户的无线网络名称是TPLINK")
        val coffee = rememberAndStore("用户喜欢深烘咖啡豆")
        MemoryStore.embedderOverride = fake
        seedVector(wifi.entry.id, floatArrayOf(1f, 0f, 0f))
        seedVector(coffee.entry.id, floatArrayOf(0f, 0f, 1f))
        MemoryStore.invalidate()

        val briefing = MemoryStore.briefing("WiFi 怎么连")

        val wifiAt = briefing.indexOf("无线网络")
        val coffeeAt = briefing.indexOf("咖啡")
        assertTrue("both entries are in the small-bank briefing", wifiAt >= 0 && coffeeAt >= 0)
        assertTrue("the semantic hit must rank first", wifiAt < coffeeAt)
    }

    // ── The floor ────────────────────────────────────────────────────────────

    @Test
    fun `a vectorized but orthogonal entry is not a recall hit`() {
        val coffee = rememberAndStore("用户喜欢深烘咖啡豆")
        MemoryStore.embedderOverride = fake
        seedVector(coffee.entry.id, floatArrayOf(0f, 0f, 1f))
        MemoryStore.invalidate()

        assertTrue(MemoryStore.recall("WiFi 怎么连").isEmpty())
    }

    // ── Per-entry fallback ───────────────────────────────────────────────────

    @Test
    fun `an entry with no cached vector still ranks lexically`() {
        // Semantic mode is on, but this entry was never backfilled — the
        // lexical branch must carry it rather than dropping it silently.
        rememberAndStore("用户每周跑步三次，在早上执行")
        MemoryStore.embedderOverride = fake
        MemoryStore.invalidate()

        val hits = MemoryStore.recall("跑步")
        assertEquals(1, hits.size)
        assertTrue(hits[0].entry.content.contains("跑步"))
    }

    // ── Background backfill ──────────────────────────────────────────────────

    @Test
    fun `writes schedule a background backfill that persists the sidecar`() {
        MemoryStore.embedderOverride = fake
        rememberAndStore("用户的无线网络名称是TPLINK")
        rememberAndStore("用户喜欢深烘咖啡豆")

        // The pass runs on a daemon executor; poll the FILE (not the store's
        // in-memory map) so the assertion cannot race the final save.
        val deadline = System.currentTimeMillis() + 5_000
        while (MemoryVectorCache.load(dir, fake.fingerprint, fake.dimension).size < 2 &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }

        val onDisk = MemoryVectorCache.load(dir, fake.fingerprint, fake.dimension)
        assertEquals("both entries must be indexed", 2, onDisk.size)
        assertTrue("sidecar must be on disk", File(dir, "memory_vectors.json").exists())

        // And the freshly backfilled vectors make the paraphrase recall work
        // with no manual seeding.
        MemoryStore.invalidate()
        val hits = MemoryStore.recall("WiFi 怎么连")
        assertEquals(1, hits.size)
        assertTrue(hits[0].entry.content.contains("无线网络"))
    }

    // ── Merge invalidates the vector ─────────────────────────────────────────

    @Test
    fun `merging new text into a memory re-embeds it under the same id`() {
        val original = rememberAndStore("用户的无线网络名称是TPLINK")
        MemoryStore.embedderOverride = fake
        seedVector(original.entry.id, floatArrayOf(1f, 0f, 0f))
        MemoryStore.invalidate()

        // The UI-edit / agent re-save path: overlapping content merges into
        // the existing entry, keeping its id but replacing its text.
        val merged = rememberAndStore("用户的无线网络名称是TPLINK，密码是8位数字")
        assertTrue("expected a merge", merged.merged)
        assertEquals("the merge keeps the original id", original.entry.id, merged.entry.id)

        // The stale vector must be gone, and the backfill must re-embed the
        // merged text — which now lights the 密码 dimension too.
        val deadline = System.currentTimeMillis() + 5_000
        var vec: FloatArray? = null
        while (System.currentTimeMillis() < deadline) {
            vec = MemoryVectorCache.load(dir, fake.fingerprint, fake.dimension)[original.entry.id]
            if (vec != null && vec[1] > 0f) break
            Thread.sleep(20)
        }
        assertNotNull("the merged entry must be re-embedded", vec)
        assertTrue(
            "the vector must describe the new wording, not the stale one",
            vec!![1] > 0f
        )
    }

    private companion object {
        /** Synonym classes lighting one dimension each — the fake's vocabulary. */
        val SYNONYM_CLASSES = listOf(
            listOf("wifi", "无线", "热点"),   // dim 0: wireless
            listOf("密码", "password"),       // dim 1: password
            listOf("咖啡"),                   // dim 2: coffee
        )
    }
}
