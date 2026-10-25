// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the F8 parameter redaction in `ToolRegistry`'s failure log.
 *
 * A failed tool call used to log its full parameter map. That log is written in
 * release builds too (`XLog.e` ignores the DEBUG flag) and
 * `DebugReportManager` ships it to whoever the user asks for help, so a failed
 * `input_text` used to hand over whatever the agent was typing — a password, a
 * verification code, a full message body.
 */
class ToolRegistryRedactionTest {

    @Test
    fun `user content parameters are redacted`() {
        val out = ToolRegistry.redactParams(
            mapOf(
                "text" to "my bank password is hunter2",
                "message" to "send this to everyone",
                "content" to "a very long note",
                "phone" to "13800138000",
                "query" to "secret search",
            )
        )
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("send this to everyone"))
        assertFalse(out.contains("very long note"))
        assertFalse(out.contains("13800138000"))
        assertFalse(out.contains("secret search"))
        // The parameter *names* survive, so a log is still diagnosable.
        assertTrue(out.contains("text="))
        assertTrue(out.contains("phone="))
    }

    @Test
    fun `non sensitive parameters keep their value`() {
        val out = ToolRegistry.redactParams(
            mapOf("package" to "com.android.settings", "x" to 120, "enabled" to true)
        )
        assertTrue(out.contains("package=com.android.settings"))
        assertTrue(out.contains("x=120"))
        assertTrue(out.contains("enabled=true"))
    }

    @Test
    fun `redaction is not fooled by casing`() {
        val out = ToolRegistry.redactParams(mapOf("Message" to "confidential", "PHONE_NUMBER" to "12345"))
        assertFalse(out.contains("confidential"))
        assertFalse(out.contains("12345"))
    }

    @Test
    fun `an empty map renders as an empty object`() {
        assertTrue(ToolRegistry.redactParams(emptyMap()).contains("{}"))
    }
}
