// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.chat

/**
 * Turns raw failure text into one short line a person can act on.
 *
 * The transcript used to print `Error: ${event.error}` verbatim — an HTTP
 * status, a stack-frame fragment, an MCP denial id — which reads as noise and
 * gives the user nothing to do. The pattern is borrowed from how dsh-Go
 * maps failure *codes* to copy (research note 2026-11-15): the friendly line
 * becomes the answer, the raw string moves into the system line right below
 * it, so nothing is hidden that used to be visible.
 *
 * Pure logic: no Android types, matched by lowercase substring on purpose —
 * provider error text is not a stable API, and a strict parser would go blind
 * the day a provider rewords a message. Unmatched input returns null and the
 * caller keeps the old `Error: …` rendering, so the worst case is today's
 * behaviour, never less information.
 */
object ErrorHumanizer {

    /**
     * One human line for a known failure shape, or null when this input has
     * no rule and should be shown as-is.
     */
    @JvmStatic
    fun humanize(raw: String): String? {
        val text = raw.lowercase()
        if (text.isBlank()) return null
        return when {
            matchesAuth(text) ->
                "The model provider rejected the API key. Check the key in Settings → Model."
            matchesQuota(text) ->
                "The model account is out of quota or balance. Top it up or switch models in Settings."
            matchesRateLimit(text) ->
                "The model is rate-limiting requests. Wait a moment and try again."
            matchesModelNotFound(text) ->
                "The configured model name was not found. Check it in Settings → Model."
            matchesContextOverflow(text) ->
                "The conversation ran past the model's context window. Start a new chat or shorten the task."
            matchesTimeout(text) ->
                "The request timed out. Check the network and try again."
            matchesNetwork(text) ->
                "Network problem — the model service is unreachable. Check the connection."
            matchesTls(text) ->
                "Secure connection to the model service failed. Check the network or proxy settings."
            matchesNotReady(text) ->
                "The AI service has not finished starting. Try again in a moment."
            else -> null
        }
    }

    private fun matchesAuth(t: String): Boolean =
        t.contains("401") || t.contains("403") ||
            t.contains("unauthorized") || t.contains("authentication") ||
            t.contains("invalid api key") || t.contains("api key") && t.contains("invalid") ||
            t.contains("incorrect api key")

    private fun matchesQuota(t: String): Boolean =
        t.contains("402") || t.contains("quota") || t.contains("insufficient") ||
            t.contains("balance") || t.contains("exceeded your current payment")

    private fun matchesRateLimit(t: String): Boolean =
        t.contains("429") || t.contains("too many requests") || t.contains("rate limit")

    private fun matchesModelNotFound(t: String): Boolean =
        t.contains("404") && (t.contains("model") || t.contains("not found"))

    private fun matchesContextOverflow(t: String): Boolean =
        t.contains("context length") || t.contains("context window") ||
            t.contains("maximum context") || t.contains("token limit")

    private fun matchesTimeout(t: String): Boolean =
        t.contains("timed out") || t.contains("timeout") ||
            t.contains("sockettimeout") || t.contains("etimedout")

    private fun matchesNetwork(t: String): Boolean =
        t.contains("unknownhost") || t.contains("unable to resolve") ||
            t.contains("connection refused") || t.contains("econnrefused") ||
            t.contains("failed to connect") || t.contains("network is unreachable") ||
            t.contains("econnreset") || t.contains("connection reset")

    private fun matchesTls(t: String): Boolean =
        t.contains("ssl") || t.contains("sslhandshake") || t.contains("certificate")

    private fun matchesNotReady(t: String): Boolean =
        t.contains("ai service not ready") || t.contains("service not ready")
}
