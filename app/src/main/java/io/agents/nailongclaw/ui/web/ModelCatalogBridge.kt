// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import io.agents.nailongclaw.agent.CloudProvider

/**
 * The contract between the model catalog and the Claude UI page's model card,
 * reached through `PokeClawChat.readModelCatalog` / `selectModel`.
 *
 * ## Why the card needs its own projection
 *
 * The real set of selectable models is scattered across three owners:
 * [io.agents.nailongclaw.agent.llm.LocalModelManager] (on-device files, download
 * state, device RAM), [CloudProvider] (declared cloud models, per provider) and
 * the user's custom cloud endpoint. The card has to show all of them in one
 * list and switch between them, so this bridge is the one place that decides
 * what a *usable* model is — the page draws rows and reports taps, it does not
 * get to decide.
 *
 * [resolveSelection] is the guard, and it is deliberately stricter than the
 * page: a row the native 模型与任务 page would refuse (a local file that is not
 * downloaded, a device without the RAM, a cloud provider with no key saved) is
 * rejected here too. Two doors with different rules about the same switch is
 * how a user ends up on a model that cannot load.
 *
 * Same split as [WebSearchBridge] and [ClaudeUiSessionBridge]: pure mapping,
 * unit-tested, so drift fails a test rather than a card that lies on device.
 */
object ModelCatalogBridge {

    const val KIND_LOCAL = "local"
    const val KIND_CLOUD = "cloud"

    /** The provider whose endpoint the user typed in by hand. */
    const val PROVIDER_CUSTOM = "CUSTOM"

    /**
     * One selectable row. [usable] is the switch gate (file present / key
     * saved); [supported] is the device-fit gate, which only local models can
     * fail. A row that is the current selection is *not* necessarily selectable
     * — an over-RAM model that is already active stays listed, because hiding
     * it would make the card contradict the pill.
     */
    data class ModelOption(
        val kind: String,
        val id: String,
        val label: String,
        val meta: String,
        val provider: String = "",
        val providerLabel: String = "",
        val usable: Boolean = true,
        val supported: Boolean = true,
    ) {
        val key: String get() = keyOf(kind, id, provider)
    }

    /** Which row the agent is actually using right now. */
    data class ActiveSelection(
        val kind: String,
        val id: String,
        val provider: String = "",
    ) {
        val key: String get() = keyOf(kind, id, provider)
    }

    /**
     * Stable identity for a row. Local ids are globally unique; cloud ids are
     * only unique per provider, so the provider is part of the key. The page
     * carries this back on a tap, which is why it is computed here rather than
     * assembled in two places.
     */
    fun keyOf(kind: String, id: String, provider: String): String =
        if (kind == KIND_LOCAL) "$KIND_LOCAL:$id" else "$KIND_CLOUD:$provider:$id"

    /**
     * `{active, models}` — the card's whole world.
     *
     * Order is the caller's: local first, then cloud in [CloudProvider]
     * declaration order, which is the order the native page lists. The card
     * groups by `kind`, so it never has to guess where a section ends.
     */
    fun statusPayload(options: List<ModelOption>, active: ActiveSelection): String =
        ClaudeUiSessionBridge.serialize(
            mapOf(
                "active" to mapOf(
                    "key" to active.key,
                    "kind" to active.kind,
                    "id" to active.id,
                    "provider" to active.provider,
                ),
                "models" to options.map { option ->
                    mapOf(
                        "key" to option.key,
                        "kind" to option.kind,
                        "id" to option.id,
                        "label" to option.label,
                        "meta" to option.meta,
                        "provider" to option.provider,
                        "providerLabel" to option.providerLabel,
                        "usable" to option.usable,
                        "supported" to option.supported,
                    )
                },
            )
        )

    /**
     * The row a tap names, or null when the switch must not happen.
     *
     * Null covers four cases, all of them a page that would otherwise be
     * asking for a model the runtime cannot use: an unknown kind, a blank or
     * unknown id, a cloud id under the wrong provider, and a row that is not
     * usable on this device. Returning null rather than throwing keeps a stale
     * card (page cached from an older build, or a config changed on the native
     * page while the card was open) from switching anything at all.
     */
    fun resolveSelection(
        options: List<ModelOption>,
        kind: String?,
        id: String?,
        provider: String?,
    ): ActiveSelection? {
        val wantedKind = kind?.trim()?.lowercase()
        if (wantedKind != KIND_LOCAL && wantedKind != KIND_CLOUD) return null
        val wantedId = id?.trim().orEmpty()
        if (wantedId.isEmpty()) return null
        val wantedProvider = provider?.trim()?.uppercase().orEmpty()
        val match = options.firstOrNull { option ->
            option.kind == wantedKind &&
                option.id.equals(wantedId, ignoreCase = true) &&
                (wantedKind == KIND_LOCAL || option.provider.equals(wantedProvider, ignoreCase = true))
        } ?: return null
        if (!match.usable || !match.supported) return null
        return ActiveSelection(kind = match.kind, id = match.id, provider = match.provider)
    }

    /**
     * The custom-endpoint row, when the user has configured one.
     *
     * [CloudProvider.CUSTOM] declares no models — its model name and base URL
     * are whatever the user typed — so without this the card would show
     * nothing selected while the agent runs on the custom model. Returns null
     * when the stored provider is not CUSTOM or the model name is blank, which
     * is the honest "there is no custom model" answer.
     */
    fun customCloudOption(
        providerName: String,
        modelName: String,
        hasKey: Boolean,
    ): ModelOption? {
        if (providerName.trim().uppercase() != PROVIDER_CUSTOM) return null
        val name = modelName.trim()
        if (name.isEmpty()) return null
        return ModelOption(
            kind = KIND_CLOUD,
            id = name,
            label = name,
            meta = "自定义端点",
            provider = PROVIDER_CUSTOM,
            providerLabel = CloudProvider.CUSTOM.displayName,
            usable = hasKey,
        )
    }
}
