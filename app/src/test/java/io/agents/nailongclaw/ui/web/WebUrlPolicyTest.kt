// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowlist that keeps `WebActivity` — a JavaScript-enabled screen with no
 * callers today — from being pointed at an arbitrary scheme (BACKLOG P3 · F10).
 *
 * Pure string cases on purpose: the policy deliberately does not use
 * `android.net.Uri`, so these run on the JVM and any platform quirk in URL
 * parsing shows up here rather than on a device.
 */
class WebUrlPolicyTest {

    @Test
    fun `plain http and https are supported`() {
        assertTrue(WebUrlPolicy.isSupported("http://example.com"))
        assertTrue(WebUrlPolicy.isSupported("https://example.com"))
        assertTrue(WebUrlPolicy.isSupported("https://example.com/a/b?c=d#e"))
        assertTrue(WebUrlPolicy.isSupported("https://example.com:8443/path"))
        assertTrue(WebUrlPolicy.isSupported("https://user:pass@example.com/path"))
    }

    @Test
    fun `scheme match is case-insensitive and surrounding space is trimmed`() {
        assertTrue(WebUrlPolicy.isSupported("HTTPS://EXAMPLE.COM/Path"))
        assertTrue(WebUrlPolicy.isSupported("  https://example.com  "))
    }

    @Test
    fun `other schemes are refused`() {
        assertFalse(WebUrlPolicy.isSupported("javascript:alert(1)"))
        assertFalse(WebUrlPolicy.isSupported("file:///etc/hosts"))
        assertFalse(WebUrlPolicy.isSupported("content://sms/42"))
        assertFalse(WebUrlPolicy.isSupported("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(WebUrlPolicy.isSupported("market://details?id=com.example"))
        assertFalse(WebUrlPolicy.isSupported("nailongclaw://settings"))
        assertFalse(WebUrlPolicy.isSupported("ftp://example.com/file"))
    }

    @Test
    fun `blank and malformed values are refused`() {
        assertFalse(WebUrlPolicy.isSupported(null))
        assertFalse(WebUrlPolicy.isSupported(""))
        assertFalse(WebUrlPolicy.isSupported("   "))
        assertFalse(WebUrlPolicy.isSupported(":https://example.com"))
        assertFalse(WebUrlPolicy.isSupported("https:example.com"))
        assertFalse(WebUrlPolicy.isSupported("http:/example.com"))
        assertFalse(WebUrlPolicy.isSupported("http://"))
        assertFalse(WebUrlPolicy.isSupported("http:///path"))
        assertFalse(WebUrlPolicy.isSupported("https://?query"))
    }

    @Test
    fun `schemeless and protocol-relative values are refused`() {
        // A schemeless string is a caller bug; letting the WebView guess turns
        // that bug into a navigation.
        assertFalse(WebUrlPolicy.isSupported("example.com"))
        assertFalse(WebUrlPolicy.isSupported("//example.com/path"))
    }
}
