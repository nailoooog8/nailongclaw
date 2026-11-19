// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

/**
 * What [WebActivity] is allowed to load.
 *
 * The screen enables JavaScript and loads whatever URL it is handed, so the
 * only thing standing between a caller and an `intent:`/`javascript:`/`file:`
 * page is this predicate. `WebActivity` has **zero callers today** — the
 * allowlist is not here because something is currently exploiting it, it is
 * here so that the first wiring change does not silently activate a screen
 * that can be pointed at any scheme (BACKLOG P3 · F10).
 *
 * Pure string parsing on purpose: no `android.net.Uri`, so the policy is
 * testable on the JVM without Robolectric.
 */
object WebUrlPolicy {

    private val SUPPORTED_SCHEMES = setOf("http", "https")

    /**
     * True only for an absolute `http`/`https` URL with a non-empty host.
     *
     * Everything else is refused, including:
     * - other schemes (`javascript:`, `file:`, `content:`, `intent:`,
     *   `market:`, the app's own deep links);
     * - scheme-relative and schemeless values (`//host`, `example.com`) —
     *   a schemeless string is a caller bug, and letting the WebView guess
     *   turns that bug into a navigation;
     * - malformed authorities (`http:/host`, `http://`, `http:///path`).
     */
    fun isSupported(url: String?): Boolean {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) return false

        val separator = candidate.indexOf(':')
        // No scheme at all, or an empty one (":foo").
        if (separator <= 0) return false

        val scheme = candidate.substring(0, separator).lowercase()
        if (scheme !in SUPPORTED_SCHEMES) return false

        val rest = candidate.substring(separator + 1)
        // Must be an authority form: "scheme://host…". "http:/host" is not.
        if (!rest.startsWith("//")) return false

        val authority = rest.removePrefix("//")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return authority.isNotBlank()
    }
}
