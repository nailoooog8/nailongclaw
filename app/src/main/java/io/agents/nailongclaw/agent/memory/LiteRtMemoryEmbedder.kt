// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import io.agents.nailongclaw.utils.XLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device embedder over LiteRT-LM's [EmbeddingEngine] — the same runtime the
 * local LLM uses (litertlm-android), so semantic ranking adds no second
 * inference stack. Model artifact: EmbeddingGemma 2 text (270M), Apache-2.0,
 * distributed as a `.litertlm` file (see [EmbeddingModelManager]).
 *
 * **CPU on purpose.** [io.agents.nailongclaw.agent.llm.LocalModelRuntime]
 * already puts the LLM engine on the GPU delegate when the device allows it;
 * a second GPU delegate for the embedder would contend for the same memory
 * exactly when the background backfill overlaps generation. CPU inference of
 * a quantized 270M encoder is tens-to-hundreds of milliseconds — measured on
 * the QA device before any reconsideration (BACKLOG H-ticket).
 *
 * **Lifecycle.** The engine is loaded lazily on the first [embed] and kept
 * resident: model load dominates per-call cost, and the bank embeds one query
 * per task turn plus a background backfill after writes. An idle engine is
 * released on the next access (check-on-access, no timer thread), and
 * [release] is called explicitly when the model file is deleted from
 * settings — otherwise the file handle can outlive the delete.
 */
internal class LiteRtMemoryEmbedder(
    private val modelPath: String,
    private val cacheDir: String?,
    override val dimension: Int = EmbeddingGemma.DIMENSION
) : MemoryEmbedder {

    override val fingerprint: String get() = "${File(modelPath).name}:$dimension"

    override val available: Boolean get() = engine != null

    @Volatile
    private var engine: EmbeddingEngine? = null

    @Volatile
    private var lastUsedAt: Long = 0L

    override fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        val e = engineOrCreate() ?: return null
        return try {
            // normalize=true makes cosine a plain dot product and keeps the
            // sidecar cache numerically stable across runs.
            val vec = e.computeEmbedding(
                listOf(InputData.Text(text)),
                EmbeddingOptions(normalize = true)
            ).embedding
            lastUsedAt = System.currentTimeMillis()
            if (vec.size != dimension) {
                // A dimension change (model swap, Matryoshka truncation) must
                // not reach the cache: mixed-length vectors would poison every
                // cosine comparison. Fail closed, fall back to lexical.
                XLog.e(TAG, "embedding dim ${vec.size} != declared $dimension; ignoring")
                null
            } else {
                vec
            }
        } catch (ex: Exception) {
            XLog.w(TAG, "computeEmbedding failed", ex)
            null
        }
    }

    /** Drop the engine so the model file can be deleted and memory reclaimed. */
    fun release() {
        synchronized(this) {
            val e = engine ?: return
            engine = null
            try {
                e.close()
            } catch (ex: Exception) {
                XLog.w(TAG, "engine close failed", ex)
            }
        }
    }

    @Synchronized
    private fun engineOrCreate(): EmbeddingEngine? {
        // Idle release: a briefing burst is seconds long; five idle minutes
        // means the user is not in a task loop any more.
        val e = engine
        if (e != null) {
            if (System.currentTimeMillis() - lastUsedAt > IDLE_RELEASE_MS) {
                XLog.d(TAG, "embedding engine idle > ${IDLE_RELEASE_MS / 1000}s; releasing")
                release()
            } else {
                return e
            }
        }
        return try {
            EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDir
                )
            ).also {
                it.initialize()
                engine = it
                lastUsedAt = System.currentTimeMillis()
                XLog.i(TAG, "embedding engine initialized: $fingerprint")
            }
        } catch (ex: Exception) {
            // Missing file, unsupported ABI, OOM — all the same to the caller:
            // lexical fallback, and the next call retries the load.
            XLog.w(TAG, "embedding engine init failed ($modelPath)", ex)
            null
        }
    }

    private companion object {
        const val TAG = "MemoryEmbedder"
        const val IDLE_RELEASE_MS = 5 * 60 * 1000L
    }
}

/**
 * Process-wide embedder instances keyed by model path, so the settings UI and
 * the store share one engine (and one model load) per file.
 */
internal object MemoryEmbedders {

    private val instances = ConcurrentHashMap<String, LiteRtMemoryEmbedder>()

    fun get(modelPath: String, cacheDir: String?): MemoryEmbedder =
        instances.getOrPut(modelPath) { LiteRtMemoryEmbedder(modelPath, cacheDir) }

    /** Called before the model file is deleted, so no engine holds it open. */
    fun release(modelPath: String) {
        instances.remove(modelPath)?.release()
    }
}

/** Facts about the shipped embedding model, in one place for UI + download. */
internal object EmbeddingGemma {
    const val DIMENSION = 768
}
