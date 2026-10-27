// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import com.google.gson.Gson
import io.agents.nailongclaw.agent.SearchProvider

/**
 * The contract between the built-in web_search tool's configuration and the
 * Claude UI page's smart-search controls, reached through
 * `PokeClawChat.readWebSearchStatus` / `selectSearchProvider` /
 * `dismissSearchPrompt`.
 *
 * ## Why the card needs its own projection
 *
 * [io.agents.nailongclaw.tool.impl.WebSearchTool] reads its provider and key from
 * KVUtils at execution time. The card says which provider is active and
 * whether it has a key, so the two must agree on one subtle point: an
 * unrecognised stored provider name falls back to
 * [SearchProvider.DEEPSEEK] — that is what [SearchProvider.fromName] does, and
 * therefore what the tool will actually call. Projecting the fallback here
 * instead of passing the raw string through is what keeps the card from
 * naming a provider the agent is not using.
 *
 * `configured` is the active provider's key and nothing else: a Tavily key
 * with DeepSeek selected is an unconfigured card, because that is what the
 * tool will report. Same split as [ClaudeUiSessionBridge] — pure mapping,
 * unit-tested, so drift fails a test rather than a card that lies on device.
 *
 * The page draws two things from one payload: a pill in the composer bar
 * (always there — it is the provider switcher) and a prompt card that shows
 * only while the active provider has no key and the user has not closed it.
 * `promptDismissed` is what makes that dismissal stick across launches.
 */
object WebSearchBridge {

    private val gson = Gson()

    /**
     * A stored provider name resolved to an enum name, or null when nothing
     * matches. Case-insensitive, because the name round-trips through
     * KVUtils and a hand-edited value should not silently become DEEPSEEK on
     * a write path.
     */
    fun normalizeProvider(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return SearchProvider.entries
            .firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?.name
    }

    /**
     * Normalise [name] and, when it names a provider, write it through
     * [write]. Returns the stored name, or null when the name was rejected —
     * in which case nothing is written. The write goes through a seam so the
     * guard is testable without MMKV, the same discipline as
     * [io.agents.nailongclaw.automation.ExternalAutomationGuard].
     */
    fun selectProvider(name: String?, write: (String) -> Unit): String? {
        val normalized = normalizeProvider(name) ?: return null
        write(normalized)
        return normalized
    }

    /**
     * `{provider, label, configured, promptDismissed, providers}` for the
     * composer-bar pill and the unconfigured prompt card.
     *
     * [activeProvider] is the raw KVUtils value; [keys] maps provider name to
     * whether a key is saved (an absent entry means no key); [promptDismissed]
     * is whether the user closed the prompt card. `providers` follows the
     * enum's declaration order — the same order the native WebSearchActivity
     * lists, so the two doors cannot disagree about which provider is which.
     *
     * The prompt card's rule lives here rather than in the page: it shows when
     * the active provider has no key AND the user has not closed it. Once a
     * key exists the prompt is gone for good — the pill carries the state from
     * then on.
     */
    fun statusPayload(
        activeProvider: String,
        keys: Map<String, Boolean>,
        promptDismissed: Boolean = false,
    ): String {
        val active = SearchProvider.fromName(activeProvider)
        val payload = mapOf(
            "provider" to active.name,
            "label" to active.displayName,
            "configured" to (keys[active.name] == true),
            "promptDismissed" to promptDismissed,
            "providers" to SearchProvider.entries.map { provider ->
                mapOf(
                    "name" to provider.name,
                    "label" to provider.displayName,
                    "hasKey" to (keys[provider.name] == true),
                )
            },
        )
        return gson.toJson(payload)
    }
}
