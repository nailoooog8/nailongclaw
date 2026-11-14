// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import com.google.gson.Gson
import io.agents.nailongclaw.utils.XLog
import java.io.File

/**
 * Sidecar vector store for the memory bank: `memory_vectors.json` next to
 * `memories.json` in [io.agents.nailongclaw.agent.memory.MemoryStore]'s
 * storage dir, following the same lazy-load + atomic tmp+rename pattern as
 * `memory_suppressions.json`.
 *
 * **Why a sidecar and not a field on [MemoryEntry]:** MemoryEntry has no
 * `toJson` — persistence is Gson reflection over the data class, so any new
 * field lands in the hand-editable `memories.json` (the file users adb-push
 * and diff). Vectors are derived data; they stay out of the source of truth.
 *
 * **Keying:** entry id, which is a SHA-256 prefix of the normalized content.
 * Editing a memory changes its content, hence its id, hence the old vector is
 * orphaned and pruned on the next save — no explicit invalidation logic, and
 * no schema migration when the model changes either: a different
 * [fingerprint][io.agents.nailongclaw.agent.memory.MemoryEmbedder.fingerprint]
 * discards the whole file.
 */
object MemoryVectorCache {

    private const val TAG = "MemoryVectorCache"
    private const val FILE_NAME = "memory_vectors.json"

    // Compact on purpose: pretty-printing 200 × 768 floats would balloon the
    // file to several MB. The vectors are machine data; the human-readable
    // view of the bank is memories.json and the export.
    private val gson = Gson()

    /**
     * Makes every [save] write to its own temp file. A shared temp name let
     * two concurrent savers interleave bytes into one file, producing a
     * corrupt document that parses as empty — every vector silently lost.
     * (In production all saves run under MemoryStore's lock, but a file API
     * that corrupts itself under concurrency is a trap, not a constraint.)
     */
    private val tmpCounter = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Load the cached vectors, or an empty map when the file is missing,
     * corrupt, from a different model ([fingerprint] mismatch), or holds
     * entries whose length differs from [dim]. Every one of those cases means
     * "recompute", and a corrupt file must never take the bank down with it.
     */
    fun load(dir: File, fingerprint: String, dim: Int): MutableMap<String, FloatArray> {
        val file = File(dir, FILE_NAME)
        if (!file.exists()) return mutableMapOf()
        return try {
            val root = gson.fromJson(file.readText(), com.google.gson.JsonObject::class.java)
                ?: return mutableMapOf()
            if (root.get("fingerprint")?.asString != fingerprint) {
                XLog.i(TAG, "Vector cache fingerprint mismatch; discarding (will recompute)")
                return mutableMapOf()
            }
            val vectors = root.getAsJsonObject("vectors") ?: return mutableMapOf()
            val out = mutableMapOf<String, FloatArray>()
            for ((id, el) in vectors.entrySet()) {
                val arr = el.asJsonArray ?: continue
                if (arr.size() != dim) continue
                val vec = FloatArray(dim) { arr[it].asFloat }
                out[id] = vec
            }
            out
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to read $FILE_NAME; treating as empty", e)
            mutableMapOf()
        }
    }

    /** Atomic write (tmp + rename), same failure handling as MemoryStore.persistLocked. */
    fun save(dir: File, fingerprint: String, vectors: Map<String, FloatArray>) {
        if (vectors.isEmpty()) {
            delete(dir)
            return
        }
        try {
            if (!dir.exists()) dir.mkdirs()
            val payload = gson.toJson(
                mapOf("fingerprint" to fingerprint, "vectors" to vectors)
            )
            val tmp = File(dir, "$FILE_NAME.tmp.${tmpCounter.getAndIncrement()}")
            tmp.writeText(payload)
            val target = File(dir, FILE_NAME)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                // Same fallback as MemoryStore: some devices refuse the rename
                // but accept a direct write.
                target.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to save $FILE_NAME", e)
        }
    }

    fun delete(dir: File) {
        try {
            File(dir, FILE_NAME).delete()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to delete $FILE_NAME", e)
        }
    }
}
