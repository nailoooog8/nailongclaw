// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

/**
 * Text embedder behind the memory bank's semantic ranking.
 *
 * Lexical ranking (CJK bigrams, [MemorySimilarity]) cannot recall a memory
 * stored in different words than the question asks in — "上次 WiFi 怎么配的"
 * never matches a memory written as "无线网络". Embeddings close exactly that
 * gap and nothing else: the governance half of the bank (suppression
 * fingerprints, contradiction detection, the retention curve) stays lexical
 * and rule-based on purpose (docs/MEMORY_DESIGN.md §2, §7).
 *
 * Two rules every implementation must keep:
 *
 *  1. **Degrade to null, never throw.** A missing model file or a runtime
 *     that refuses to load costs recall quality, never correctness — the
 *     caller falls back to pure lexical ranking.
 *  2. **Never called while [MemoryStore]'s lock is held.** Inference is
 *     milliseconds to hundreds of milliseconds; the lock guards disk I/O on
 *     a 200-entry JSON file and must stay sub-millisecond.
 */
interface MemoryEmbedder {

    /** Embedding dimensionality; meaningless when [available] is false. */
    val dimension: Int

    /**
     * False when the model is absent or the runtime failed to initialize.
     * Callers must treat every entry as unvectorized in that case.
     */
    val available: Boolean

    /**
     * Identity of the model+configuration that produced the vectors, e.g.
     * "embeddinggemma-2-text-270m.litertlm:768". [MemoryVectorCache] drops
     * everything whose fingerprint differs, so a model swap cannot silently
     * mix vector spaces.
     */
    val fingerprint: String

    /** Embed [text]; null on any failure. Never called while MemoryStore's lock is held. */
    fun embed(text: String): FloatArray?
}

/**
 * Cosine similarity in [-1, 1]; 0 when either side is degenerate or the
 * dimensions differ. Norms are computed even though embedding models usually
 * emit unit vectors — the cost is one pass, and a non-normalized vector then
 * silently becomes a dot product.
 */
internal fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        val x = a[i].toDouble()
        val y = b[i].toDouble()
        dot += x * y
        normA += x * x
        normB += y * y
    }
    if (normA <= 0.0 || normB <= 0.0) return 0f
    return (dot / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))).toFloat()
}
