// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.chat

import io.agents.nailongclaw.agent.CloudProvider
import io.agents.nailongclaw.ui.web.ModelCatalogBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the Compose chat screen's model dropdown draws, against the same
 * rules the WebView main surface's model card renders (`cds.js`
 * `renderModelCatalog` / `modelRowTag`): rows group by `kind` with 本地模型
 * first, every row is listed — including ones that cannot be switched to, so
 * the user can see they exist — the row-end tag names the *reason* a row is
 * not selectable (未下载 / 内存不足 / 未配置密钥) or that it is the active one
 * (使用中), and the tap gate is exactly `usable && supported`.
 *
 * This projection is the whole data layer of the dropdown — the composable
 * reads these rows and invents nothing — so a test here is the two surfaces'
 * parity contract: any divergence between what the card says and what this
 * menu shows fails here, not on a device.
 */
class ModelMenuProjectionTest {

    private fun local(
        id: String = "gemma4-e2b",
        usable: Boolean = true,
        supported: Boolean = true,
    ) = ModelCatalogBridge.ModelOption(
        kind = ModelCatalogBridge.KIND_LOCAL,
        id = id,
        label = "Gemma 4 E2B",
        meta = "2580 MB · 需 8GB+ 内存",
        usable = usable,
        supported = supported,
    )

    private fun cloud(
        id: String = "claude-sonnet-4-6",
        provider: String = CloudProvider.ANTHROPIC.name,
        usable: Boolean = true,
    ) = ModelCatalogBridge.ModelOption(
        kind = ModelCatalogBridge.KIND_CLOUD,
        id = id,
        label = "Claude Sonnet 4.6",
        meta = "Pro · 200K",
        provider = provider,
        providerLabel = "Anthropic",
        usable = usable,
    )

    private fun active(option: ModelCatalogBridge.ModelOption) =
        ModelCatalogBridge.ActiveSelection(option.kind, option.id, option.provider)

    private fun rowsOf(
        kind: String,
        menu: List<ModelMenuProjection.Group>,
    ) = menu.first { it.kind == kind }.rows

    // ── grouping: what the two section headers contain ──────────────────────

    @Test
    fun `local rows group before cloud rows even when handed over interleaved`() {
        val menu = ModelMenuProjection.menu(
            listOf(
                cloud(id = "gpt-4o", provider = CloudProvider.OPENAI.name),
                local("gemma4-e2b"),
                cloud(id = "claude-sonnet-4-6"),
                local("gemma4-e4b"),
            ),
            active(local("gemma4-e2b")),
        )
        assertEquals(listOf(ModelCatalogBridge.KIND_LOCAL, ModelCatalogBridge.KIND_CLOUD), menu.map { it.kind })
        assertEquals(
            listOf("gemma4-e2b", "gemma4-e4b"),
            rowsOf(ModelCatalogBridge.KIND_LOCAL, menu).map { it.option.id },
        )
        assertEquals(
            listOf("gpt-4o", "claude-sonnet-4-6"),
            rowsOf(ModelCatalogBridge.KIND_CLOUD, menu).map { it.option.id },
        )
    }

    @Test
    fun `every option lands in the menu exactly once`() {
        val options = listOf(
            local("gemma4-e2b"),
            local("gemma4-e4b", usable = false),
            cloud(id = "claude-sonnet-4-6"),
            cloud(id = "gpt-4o-mini", provider = CloudProvider.OPENAI.name, usable = false),
        )
        val menu = ModelMenuProjection.menu(options, active(options.first()))
        val menuKeys = menu.flatMap { it.rows }.map { it.option.key }
        assertEquals(options.map { it.key }, menuKeys)
        assertEquals(options.map { it.key }.toSet().size, menuKeys.size)
    }

    @Test
    fun `a menu over an empty catalog has no groups rather than an empty group`() {
        // The card's empty state ("没有可选模型…") is the footer row alone;
        // the projection answers with no groups, not a group with no rows.
        assertTrue(ModelMenuProjection.menu(emptyList(), active(local())).isEmpty())
    }

    @Test
    fun `a group is dropped only when nothing of that kind exists`() {
        // A device with nothing downloaded: the dropdown still lists the
        // cloud side in full — hiding the whole menu because one side is
        // empty is how "切不到别的服务商" happened in the first place.
        val menu = ModelMenuProjection.menu(
            listOf(cloud(id = "claude-sonnet-4-6")),
            active(cloud()),
        )
        assertEquals(listOf(ModelCatalogBridge.KIND_CLOUD), menu.map { it.kind })
    }

    // ── rows: identity, activity, tap gate ──────────────────────────────────

    @Test
    fun `the active row is matched by full key, not by model id alone`() {
        // Cloud ids are only unique per provider: gpt-4o under OPENAI and the
        // same id under another provider must not both light up. The custom
        // endpoint's row is exactly this case — a typed model name that may
        // collide with a declared one.
        val shared = "shared-model"
        val a = cloud(id = shared, provider = CloudProvider.ANTHROPIC.name)
        val b = cloud(id = shared, provider = CloudProvider.OPENAI.name)
        val menu = ModelMenuProjection.menu(listOf(a, b), active(b))
        val rows = rowsOf(ModelCatalogBridge.KIND_CLOUD, menu)
        assertFalse(rows.first { it.option.key == a.key }.isActive)
        assertTrue(rows.first { it.option.key == b.key }.isActive)
    }

    @Test
    fun `selectable is exactly usable and supported, per combination`() {
        val options = listOf(
            local(usable = true, supported = true),
            local(usable = true, supported = false),
            local(usable = false, supported = true),
            local(usable = false, supported = false),
            cloud(usable = true),
            cloud(usable = false),
        )
        val menu = ModelMenuProjection.menu(options, active(options[4]))
        val expected = listOf(true, false, false, false, true, false)
        assertEquals(
            expected,
            menu.flatMap { it.rows }.map { it.selectable },
        )
    }

    // ── tags: the row-end badges, same words the card shows ─────────────────

    @Test
    fun `the active row is tagged in use even when it cannot run`() {
        // The card checks 使用中 first: an over-RAM model that is somehow
        // already active is still tagged 使用中 — hiding that would make the
        // dropdown contradict the status line above it.
        val menu = ModelMenuProjection.menu(
            listOf(local(supported = false)),
            active(local(supported = false)),
        )
        assertEquals(
            ModelMenuProjection.RowTag.ACTIVE,
            rowsOf(ModelCatalogBridge.KIND_LOCAL, menu).single().tag,
        )
    }

    @Test
    fun `a local row without its file on disk is tagged not downloaded`() {
        val menu = ModelMenuProjection.menu(
            listOf(local(usable = false)),
            active(cloud()),
        )
        assertEquals(
            ModelMenuProjection.RowTag.NOT_DOWNLOADED,
            rowsOf(ModelCatalogBridge.KIND_LOCAL, menu).single().tag,
        )
    }

    @Test
    fun `not downloaded wins over over-ram when both apply`() {
        // The card checks usable before supported; the menu must name the
        // same reason or the two doors disagree about what is wrong.
        val menu = ModelMenuProjection.menu(
            listOf(local(usable = false, supported = false)),
            active(cloud()),
        )
        assertEquals(
            ModelMenuProjection.RowTag.NOT_DOWNLOADED,
            rowsOf(ModelCatalogBridge.KIND_LOCAL, menu).single().tag,
        )
    }

    @Test
    fun `a downloaded local row the device cannot hold is tagged over ram`() {
        val menu = ModelMenuProjection.menu(
            listOf(local(usable = true, supported = false)),
            active(cloud()),
        )
        assertEquals(
            ModelMenuProjection.RowTag.OVER_RAM,
            rowsOf(ModelCatalogBridge.KIND_LOCAL, menu).single().tag,
        )
    }

    @Test
    fun `a cloud row without a saved key is tagged no key`() {
        val menu = ModelMenuProjection.menu(
            listOf(cloud(usable = false)),
            active(local()),
        )
        assertEquals(
            ModelMenuProjection.RowTag.NO_KEY,
            rowsOf(ModelCatalogBridge.KIND_CLOUD, menu).single().tag,
        )
    }

    @Test
    fun `a fully healthy non-active row carries no tag`() {
        // The active selection is the e4b row, so the healthy e2b row next to
        // it must come out untagged — a tag on a working row invents a
        // problem the projection does not report.
        val menu = ModelMenuProjection.menu(
            listOf(local("gemma4-e2b"), local("gemma4-e4b"), cloud()),
            active(local("gemma4-e4b")),
        )
        assertNull(rowsOf(ModelCatalogBridge.KIND_LOCAL, menu).first { it.option.id == "gemma4-e2b" }.tag)
        assertNull(rowsOf(ModelCatalogBridge.KIND_CLOUD, menu).single().tag)
    }

    @Test
    fun `an unusable cloud row is still listed — the user must see it exists`() {
        // The card lists every row and disables the tap; a menu that dropped
        // the no-key providers would hide exactly the models the user is
        // meant to go configure a key for.
        val menu = ModelMenuProjection.menu(
            listOf(
                cloud(id = "claude-sonnet-4-6", usable = true),
                cloud(id = "gpt-4o-mini", provider = CloudProvider.OPENAI.name, usable = false),
            ),
            active(cloud()),
        )
        assertEquals(2, rowsOf(ModelCatalogBridge.KIND_CLOUD, menu).size)
        val noKey = rowsOf(ModelCatalogBridge.KIND_CLOUD, menu).first { it.option.id == "gpt-4o-mini" }
        assertFalse(noKey.selectable)
        assertEquals(ModelMenuProjection.RowTag.NO_KEY, noKey.tag)
    }

    // ── empty state ─────────────────────────────────────────────────────────

    @Test
    fun `a menu with no rows at all is the dropdown's empty state`() {
        // Same trigger as the web card's 没有可选模型 footer (cds.js:
        // `if (!models.length)`) — an empty catalog. The Compose dropdown
        // draws the empty-state line for exactly this case, so both doors
        // answer "there is nothing to switch to" the same way.
        assertTrue(
            ModelMenuProjection.isEmptyMenu(
                ModelMenuProjection.menu(emptyList(), active(local()))
            )
        )
    }

    @Test
    fun `rows that exist but are not selectable are not the empty state`() {
        // Not-downloaded local model only: the row is listed disabled with its
        // tag, like the card — the user can see the model exists and what it
        // lacks, which says more than a "no models" line would.
        val menu = ModelMenuProjection.menu(
            listOf(local("gemma4-e2b", usable = false)),
            active(local()),
        )
        assertFalse(ModelMenuProjection.isEmptyMenu(menu))
    }
}
