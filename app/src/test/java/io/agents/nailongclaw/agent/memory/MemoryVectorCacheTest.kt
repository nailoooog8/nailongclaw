// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the `memory_vectors.json` sidecar.
 *
 * What each case protects:
 *  - **fingerprint mismatch discards everything** — a model swap must not
 *    leave the old vector space in place; mixed spaces make every cosine
 *    comparison meaningless.
 *  - **dimension mismatch drops the entry** — same failure, one entry at a
 *    time (a hand-edited file, or a Matryoshka size change).
 *  - **corrupt JSON reads as empty** — the sidecar is derived data; losing it
 *    costs a recompute, never the bank itself.
 *  - **empty save deletes the file** — an all-pruned cache must not linger as
 *    a stale fingerprint that outlives the model.
 */
class MemoryVectorCacheTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-vector-cache-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val fp = "embeddinggemma-2-text-270m.litertlm:768"

    @Test
    fun `round-trips through the sidecar`() {
        val vectors = mapOf(
            "a" to floatArrayOf(0.5f, -0.25f, 0f),
            "b" to floatArrayOf(1f, 0f, -1f)
        )
        MemoryVectorCache.save(dir, fp, vectors)

        val loaded = MemoryVectorCache.load(dir, fp, 3)
        assertTrue(File(dir, "memory_vectors.json").exists())
        assertEquals(2, loaded.size)
        assertArrayEquals(vectors["a"], loaded["a"], 1e-6f)
        assertArrayEquals(vectors["b"], loaded["b"], 1e-6f)
    }

    @Test
    fun `a different fingerprint discards everything`() {
        MemoryVectorCache.save(dir, fp, mapOf("a" to floatArrayOf(1f, 0f)))

        val loaded = MemoryVectorCache.load(dir, "some-other-model.litertlm:768", 2)
        assertTrue("stale vectors from another model must not survive", loaded.isEmpty())
    }

    @Test
    fun `entries with the wrong dimension are dropped`() {
        MemoryVectorCache.save(
            dir, fp,
            mapOf("good" to floatArrayOf(1f, 2f), "bad" to floatArrayOf(1f, 2f, 3f))
        )

        val loaded = MemoryVectorCache.load(dir, fp, 2)
        assertEquals(1, loaded.size)
        assertArrayEquals(floatArrayOf(1f, 2f), loaded["good"], 1e-6f)
    }

    @Test
    fun `corrupt json reads as empty`() {
        File(dir, "memory_vectors.json").writeText("{ this is not json")
        assertTrue(MemoryVectorCache.load(dir, fp, 768).isEmpty())
    }

    @Test
    fun `missing file reads as empty`() {
        assertTrue(MemoryVectorCache.load(dir, fp, 768).isEmpty())
    }

    @Test
    fun `saving an empty map removes the file`() {
        MemoryVectorCache.save(dir, fp, mapOf("a" to floatArrayOf(1f)))
        assertTrue(File(dir, "memory_vectors.json").exists())

        MemoryVectorCache.save(dir, fp, emptyMap())
        assertFalse(File(dir, "memory_vectors.json").exists())
    }
}
