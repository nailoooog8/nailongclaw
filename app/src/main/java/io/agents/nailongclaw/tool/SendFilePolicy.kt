// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool

import java.io.File

/**
 * What `send_file` is allowed to read off the device.
 *
 * R3-04: the tool took any absolute path and handed the file to the current
 * channel, which made it the last hop of every injection chain — one successful
 * prompt injection (a poisoned web snippet, a planted memory) could ship the
 * app's private data directory (chat history, task log) or the MMKV file
 * holding the encrypted bot tokens straight out of the device.
 *
 * The policy is an allow-by-default, deny-by-root rule rather than a whitelist
 * of "interesting" directories: users legitimately send files from anywhere in
 * shared storage (DCIM, Download, a screenshot the agent just took), and
 * enumerating those per OEM is not maintainable. What must never leave is the
 * private-data tree, which is also exactly what a credential-harvesting payload
 * would reach for.
 *
 * Paths are canonicalized before the check so `..` and symlinks cannot walk out
 * of shared storage into the private tree (the same lesson as the ConfigServer
 * path-traversal fix in round one).
 */
object SendFilePolicy {

    /** Chat channels cap attachments far below this; refuse absurd uploads early. */
    const val MAX_BYTES: Long = 50L * 1024 * 1024

    /** Roots that hold app-private data, system internals, or other apps' code. */
    private val DENIED_ROOTS = listOf(
        "/data/",
        "/system/",
        "/proc/",
        "/dev/",
        "/apex/",
        "/vendor/",
        "/product/",
    )

    /**
     * True when [rawPath] may be read and sent.
     *
     * @param appInternalDir absolute path of the app's internal data directory
     *   (`context.dataDir`), denied explicitly as well as by root, so a device
     *   that reports it somewhere unexpected is still covered.
     */
    fun isAllowed(rawPath: String?, appInternalDir: String?): Boolean {
        val path = canonical(rawPath) ?: return false
        if (path == "/") return false
        if (!appInternalDir.isNullOrBlank()) {
            val internal = canonical(appInternalDir) ?: appInternalDir
            if (path == internal || path.startsWith("$internal/")) return false
        }
        return DENIED_ROOTS.none { path.startsWith(it) }
    }

    /** Human-readable reason for a refusal, or null when the path is allowed. */
    fun rejectReason(rawPath: String?, appInternalDir: String?): String? {
        if (isAllowed(rawPath, appInternalDir)) return null
        val path = canonical(rawPath)
        return when {
            path == null -> "Unreadable path."
            path == "/" -> "The filesystem root cannot be sent."
            !appInternalDir.isNullOrBlank() &&
                (path == canonical(appInternalDir) || path.startsWith("${canonical(appInternalDir)}/")) ->
                "That path is inside NailongClaw's private data (credentials and chat history live there) " +
                    "and cannot be sent."
            DENIED_ROOTS.any { path.startsWith(it) } ->
                "That path is inside a protected system or app-private directory and cannot be sent."
            else -> "That path cannot be sent."
        }
    }

    /**
     * Normalizes a path into the form the root rules are written against.
     *
     * On the device this is a near no-op (Android paths are already `/`-rooted
     * and `File.canonicalPath` has folded any `..` and symlink). The separator
     * folding and drive-letter strip exist because the unit tests run on the
     * Windows dev host, where `File("/data/...").canonicalPath` comes back as
     * `C:\data\...` — without them the rules would silently never fire there and
     * the suite would pass while checking nothing.
     */
    private fun normalize(raw: String): String {
        var path = raw.replace('\\', '/')
        // Drop a Windows drive prefix ("C:") so the same rules apply on both hosts.
        if (path.length >= 2 && path[1] == ':') path = path.substring(2)
        while (path.contains("//")) path = path.replace("//", "/")
        if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        return path
    }

    private fun canonical(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            normalize(File(trimmed).canonicalPath)
        } catch (_: Exception) {
            normalize(File(trimmed).absolutePath)
        }
    }
}
