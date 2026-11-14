// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import android.content.Context
import android.os.StatFs
import io.agents.nailongclaw.agent.llm.LocalModelManager
import io.agents.nailongclaw.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Download and storage for the memory bank's embedding model — EmbeddingGemma 2
 * text (270M), Apache-2.0, shipped as a `.litertlm` file so it loads on the
 * same LiteRT-LM runtime as the local LLM.
 *
 * The download machinery deliberately mirrors [LocalModelManager] (HTTP Range
 * resume, temp-file + rename, throttled progress, free-space pre-check, size
 * tolerance) instead of extracting a shared helper: refactoring the working
 * LLM download path mid-release for ~100 lines of duplication is the worse
 * trade. If a third model type ever shows up, extract then.
 *
 * The file lands in the same `models/` directory as the LLM models
 * ([LocalModelManager.getModelDir]) — one place to inspect, one place to clean.
 */
object EmbeddingModelManager {

    private const val TAG = "EmbeddingModelMgr"

    const val MODEL_FILE_NAME = "embeddinggemma-2-text-270m.litertlm"

    private const val MODEL_URL =
        "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/main/$MODEL_FILE_NAME"

    /** Exact size from the HuggingFace API (LFS blob), used for the free-space
     *  pre-check and the ±5% completion validation. */
    private const val MODEL_SIZE_BYTES = 164_626_432L

    private const val SIZE_TOLERANCE_BYTES = 32L * 1024 * 1024

    /** Label shown in settings. */
    const val MODEL_DISPLAY_NAME = "EmbeddingGemma 2（270M 文本）"

    /**
     * Devices below this much RAM get no download button. The 270M encoder
     * needs a few hundred MB resident on top of the LLM engine; a 2 GB device
     * would thrash. Mirrors how [LocalModelManager] gates its big models.
     */
    private const val MIN_RAM_GB = 4

    private const val PROGRESS_INTERVAL_MS = 200L

    /**
     * Memoized absolute path of the downloaded model. [getModelPath] is on
     * MemoryStore's locked write path (via embedder()), and resolving the
     * directory prepares it (mkdir + write probe) — worth doing once, not
     * per memory write. Invalidated on download/delete; a file deleted behind
     * our back only means the embedder fails to initialize and ranking falls
     * back to lexical, which is self-healing.
     */
    @Volatile
    private var cachedPath: String? = null

    interface DownloadCallback {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long)
        fun onComplete(modelPath: String)
        fun onError(error: String)
    }

    fun isDeviceSupported(context: Context): Boolean =
        LocalModelManager.getDeviceRamGb(context) >= MIN_RAM_GB

    fun isDownloaded(context: Context): Boolean = getModelPath(context) != null

    /** Absolute path of the downloaded model, or null when absent. */
    fun getModelPath(context: Context): String? {
        cachedPath?.let { return it }
        val file = File(LocalModelManager.getModelDir(context), MODEL_FILE_NAME)
        val path = if (file.exists() && file.length() > 0) file.absolutePath else null
        cachedPath = path
        return path
    }

    /** Human-readable size for the settings row, e.g. "157 MB". */
    fun modelSizeLabel(): String = "${MODEL_SIZE_BYTES / 1_048_576L} MB"

    /**
     * Delete the model file. The embedder engine is released first: a live
     * native engine can hold the file open, and on some filesystems the delete
     * would silently fail.
     */
    fun deleteModel(context: Context): Boolean {
        val path = getModelPath(context)
        if (path != null) MemoryEmbedders.release(path)
        cachedPath = null
        val dir = LocalModelManager.getModelDir(context)
        File(dir, "$MODEL_FILE_NAME.downloading").delete()
        val file = File(dir, MODEL_FILE_NAME)
        return !file.exists() || file.delete()
    }

    /**
     * Download with progress. Supports resume via HTTP Range headers.
     * Must be called from a background thread.
     */
    fun downloadModel(context: Context, callback: DownloadCallback) {
        val modelDir = try {
            LocalModelManager.getModelDir(context)
        } catch (e: Exception) {
            XLog.e(TAG, "Could not prepare model storage", e)
            callback.onError("无法准备模型存储目录：${e.message}")
            return
        }
        val targetFile = File(modelDir, MODEL_FILE_NAME)
        val tempFile = File(modelDir, "$MODEL_FILE_NAME.downloading")
        cleanupInvalidFiles(targetFile, tempFile)

        try {
            val stat = StatFs(modelDir.absolutePath)
            val availableBytes = stat.availableBytes
            val existingTempBytes = if (tempFile.exists()) tempFile.length() else 0L
            val bytesNeeded = MODEL_SIZE_BYTES - existingTempBytes
            if (bytesNeeded > 0 && availableBytes < bytesNeeded) {
                val need = String.format("%.1f", bytesNeeded / 1_000_000_000.0)
                val have = String.format("%.1f", availableBytes / 1_000_000_000.0)
                XLog.e(TAG, "Not enough storage: need ${need}GB, have ${have}GB available")
                callback.onError("存储空间不足：需要 ${need} GB，仅剩 ${have} GB")
                return
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Could not check storage, proceeding anyway", e)
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L
            val requestBuilder = Request.Builder().url(MODEL_URL)
            if (existingBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                XLog.i(TAG, "Resuming download from byte $existingBytes")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 206) {
                callback.onError("下载失败：HTTP ${response.code}")
                return
            }

            val isResumedResponse = existingBytes > 0 && response.code == 206
            if (existingBytes > 0 && !isResumedResponse) {
                XLog.w(TAG, "Server ignored Range request; restarting download from scratch")
                tempFile.delete()
            }

            val totalBytes = if (isResumedResponse) {
                response.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                    ?: MODEL_SIZE_BYTES
            } else {
                response.body?.contentLength()?.takeIf { it > 0 } ?: MODEL_SIZE_BYTES
            }

            val body = response.body ?: run {
                callback.onError("下载响应为空")
                return
            }

            val startingBytes = if (isResumedResponse) existingBytes else 0L
            val outputStream = FileOutputStream(tempFile, isResumedResponse)
            val buffer = ByteArray(8192)
            var downloadedBytes = startingBytes
            var lastReportTime = System.currentTimeMillis()
            var lastReportedBytes = startingBytes

            body.byteStream().use { input ->
                outputStream.use { output ->
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime >= PROGRESS_INTERVAL_MS) {
                            val elapsed = (now - lastReportTime) / 1000.0
                            val speed = ((downloadedBytes - lastReportedBytes) / elapsed).toLong()
                            callback.onProgress(downloadedBytes, totalBytes, speed)
                            lastReportTime = now
                            lastReportedBytes = downloadedBytes
                        }
                    }
                }
            }

            if (!isValidFile(tempFile)) {
                tempFile.delete()
                callback.onError("下载的文件不完整或已损坏，请重试")
                return
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                callback.onError("下载完成，但无法把模型文件移入位")
                return
            }

            XLog.i(TAG, "Model downloaded: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
            cachedPath = targetFile.absolutePath
            callback.onComplete(targetFile.absolutePath)

        } catch (e: Exception) {
            XLog.e(TAG, "Download failed", e)
            callback.onError("下载失败：${e.message}")
        }
    }

    private fun cleanupInvalidFiles(targetFile: File, tempFile: File) {
        if (targetFile.exists() && !isValidFile(targetFile)) {
            XLog.w(TAG, "Removing invalid completed model file (${targetFile.length()} bytes)")
            targetFile.delete()
        }
        if (tempFile.exists() && tempFile.length() > expectedUpperBound()) {
            XLog.w(TAG, "Removing oversized partial download (${tempFile.length()} bytes)")
            tempFile.delete()
        }
    }

    private fun isValidFile(file: File): Boolean {
        if (!file.exists()) return false
        val length = file.length()
        if (length <= 0L) return false
        return length in expectedLowerBound()..expectedUpperBound()
    }

    private fun expectedLowerBound(): Long =
        (MODEL_SIZE_BYTES - maxOf(SIZE_TOLERANCE_BYTES, MODEL_SIZE_BYTES / 20)).coerceAtLeast(1L)

    private fun expectedUpperBound(): Long =
        MODEL_SIZE_BYTES + maxOf(SIZE_TOLERANCE_BYTES, MODEL_SIZE_BYTES / 20)
}
