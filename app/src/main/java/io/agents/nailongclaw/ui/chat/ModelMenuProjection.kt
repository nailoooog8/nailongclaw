// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.chat

import io.agents.nailongclaw.ui.web.ModelCatalogBridge

/**
 * Turns the shared model catalog into what the Compose chat screen's model
 * dropdown draws — the same projection the WebView main surface's model card
 * renders from ([ModelCatalogBridge.statusPayload]), shaped for Compose.
 *
 * The web card's rules, ported verbatim so the two doors stay one product
 * (cds.js `renderModelCatalog` / `modelRowTag`): rows group by `kind` with
 * 本地模型 first and 云端模型 after, order inside a group is the projection's
 * order, every row is listed — including ones that cannot be switched to, so
 * the user can see they exist — and the tap gate is exactly
 * `usable && supported`. The row tag names the *reason* a row is not
 * selectable (未下载 / 内存不足 / 未配置密钥) or that it is the active one
 * (使用中), and nothing else: a tag this code invents beyond the projection's
 * flags would be a card that lies about the device.
 *
 * Pure mapping — no Android, no state — so drift between what the card says
 * and what the dropdown says fails a test rather than a user.
 */
object ModelMenuProjection {

    /** Why a row carries a tag, mirroring the web card's row-end badges. */
    enum class RowTag {
        /** The row the agent is running on right now. */
        ACTIVE,

        /** Local file not on disk yet. */
        NOT_DOWNLOADED,

        /** Local file the device does not have the RAM to hold. */
        OVER_RAM,

        /** Cloud provider without a saved key. */
        NO_KEY,
    }

    /** One dropdown row: the catalog row plus how to present it. */
    data class Row(
        val option: ModelCatalogBridge.ModelOption,
        val isActive: Boolean,
        /** Same gate the web card enforces: `usable && supported`. */
        val selectable: Boolean,
        val tag: RowTag?,
    )

    /** One kind-group of rows. [kind] is [ModelCatalogBridge.KIND_LOCAL] / `KIND_CLOUD`. */
    data class Group(
        val kind: String,
        val rows: List<Row>,
    )

    /**
     * The dropdown's whole world: groups in display order, every option in
     * [options] present exactly once.
     */
    fun menu(
        options: List<ModelCatalogBridge.ModelOption>,
        active: ModelCatalogBridge.ActiveSelection,
    ): List<Group> {
        val byKind = linkedMapOf<String, MutableList<ModelCatalogBridge.ModelOption>>()
        options.forEach { option ->
            byKind.getOrPut(option.kind) { mutableListOf() }.add(option)
        }
        // Local first, then cloud — the projection's own order discipline; the
        // linked map only makes it robust to a caller that hands rows over
        // interleaved.
        val ordered = byKind.entries.sortedBy { entry ->
            when (entry.key) {
                ModelCatalogBridge.KIND_LOCAL -> 0
                ModelCatalogBridge.KIND_CLOUD -> 1
                else -> 2
            }
        }
        return ordered.map { (kind, rows) ->
            Group(
                kind = kind,
                rows = rows.map { it.toRow(active) },
            )
        }
    }

    private fun ModelCatalogBridge.ModelOption.toRow(
        active: ModelCatalogBridge.ActiveSelection,
    ): Row {
        val isActive = key == active.key
        val tag = when {
            isActive -> RowTag.ACTIVE
            kind == ModelCatalogBridge.KIND_LOCAL && !usable -> RowTag.NOT_DOWNLOADED
            kind == ModelCatalogBridge.KIND_LOCAL && !supported -> RowTag.OVER_RAM
            kind == ModelCatalogBridge.KIND_CLOUD && !usable -> RowTag.NO_KEY
            else -> null
        }
        return Row(
            option = this,
            isActive = isActive,
            selectable = usable && supported,
            tag = tag,
        )
    }
}
