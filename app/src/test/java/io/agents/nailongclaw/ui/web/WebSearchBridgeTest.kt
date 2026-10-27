// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the smart-search card's contract.
 *
 * The failures these guard against are silent ones, not crashes: a card that
 * says a provider is configured when the active one has no key (the tool then
 * refuses and the user was told otherwise), a card naming a stored value the
 * tool silently resolves to DEEPSEEK, a menu that lists providers in a
 * different order than the native WebSearchActivity. Each is a page that
 * lies, which is the thing [WebSearchBridge]'s doc says it exists to prevent.
 */
class WebSearchBridgeTest {

    private fun payload(
        active: String,
        vararg keyed: String,
        promptDismissed: Boolean = false,
    ): String = WebSearchBridge.statusPayload(
        activeProvider = active,
        keys = keyed.associate { it to true },
        promptDismissed = promptDismissed,
    )

    private fun parse(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `active provider with a key is configured`() {
        val status = parse(payload("DEEPSEEK", "DEEPSEEK"))
        assertEquals("DEEPSEEK", status.get("provider").asString)
        assertEquals("DeepSeek", status.get("label").asString)
        assertTrue(status.get("configured").asBoolean)
    }

    @Test
    fun `another provider's key does not configure the active one`() {
        // Tavily key saved, DeepSeek active: the tool will report "not
        // configured", so the card must say the same.
        val status = parse(payload("DEEPSEEK", "TAVILY"))
        assertFalse(status.get("configured").asBoolean)
        val tavily = status.getAsJsonArray("providers")
            .first { it.asJsonObject.get("name").asString == "TAVILY" }
            .asJsonObject
        assertTrue(tavily.get("hasKey").asBoolean)
    }

    @Test
    fun `unknown stored provider falls back to deepseek like the tool does`() {
        val status = parse(payload("SOMETHING_ELSE", "DEEPSEEK"))
        assertEquals("DEEPSEEK", status.get("provider").asString)
        assertTrue(status.get("configured").asBoolean)
    }

    @Test
    fun `empty stored provider falls back to deepseek`() {
        val status = parse(payload("", "DEEPSEEK"))
        assertEquals("DEEPSEEK", status.get("provider").asString)
    }

    @Test
    fun `menu lists every provider in enum order with its key flag`() {
        val status = parse(payload("BOCHA", "BOCHA"))
        val providers = status.getAsJsonArray("providers").map { it.asJsonObject }
        assertEquals(
            listOf("DEEPSEEK", "BOCHA", "TAVILY"),
            providers.map { it.get("name").asString },
        )
        assertEquals(
            listOf("DeepSeek", "博查 Bocha", "Tavily"),
            providers.map { it.get("label").asString },
        )
        assertEquals(
            listOf(false, true, false),
            providers.map { it.get("hasKey").asBoolean },
        )
    }

    @Test
    fun `normalizeProvider accepts case-insensitive names and rejects the rest`() {
        assertEquals("DEEPSEEK", WebSearchBridge.normalizeProvider("deepseek"))
        assertEquals("BOCHA", WebSearchBridge.normalizeProvider(" BoCha "))
        assertNull(WebSearchBridge.normalizeProvider("google"))
        assertNull(WebSearchBridge.normalizeProvider(""))
        assertNull(WebSearchBridge.normalizeProvider(null))
    }

    @Test
    fun `prompt dismissal rides along without touching configured`() {
        // The page decides the prompt's visibility from these two fields
        // alone: unconfigured AND not dismissed. A dismissal must not be able
        // to make an unconfigured provider look configured, or the reverse.
        val open = parse(payload("DEEPSEEK", promptDismissed = false))
        assertFalse(open.get("promptDismissed").asBoolean)
        assertFalse(open.get("configured").asBoolean)

        val dismissed = parse(payload("DEEPSEEK", promptDismissed = true))
        assertTrue(dismissed.get("promptDismissed").asBoolean)
        assertFalse(dismissed.get("configured").asBoolean)

        // A saved key outranks a dismissal: the prompt stays gone either way,
        // but the pill must still read configured.
        val configured = parse(payload("DEEPSEEK", "DEEPSEEK", promptDismissed = true))
        assertTrue(configured.get("configured").asBoolean)
        assertTrue(configured.get("promptDismissed").asBoolean)
    }

    @Test
    fun `rejected provider name is not written`() {
        // The guard's other half: normalizeProvider rejecting a name is only
        // half the contract — the stored value must survive the rejection.
        val writes = mutableListOf<String>()
        assertNull(WebSearchBridge.selectProvider("google") { writes += it })
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `accepted provider name is written normalised`() {
        val writes = mutableListOf<String>()
        assertEquals("BOCHA", WebSearchBridge.selectProvider(" bocha ") { writes += it })
        assertEquals(listOf("BOCHA"), writes)
    }
}
