// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import io.agents.nailongclaw.agent.CloudProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the default-row decisions behind the Compose chat screen's 本地/云端
 * tabs and its switch guard.
 *
 * The tabs used to send the pseudo-model ids `"LOCAL"` (activate whatever
 * local default was stored) and `"NONE"` (this side has nothing configured —
 * which the controller answered by killing the running model's ready state).
 * Both cases name a real catalog row now, and these tests pin the remaining
 * paths: a configured default, a runnable fallback, the custom endpoint, and
 * the honest null when a side has no row at all.
 */
class ModelCatalogBridgeRowsTest {

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

    // ── localDefaultRow: the 本地 tab ───────────────────────────────────────

    @Test
    fun `the configured local default resolves to its row`() {
        val options = listOf(local("gemma4-e2b"), local("gemma4-e4b", usable = false))
        assertEquals(
            "gemma4-e2b",
            ModelCatalogBridge.localDefaultRow(options, "gemma4-e2b")?.id,
        )
    }

    @Test
    fun `a configured id wins even when another local row would also run`() {
        // The stored default is the user's choice; the fallback only exists
        // for the nothing-configured case.
        val options = listOf(local("gemma4-e2b"), local("gemma4-e4b"))
        assertEquals(
            "gemma4-e2b",
            ModelCatalogBridge.localDefaultRow(options, "gemma4-e2b")?.id,
        )
    }

    @Test
    fun `without a configured default the first runnable local row is the default`() {
        // The old `"LOCAL"` magic id only worked when a default was stored;
        // a device that downloaded a model without saving a default now still
        // has a row to point at.
        val options = listOf(local("gemma4-e2b", usable = false), local("gemma4-e4b"))
        assertEquals(
            "gemma4-e4b",
            ModelCatalogBridge.localDefaultRow(options, null)?.id,
        )
    }

    @Test
    fun `a configured id that matches nothing falls through to the runnable row`() {
        val options = listOf(local("gemma4-e2b"))
        assertEquals(
            "gemma4-e2b",
            ModelCatalogBridge.localDefaultRow(options, "gemma-9b")?.id,
        )
    }

    @Test
    fun `a device with nothing downloaded has no local row to switch to`() {
        val options = listOf(local("gemma4-e2b", usable = false), local("gemma4-e4b", usable = false))
        assertNull(ModelCatalogBridge.localDefaultRow(options, null))
        assertNull(ModelCatalogBridge.localDefaultRow(emptyList(), null))
    }

    // ── cloudDefaultRow: the 云端 tab ───────────────────────────────────────

    @Test
    fun `the stored cloud default resolves to its row by provider and id`() {
        val options = listOf(
            cloud(id = "gpt-4o", provider = CloudProvider.OPENAI.name),
            cloud(id = "claude-sonnet-4-6", provider = CloudProvider.ANTHROPIC.name),
        )
        assertEquals(
            "gpt-4o",
            ModelCatalogBridge.cloudDefaultRow(
                options,
                CloudProvider.OPENAI.name,
                "gpt-4o",
            )?.id,
        )
    }

    @Test
    fun `a default under a provider the catalog does not list is not a row`() {
        // Same two-part identity a card tap carries: the provider must match,
        // not just the model id.
        val options = listOf(cloud(provider = CloudProvider.OPENAI.name))
        assertNull(
            ModelCatalogBridge.cloudDefaultRow(
                options,
                CloudProvider.ANTHROPIC.name,
                "gpt-4o",
            ),
        )
    }

    @Test
    fun `no default model configured means nothing to switch to`() {
        // The old `"NONE"` path: the 云端 tab with nothing configured. The
        // honest answer now is null — no row, no switch, no killed model.
        assertNull(
            ModelCatalogBridge.cloudDefaultRow(
                listOf(cloud()),
                CloudProvider.ANTHROPIC.name,
                "   ",
            ),
        )
        assertNull(ModelCatalogBridge.cloudDefaultRow(listOf(cloud()), "", ""))
    }

    @Test
    fun `a custom endpoint default resolves to its synthesized row`() {
        val custom = ModelCatalogBridge.customCloudOption(
            providerName = CloudProvider.CUSTOM.name,
            modelName = "my-model",
            hasKey = true,
        )!!
        assertEquals(
            custom.key,
            ModelCatalogBridge.cloudDefaultRow(
                listOf(cloud(), custom),
                CloudProvider.CUSTOM.name,
                "my-model",
            )?.key,
        )
    }

    @Test
    fun `a default whose key was cleared is still returned — the switch guard refuses, not the projection`() {
        // The card lists the row and disables the tap; the tab hands over the
        // row and the controller's usable-flag guard refuses it. A projection
        // that hid the row would leave the tab pointing at nothing with no
        // way to see why.
        val row = ModelCatalogBridge.cloudDefaultRow(
            listOf(cloud(usable = false)),
            CloudProvider.ANTHROPIC.name,
            "claude-sonnet-4-6",
        )
        assertEquals("claude-sonnet-4-6", row?.id)
        assertFalse(row?.usable ?: true)
    }

    // ── isDeclaredCloudModel: the switch guard for row-shaped calls ────────

    @Test
    fun `a declared model passes its provider's declaration check`() {
        assertTrue(
            ModelCatalogBridge.isDeclaredCloudModel(
                CloudProvider.ANTHROPIC.name,
                "claude-sonnet-4-6",
            ),
        )
    }

    @Test
    fun `an id the provider does not declare is refused`() {
        // A stale row (catalog assembled before the provider's list changed)
        // must not write a config the runtime cannot build.
        assertFalse(
            ModelCatalogBridge.isDeclaredCloudModel(
                CloudProvider.ANTHROPIC.name,
                "gpt-4o",
            ),
        )
    }

    @Test
    fun `an unknown provider is refused rather than defaulted`() {
        assertFalse(ModelCatalogBridge.isDeclaredCloudModel("SIDEWAYS", "gpt-4o"))
        assertFalse(ModelCatalogBridge.isDeclaredCloudModel("", "gpt-4o"))
    }

    @Test
    fun `the declaration check ignores case like the rest of the projection`() {
        assertTrue(
            ModelCatalogBridge.isDeclaredCloudModel(
                "anthropic",
                "Claude-Sonnet-4-6",
            ),
        )
    }

    @Test
    fun `custom declares nothing but passes any typed model name`() {
        // The custom endpoint's model name is whatever the user typed — it can
        // never be checked against a list, only against blankness.
        assertTrue(ModelCatalogBridge.isDeclaredCloudModel("CUSTOM", "my-model"))
        assertFalse(ModelCatalogBridge.isDeclaredCloudModel("CUSTOM", "  "))
    }
}
