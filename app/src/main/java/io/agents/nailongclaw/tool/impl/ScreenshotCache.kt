// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl

import io.agents.nailongclaw.utils.XLog
import java.io.File

/**
 * Retention policy for the screenshot cache (`cacheDir/screenshots/`).
 *
 * Both real-screen ([TakeScreenshotTool]) and shadow-screen
 * ([ShadowGetScreenInfoTool]) captures write PNGs here with no cleanup, and
 * `file_paths.xml` exposes the cache root to FileProvider — an unbounded pile
 * of screen captures that every share sheet can see (R5 §5 P3). Prune runs on
 * every write, so no separate scheduler is needed and the cost is one
 * directory listing per capture.
 *
 * The bounds are generous on purpose: QA reads screenshots out of this
 * directory as evidence, so newest N survive well past a session, while a
 * months-old pile does not.
 */
object ScreenshotCache {

    private const val TAG = "ScreenshotCache"

    /** Captures older than this are deleted on the next write. */
    private const val RETENTION_MS = 24L * 60 * 60 * 1000

    /** And even fresh ones beyond this count are deleted, newest kept. */
    private const val MAX_FILES = 20

    /** Prunes [dir] by age and count; never throws — a failed prune must not
     *  fail the capture that triggered it. */
    @JvmStatic
    fun prune(dir: File) {
        try {
            val files = dir.listFiles() ?: return
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val stale = files.filter { it.isFile && it.lastModified() < cutoff }
            stale.forEach { it.delete() }
            val survivors = dir.listFiles()?.filter { it.isFile } ?: return
            if (survivors.size > MAX_FILES) {
                survivors.sortedByDescending { it.lastModified() }
                    .drop(MAX_FILES)
                    .forEach { it.delete() }
            }
            if (stale.isNotEmpty()) {
                XLog.i(TAG, "Pruned ${stale.size} stale screenshot(s) from ${dir.name}")
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Screenshot cache prune failed: ${e.message}")
        }
    }
}
