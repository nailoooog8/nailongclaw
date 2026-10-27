// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import com.google.gson.Gson
import io.agents.nailongclaw.agent.llm.ResolvedModelConfig
import io.agents.nailongclaw.ui.chat.ChatHistoryManager

/**
 * The WebView surface's session and model-selection contract with the Claude UI
 * page (`mockup/claude-ui`), reached through `PokeClawChat.newChat` /
 * `listConversations` / `openConversation` / `renameConversation` /
 * `deleteConversation` / `readModelStatus`.
 *
 * ## Why a mapping layer exists at all
 *
 * The store's model is the record of truth: [ChatHistoryManager.ConversationSummary]
 * with its `file` and `created`, and [ResolvedModelConfig] with its
 * `activeMode` / `local` / `activeCloud`. The page's contract is what a sidebar
 * and two pills need to draw: ids, titles, epoch millis, one label and one dot
 * colour. Keeping the translation here — pure and unit-tested — means a silent
 * drift shows up as a failing test rather than as a sidebar that quietly lists
 * nothing on a device. Same split as [TaskLedgerBridge].
 *
 * ## The two things this used to get wrong
 *
 * 1. **One fixed conversation id for the life of the install.** Every visit
 *    appended to the same file, so the surface could neither start a second
 *    chat nor go back to an older one. [newConversationId] /
 *    [resolveInitialId] own that policy now, and the legacy id is still opened
 *    when it exists so the user's last transcript is not orphaned.
 * 2. **The model pill was static markup.** It said 「本地模型」 after every
 *    switch. [modelStatusPayload] reads the selection from
 *    [ModelConfigRepository] — deliberately *not* from the controller's status
 *    string, which is about readiness ("Loading…", "⚠ Model busy") and would
 *    park a transient state in a control whose job is to say which model is
 *    selected.
 */
object ClaudeUiSessionBridge {

    private val gson = Gson()

    /** Legacy id every transcript written before 2026-10-27 lives under. */
    const val LEGACY_CONVERSATION_ID = "preview_claude_ui"

    /** Title shown for a conversation that has not been saved yet. */
    const val UNSAVED_TITLE = "新对话"

    /**
     * A fresh id. `preview_` rather than the chat screen's `chat_` so the two
     * surfaces' conversations are tellable apart in the markdown store.
     */
    fun newConversationId(now: Long): String = "preview_$now"

    /**
     * Which conversation to open on entry.
     *
     * A stored pointer wins; failing that, the legacy id is kept when its file
     * still exists (migration — see the class doc); failing that, start fresh.
     */
    fun resolveInitialId(stored: String, legacyExists: Boolean, now: Long): String = when {
        stored.isNotEmpty() -> stored
        legacyExists -> LEGACY_CONVERSATION_ID
        else -> newConversationId(now)
    }

    /**
     * `{current, conversations}` — the sidebar's whole world.
     *
     * `current` carries a placeholder title when the open conversation has no
     * file yet (a brand-new chat with no first message): without it the topbar
     * keeps the previous conversation's name until the user types something.
     * The open conversation is *not* injected into `conversations` — an unsaved
     * chat has nothing to open, and a row that selects itself is a lie.
     */
    fun conversationsPayload(
        currentId: String,
        summaries: List<ChatHistoryManager.ConversationSummary>,
        now: Long,
    ): String {
        val current = summaries.firstOrNull { it.id == currentId }
        val payload = mapOf(
            "current" to (current?.let { conversationOf(it) }
                ?: mapOf(
                    "id" to currentId,
                    "title" to UNSAVED_TITLE,
                    "createdAt" to now,
                )),
            "conversations" to summaries.map { conversationOf(it) },
        )
        return serialize(payload)
    }

    /**
     * `{label, short, kind}` for the topbar pill and the composer pill.
     *
     * `kind` is one of the page's status-dot kinds: `ready` on-device, `clay`
     * cloud, `muted` when nothing usable is selected. An unconfigured selection
     * says so rather than falling back to a model name the agent cannot use.
     */
    fun modelStatusPayload(config: ResolvedModelConfig): String {
        val (label, short, kind) = when {
            config.isLocalActive() && config.local.isConfigured -> Triple(
                "${config.local.displayName} · 本地",
                config.local.displayName,
                "ready",
            )
            config.isLocalActive() -> Triple("未选择本地模型", "未选择模型", "muted")
            config.activeCloud.isConfigured -> Triple(
                "${config.activeCloud.modelName} · ${config.activeCloud.provider.displayName}",
                config.activeCloud.modelName,
                "clay",
            )
            else -> Triple("未配置云端模型", "未选择模型", "muted")
        }
        return serialize(mapOf("label" to label, "short" to short, "kind" to kind))
    }

    private fun conversationOf(summary: ChatHistoryManager.ConversationSummary) = mapOf(
        "id" to summary.id,
        "title" to summary.title,
        "createdAt" to summary.created,
    )

    /**
     * Gson leaves U+2028/U+2029 raw, and they are line terminators in JS source
     * before ES2019 — one in a conversation title turns the whole
     * `evaluateJavascript` into a syntax error and the push is silently lost.
     * The payload is spliced in as source text, not handed over as a string, so
     * they have to be escaped here. Same two lines as
     * [TaskLedgerBridge]'s callers.
     *
     * Internal rather than private because [ModelCatalogBridge] builds a
     * payload from the same user-reachable strings (a custom cloud model name
     * is typed by the user) — one escaping implementation, not two.
     */
    internal fun serialize(payload: Map<String, Any?>): String = gson.toJson(payload)
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
}
