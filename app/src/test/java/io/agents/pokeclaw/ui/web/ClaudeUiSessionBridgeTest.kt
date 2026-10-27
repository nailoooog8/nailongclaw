// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import com.google.gson.JsonParser
import io.agents.pokeclaw.agent.CloudProvider
import io.agents.pokeclaw.agent.llm.ActiveModelMode
import io.agents.pokeclaw.agent.llm.CloudModelConfig
import io.agents.pokeclaw.agent.llm.LocalModelConfig
import io.agents.pokeclaw.agent.llm.ResolvedModelConfig
import io.agents.pokeclaw.ui.chat.ChatHistoryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the WebView surface's session and model-selection contract.
 *
 * The failures these guard against are silent ones, not crashes: a sidebar
 * that lists nothing because `current` was omitted, a topbar that keeps the
 * previous conversation's name because an unsaved chat had no placeholder
 * title, a model pill that says 本地 after the user switched to cloud. Each of
 * those is a page that lies, which is the thing [ClaudeUiSessionBridge]'s doc
 * says it exists to prevent.
 */
class ClaudeUiSessionBridgeTest {

    private val now = 1_700_000_000_000L

    private fun summary(id: String, title: String, created: Long) =
        ChatHistoryManager.ConversationSummary(
            id = id,
            title = title,
            created = created,
            file = File("/tmp/$id.md"),
        )

    private fun config(
        local: Boolean,
        localConfigured: Boolean = true,
        cloudConfigured: Boolean = true,
        modelName: String = "claude-sonnet-4-6",
    ) = ResolvedModelConfig(
        activeMode = if (local) ActiveModelMode.LOCAL else ActiveModelMode.CLOUD,
        local = LocalModelConfig(
            modelPath = if (localConfigured) "/data/gemma.task" else "",
            modelId = "gemma-3n-e2b",
            displayName = "Gemma 3n E2B",
            backendPreference = "GPU",
        ),
        activeCloud = CloudModelConfig(
            providerName = CloudProvider.ANTHROPIC.name,
            modelName = if (cloudConfigured) modelName else "",
            baseUrl = "https://api.anthropic.com/v1",
            apiKey = if (cloudConfigured) "sk-test" else "",
        ),
        defaultCloud = CloudModelConfig(
            providerName = CloudProvider.ANTHROPIC.name,
            modelName = if (cloudConfigured) modelName else "",
            baseUrl = "https://api.anthropic.com/v1",
            apiKey = if (cloudConfigured) "sk-test" else "",
        ),
    )

    private fun payload(json: String) = JsonParser.parseString(json).asJsonObject

    // ── conversation identity ───────────────────────────────────────────────

    @Test
    fun `stored pointer wins over everything else`() {
        assertEquals(
            "preview_stored",
            ClaudeUiSessionBridge.resolveInitialId("preview_stored", legacyExists = true, now = now),
        )
    }

    @Test
    fun `legacy id is kept when its file still exists`() {
        // Migration: the transcript written under the one fixed id must not be
        // orphaned by this change.
        assertEquals(
            ClaudeUiSessionBridge.LEGACY_CONVERSATION_ID,
            ClaudeUiSessionBridge.resolveInitialId("", legacyExists = true, now = now),
        )
    }

    @Test
    fun `fresh id when nothing is stored and no legacy file exists`() {
        assertEquals(
            "preview_$now",
            ClaudeUiSessionBridge.resolveInitialId("", legacyExists = false, now = now),
        )
    }

    @Test
    fun `new ids carry the preview prefix so the two surfaces are tellable apart`() {
        assertTrue(ClaudeUiSessionBridge.newConversationId(now).startsWith("preview_"))
        assertFalse(ClaudeUiSessionBridge.newConversationId(now).startsWith("chat_"))
    }

    // ── conversations payload ───────────────────────────────────────────────

    @Test
    fun `open conversation becomes current and appears in the list`() {
        val summaries = listOf(
            summary("preview_2", "订高铁票", 2_000),
            summary("preview_1", "清理相册", 1_000),
        )
        val json = payload(
            ClaudeUiSessionBridge.conversationsPayload("preview_2", summaries, now)
        )
        assertEquals("preview_2", json["current"].asJsonObject["id"].asString)
        assertEquals("订高铁票", json["current"].asJsonObject["title"].asString)
        assertEquals(2, json["conversations"].asJsonArray.size())
        assertEquals("preview_1", json["conversations"].asJsonArray[1].asJsonObject["id"].asString)
    }

    @Test
    fun `unsaved conversation gets a placeholder title instead of the previous name`() {
        // A brand-new chat has no file yet. Without the placeholder the topbar
        // keeps saying whatever the last conversation was called.
        val json = payload(
            ClaudeUiSessionBridge.conversationsPayload(
                "preview_new",
                listOf(summary("preview_old", "上一段对话", 1_000)),
                now,
            )
        )
        assertEquals("preview_new", json["current"].asJsonObject["id"].asString)
        assertEquals(ClaudeUiSessionBridge.UNSAVED_TITLE, json["current"].asJsonObject["title"].asString)
        assertEquals(now, json["current"].asJsonObject["createdAt"].asLong)
    }

    @Test
    fun `unsaved conversation is not injected into the list`() {
        // A row that selects itself is a lie: there is nothing on disk to open.
        val json = payload(
            ClaudeUiSessionBridge.conversationsPayload("preview_new", emptyList(), now)
        )
        assertEquals(0, json["conversations"].asJsonArray.size())
    }

    @Test
    fun `line separators in a title are escaped so the push is not a syntax error`() {
        val json = ClaudeUiSessionBridge.conversationsPayload(
            "preview_1",
            listOf(summary("preview_1", "第一行\u2028第二行", 1_000)),
            now,
        )
        assertFalse(json.contains("\u2028"))
        assertTrue(json.contains("\\u2028"))
        // Still parses as the same title on the far side.
        val title = payload(json)["conversations"].asJsonArray[0]
            .asJsonObject["title"].asString
        assertEquals("第一行\u2028第二行", title)
    }

    @Test
    fun `empty store yields an empty list and a placeholder current`() {
        val json = payload(ClaudeUiSessionBridge.conversationsPayload("preview_x", emptyList(), now))
        assertEquals(0, json["conversations"].asJsonArray.size())
        assertEquals(ClaudeUiSessionBridge.UNSAVED_TITLE, json["current"].asJsonObject["title"].asString)
    }

    // ── model status payload ────────────────────────────────────────────────

    @Test
    fun `configured local model reads as on-device`() {
        val json = payload(ClaudeUiSessionBridge.modelStatusPayload(config(local = true)))
        assertEquals("Gemma 3n E2B · 本地", json["label"].asString)
        assertEquals("Gemma 3n E2B", json["short"].asString)
        assertEquals("ready", json["kind"].asString)
    }

    @Test
    fun `configured cloud model reads as cloud with the provider name`() {
        val json = payload(ClaudeUiSessionBridge.modelStatusPayload(config(local = false)))
        assertEquals("claude-sonnet-4-6 · Anthropic", json["label"].asString)
        assertEquals("claude-sonnet-4-6", json["short"].asString)
        assertEquals("clay", json["kind"].asString)
    }

    @Test
    fun `unconfigured local selection says so instead of naming an unusable model`() {
        val json = payload(
            ClaudeUiSessionBridge.modelStatusPayload(config(local = true, localConfigured = false))
        )
        assertEquals("未选择本地模型", json["label"].asString)
        assertEquals("muted", json["kind"].asString)
    }

    @Test
    fun `unconfigured cloud selection says so too`() {
        val json = payload(
            ClaudeUiSessionBridge.modelStatusPayload(config(local = false, cloudConfigured = false))
        )
        assertEquals("未配置云端模型", json["label"].asString)
        assertEquals("muted", json["kind"].asString)
    }
}
