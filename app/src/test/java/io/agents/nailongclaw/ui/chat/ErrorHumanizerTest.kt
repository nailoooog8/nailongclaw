// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the failure-shape rules: each provider/system error class must land on
 * its human line, and unrecognized text must come back null (the caller keeps
 * the raw rendering) so the humanizer can only add information, never eat it.
 */
class ErrorHumanizerTest {

    // ---- Matched shapes ----

    @Test
    fun `auth failures name the key`() {
        assertTrue(
            ErrorHumanizer.humanize("OpenAI error, status code: 401. Invalid API key provided")!!
                .contains("API key"),
        )
        assertTrue(
            ErrorHumanizer.humanize("HTTP 403 Unauthorized")!!.contains("API key"),
        )
    }

    @Test
    fun `quota failures point at the account`() {
        val line = ErrorHumanizer.humanize("429 You exceeded your current quota, please check your plan and billing details")!!
        assertTrue(line.contains("quota") || line.contains("balance"))
    }

    @Test
    fun `rate limit is distinct from quota`() {
        val line = ErrorHumanizer.humanize("429 Too Many Requests")!!
        assertTrue(line.contains("rate-limiting"))
    }

    @Test
    fun `timeout mentions the network`() {
        assertTrue(
            ErrorHumanizer.humanize("SocketTimeoutException: Read timed out")!!
                .contains("timed out"),
        )
    }

    @Test
    fun `unreachable host mentions connectivity`() {
        assertTrue(
            ErrorHumanizer.humanize("UnknownHostException: Unable to resolve host api.example.com")!!
                .contains("Network"),
        )
        assertTrue(
            ErrorHumanizer.humanize("Failed to connect to api.example.com")!!
                .contains("Network"),
        )
    }

    @Test
    fun `ssl problems are their own line`() {
        assertTrue(
            ErrorHumanizer.humanize("javax.net.ssl.SSLHandshakeException: Handshake failed")!!
                .contains("Secure connection"),
        )
    }

    @Test
    fun `context overflow names the window`() {
        assertTrue(
            ErrorHumanizer.humanize("prompt is too long: 9123 tokens > 8192 maximum context length")!!
                .contains("context window"),
        )
    }

    @Test
    fun `litertlm token overflow is recognised as context overflow`() {
        val line = ErrorHumanizer.humanize(
            "AI service call failed: Failed to call nativeSendMessage: INVALID_ARGUMENT: " +
                "Input token ids are too long. Exceeding the maximum number of tokens " +
                "allowed: 8209 >= 8192, totalTokens=0",
        )!!
        assertTrue(line.contains("context window"))
    }

    @Test
    fun `service not ready`() {
        assertTrue(
            ErrorHumanizer.humanize("AI service not ready")!!.contains("starting"),
        )
    }

    // ---- Unmatched shapes stay raw ----

    @Test
    fun `unknown error returns null so the caller keeps the raw text`() {
        assertNull(ErrorHumanizer.humanize("Tool returned exit code 3"))
        assertNull(ErrorHumanizer.humanize(""))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(
            ErrorHumanizer.humanize("RATE LIMIT exceeded"),
            ErrorHumanizer.humanize("rate limit exceeded"),
        )
    }
}
