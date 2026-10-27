// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.web

import com.google.gson.JsonParser
import io.agents.nailongclaw.agent.CloudProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the model card's contract with the page.
 *
 * The failures these guard against are the silent ones: a card that lists a
 * model the runtime cannot use (a local file that was never downloaded, a
 * provider whose key was never saved), a cloud id resolving under the wrong
 * provider because the provider was dropped from the round-trip, and a custom
 * model name breaking the `evaluateJavascript` splice because a line separator
 * was left raw. Each is a page that lies, which is what
 * [ModelCatalogBridge]'s doc says it exists to prevent.
 */
class ModelCatalogBridgeTest {

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

    private fun payload(json: String) = JsonParser.parseString(json).asJsonObject

    // ── payload shape ───────────────────────────────────────────────────────

    @Test
    fun `payload carries the active row and every option in order`() {
        val options = listOf(local(), cloud())
        val json = payload(
            ModelCatalogBridge.statusPayload(
                options,
                ModelCatalogBridge.ActiveSelection(
                    kind = ModelCatalogBridge.KIND_CLOUD,
                    id = "claude-sonnet-4-6",
                    provider = CloudProvider.ANTHROPIC.name,
                ),
            )
        )

        val active = json["active"].asJsonObject
        assertEquals(CloudProvider.ANTHROPIC.name, active["provider"].asString)
        assertEquals("claude-sonnet-4-6", active["id"].asString)
        assertEquals(
            "cloud:${CloudProvider.ANTHROPIC.name}:claude-sonnet-4-6",
            active["key"].asString,
        )

        val models = json["models"].asJsonArray
        assertEquals(2, models.size())
        assertEquals("local:gemma4-e2b", models[0].asJsonObject["key"].asString)
        assertEquals("gemma4-e2b", models[0].asJsonObject["id"].asString)
        assertTrue(models[0].asJsonObject["usable"].asBoolean)
        assertEquals("2580 MB · 需 8GB+ 内存", models[0].asJsonObject["meta"].asString)
        assertEquals("", models[0].asJsonObject["provider"].asString)
        assertEquals(
            "cloud:${CloudProvider.ANTHROPIC.name}:claude-sonnet-4-6",
            models[1].asJsonObject["key"].asString,
        )
        assertEquals("Anthropic", models[1].asJsonObject["providerLabel"].asString)
    }

    @Test
    fun `a local key carries no provider so two providers cannot collide on one id`() {
        assertEquals("local:gemma4-e2b", ModelCatalogBridge.keyOf("local", "gemma4-e2b", ""))
        assertEquals(
            "cloud:OPENAI:gpt-4o",
            ModelCatalogBridge.keyOf("cloud", "gpt-4o", "OPENAI"),
        )
        assertEquals(
            "cloud:ANTHROPIC:gpt-4o",
            ModelCatalogBridge.keyOf("cloud", "gpt-4o", "ANTHROPIC"),
        )
    }

    @Test
    fun `an unusable row is still listed with the flag that says so`() {
        val json = payload(
            ModelCatalogBridge.statusPayload(
                listOf(local(usable = false, supported = false)),
                ModelCatalogBridge.ActiveSelection(kind = "local", id = ""),
            )
        )
        val row = json["models"].asJsonArray[0].asJsonObject
        assertFalse(row["usable"].asBoolean)
        assertFalse(row["supported"].asBoolean)
    }

    // ── the selection guard ─────────────────────────────────────────────────

    @Test
    fun `a usable local row resolves to a local selection`() {
        val selection = ModelCatalogBridge.resolveSelection(
            listOf(local()), ModelCatalogBridge.KIND_LOCAL, "gemma4-e2b", ""
        )
        assertEquals(
            ModelCatalogBridge.ActiveSelection(kind = "local", id = "gemma4-e2b", provider = ""),
            selection,
        )
    }

    @Test
    fun `a usable cloud row resolves with its provider`() {
        val selection = ModelCatalogBridge.resolveSelection(
            listOf(cloud()), ModelCatalogBridge.KIND_CLOUD, "claude-sonnet-4-6", "ANTHROPIC"
        )
        assertEquals(
            ModelCatalogBridge.ActiveSelection(
                kind = "cloud",
                id = "claude-sonnet-4-6",
                provider = "ANTHROPIC",
            ),
            selection,
        )
    }

    @Test
    fun `a cloud id under the wrong provider is refused`() {
        // The same model id can be offered by two providers. Accepting the
        // first match would switch the provider along with the model — the
        // user picked a model, not an endpoint.
        val options = listOf(cloud(id = "gpt-4o", provider = "OPENAI"), cloud(provider = "GOOGLE"))
        assertNull(
            ModelCatalogBridge.resolveSelection(options, "cloud", "gpt-4o", "GOOGLE")
        )
        assertNull(
            ModelCatalogBridge.resolveSelection(options, "cloud", "claude-sonnet-4-6", "OPENAI")
        )
    }

    @Test
    fun `the provider match ignores case but not identity`() {
        assertEquals(
            "ANTHROPIC",
            ModelCatalogBridge.resolveSelection(
                listOf(cloud()), "cloud", "claude-sonnet-4-6", "anthropic"
            )?.provider,
        )
    }

    @Test
    fun `an unknown kind or a blank id is refused`() {
        val options = listOf(local())
        assertNull(ModelCatalogBridge.resolveSelection(options, "sideways", "gemma4-e2b", ""))
        assertNull(ModelCatalogBridge.resolveSelection(options, null, "gemma4-e2b", ""))
        assertNull(ModelCatalogBridge.resolveSelection(options, "local", "  ", ""))
        assertNull(ModelCatalogBridge.resolveSelection(options, "local", "gemma-9b", ""))
    }

    @Test
    fun `a local file that is not downloaded is refused`() {
        assertNull(
            ModelCatalogBridge.resolveSelection(
                listOf(local(usable = false)), "local", "gemma4-e2b", ""
            )
        )
    }

    @Test
    fun `a downloaded model the device cannot hold is refused`() {
        // Same rule as the native 模型与任务 page, which hides its 使用 pill
        // for these: two doors, one rule.
        assertNull(
            ModelCatalogBridge.resolveSelection(
                listOf(local(supported = false)), "local", "gemma4-e2b", ""
            )
        )
    }

    @Test
    fun `a cloud provider without a key is refused`() {
        assertNull(
            ModelCatalogBridge.resolveSelection(
                listOf(cloud(usable = false)), "cloud", "claude-sonnet-4-6", "ANTHROPIC"
            )
        )
    }

    // ── the custom endpoint row ─────────────────────────────────────────────

    @Test
    fun `no custom row unless the stored provider is custom and named`() {
        assertNull(
            ModelCatalogBridge.customCloudOption(
                providerName = CloudProvider.ANTHROPIC.name,
                modelName = "my-model",
                hasKey = true,
            )
        )
        assertNull(
            ModelCatalogBridge.customCloudOption(
                providerName = "custom",
                modelName = "   ",
                hasKey = true,
            )
        )
    }

    @Test
    fun `a configured custom endpoint becomes a cloud row gated on its key`() {
        val option = ModelCatalogBridge.customCloudOption(
            providerName = "CUSTOM",
            modelName = "my-model",
            hasKey = false,
        )!!
        assertEquals(ModelCatalogBridge.KIND_CLOUD, option.kind)
        assertEquals("my-model", option.id)
        assertEquals("CUSTOM", option.provider)
        assertEquals(CloudProvider.CUSTOM.displayName, option.providerLabel)
        assertFalse(option.usable)
        assertEquals("自定义端点", option.meta)
        assertEquals("cloud:CUSTOM:my-model", option.key)
    }

    @Test
    fun `a custom model name with a quote and a line separator survives the splice`() {
        // The model name is typed by the user, and the payload is spliced into
        // evaluateJavascript as source text. U+2028/U+2029 are line
        // terminators in JS source before ES2019, so one raw separator turns
        // the whole push into a syntax error and the card silently stops
        // updating.
        val option = ModelCatalogBridge.customCloudOption(
            providerName = "CUSTOM",
            modelName = "weird\"name\u2028line",
            hasKey = true,
        )!!
        val raw = ModelCatalogBridge.statusPayload(
            listOf(option),
            ModelCatalogBridge.ActiveSelection(kind = "cloud", id = option.id, provider = "CUSTOM"),
        )
        // Asserted on the serialized text, not on a re-serialized parse: what
        // gets spliced into evaluateJavascript is exactly this string.
        assertFalse(raw.contains('\u2028'))
        assertFalse(raw.contains('\u2029'))
        val json = payload(raw)
        assertEquals(
            "weird\"name\u2028line",
            json["models"].asJsonArray[0].asJsonObject["label"].asString,
        )
    }
}
