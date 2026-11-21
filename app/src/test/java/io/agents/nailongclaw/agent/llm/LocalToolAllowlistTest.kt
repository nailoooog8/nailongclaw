// Copyright 2026 NailongClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.llm

import io.agents.nailongclaw.agent.LlmProvider
import dev.langchain4j.agent.tool.ToolSpecification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the local-path tool allowlist ([LocalToolAllowlist]).
 *
 * The allowlist is the local-context-budget lever: on-device inference
 * declares only the tools the local prompt and the briefing teach, while the
 * cloud path must keep declaring everything the registry holds. The tests
 * pin three things:
 *  - the selection itself (kept domains vs trimmed domains);
 *  - [LocalToolAllowlist.specsForRequest] keeping cloud requests byte-identical
 *    and preserving the existing shadow-display pruning for every provider;
 *  - the honest-degradation notice for a tool the model calls anyway.
 *
 * `registryNames` mirrors `ToolRegistry.registerAllTools(MOBILE)` by hand —
 * registering the real registry in a JVM test is not possible (some tool
 * constructors touch `ClawApplication.instance`). If a tool is added to the
 * registry without updating one of the two lists here, the cross-checks
 * below fail loudly instead of the tool silently vanishing from — or
 * leaking into — the local toolset.
 */
class LocalToolAllowlistTest {

    /** The names ToolRegistry registers for DeviceType.MOBILE (44 tools). */
    private val registryNames = listOf(
        // registerCommonTools
        "get_screen_info", "find_node_info", "input_text", "system_key",
        "open_app", "get_installed_apps", "take_screenshot", "wait",
        "repeat_actions", "clipboard", "send_file", "get_device_info",
        "get_notifications", "reply_via_notification", "make_call", "finish",
        "kb_write", "kb_read", "kb_search", "kb_append", "kb_add_todo",
        "memory_save", "memory_recall", "memory_forget",
        "web_search",
        "calendar_read", "calendar_create",
        "schedule_task", "list_scheduled_tasks", "cancel_scheduled_task",
        // registerMobileTools
        "tap", "tap_node", "long_press", "swipe", "scroll_to_find",
        "find_and_tap", "read_conversation", "send_message", "auto_reply",
        "shadow_get_screen_info", "shadow_open_app", "shadow_tap_node",
        "shadow_swipe", "shadow_input_text",
    )

    private fun spec(name: String): ToolSpecification =
        ToolSpecification.builder().name(name).description("desc for $name").build()

    private fun specs(names: List<String>): List<ToolSpecification> = names.map { spec(it) }

    // ── Selection: what stays ──────────────────────────────────────────────────

    @Test
    fun `every kept capability-domain tool is declared`() {
        val mustStay = listOf(
            "get_screen_info", "find_node_info", "get_device_info",      // observe
            "get_notifications", "get_installed_apps", "take_screenshot",
            "read_conversation",
            "tap", "tap_node", "long_press", "swipe",                    // act
            "scroll_to_find", "find_and_tap", "input_text", "system_key",
            "open_app",
            "send_message", "reply_via_notification", "make_call",       // communicate
            "web_search", "clipboard", "wait", "finish",                 // utility
            "memory_save", "memory_recall", "memory_forget",             // memory
        )
        for (name in mustStay) {
            assertTrue("'$name' must be declared on the local path", LocalToolAllowlist.isDeclared(name))
        }
    }

    @Test
    fun `trimmed capability domains are not declared`() {
        val mustGo = listOf(
            "kb_write", "kb_read", "kb_search", "kb_append", "kb_add_todo",
            "calendar_read", "calendar_create",
            "schedule_task", "list_scheduled_tasks", "cancel_scheduled_task",
            "auto_reply", "send_file", "repeat_actions",
            "shadow_get_screen_info", "shadow_open_app", "shadow_tap_node",
            "shadow_swipe", "shadow_input_text",
        )
        for (name in mustGo) {
            assertFalse("'$name' must not be declared on the local path", LocalToolAllowlist.isDeclared(name))
        }
    }

    @Test
    fun `kept and trimmed sets are disjoint and cover the registry exactly once`() {
        assertTrue(
            "a tool cannot be both kept and trimmed",
            LocalToolAllowlist.DECLARED.none { it in LocalToolAllowlist.TRIMMED_EXAMPLES }
        )
        // Every registry tool lands in exactly one of the two sets — a name in
        // neither would silently change behaviour the next time the registry
        // grows, and a name in both is a contradiction.
        val all = registryNames.toSet()
        assertEquals(
            "kept + trimmed must cover every registry name exactly once",
            all,
            (LocalToolAllowlist.DECLARED + LocalToolAllowlist.TRIMMED_EXAMPLES).toSet()
        )
        assertEquals(
            "no allowlist entry may drift outside the registry",
            emptySet<String>(),
            (LocalToolAllowlist.DECLARED + LocalToolAllowlist.TRIMMED_EXAMPLES) - all
        )
    }

    @Test
    fun `isDeclared is exact on unknown names and tolerates whitespace`() {
        assertFalse(LocalToolAllowlist.isDeclared("not_a_real_tool"))
        assertFalse(LocalToolAllowlist.isDeclared(""))
        assertTrue(LocalToolAllowlist.isDeclared(" tap_node "))
        // An MCP tool is third-party code behind a confirmation gate — never
        // part of the local toolset.
        assertFalse(LocalToolAllowlist.isDeclared("mcp_demo_echo"))
    }

    // ── filter: the spec list that goes out ────────────────────────────────────

    @Test
    fun `filter keeps allowed specs in order and drops the rest`() {
        val input = specs(listOf("finish", "kb_read", "get_screen_info", "calendar_read", "tap_node"))
        val out = LocalToolAllowlist.filter(input)
        assertEquals(listOf("finish", "get_screen_info", "tap_node"), out.map { it.name() })
    }

    @Test
    fun `filter drops names the registry no longer knows`() {
        val out = LocalToolAllowlist.filter(specs(listOf("ghost_tool", "wait")))
        assertEquals(listOf("wait"), out.map { it.name() })
    }

    @Test
    fun `filter of the full mobile registry keeps exactly the kept set`() {
        val out = LocalToolAllowlist.filter(specs(registryNames))
        assertEquals(LocalToolAllowlist.DECLARED, out.map { it.name() }.toSet())
        assertTrue("the local toolset must actually be smaller than the registry", out.size < registryNames.size)
    }

    // ── specsForRequest: the per-request pipeline ──────────────────────────────

    @Test
    fun `cloud requests pass through untouched regardless of shadow state`() {
        val full = specs(registryNames)
        assertEquals(full.map { it.name() }, LocalToolAllowlist.specsForRequest(full, LlmProvider.OPENAI, true).map { it.name() })
        assertEquals(full.map { it.name() }, LocalToolAllowlist.specsForRequest(full, LlmProvider.ANTHROPIC, true).map { it.name() })
    }

    @Test
    fun `existing shadow pruning still applies to cloud when no display exists`() {
        val full = specs(registryNames)
        val out = LocalToolAllowlist.specsForRequest(full, LlmProvider.OPENAI, false)
        assertFalse("shadow specs stay pruned without a display", out.any { it.name().startsWith("shadow_") })
        assertEquals(
            "everything except the shadow tools is untouched",
            registryNames.filterNot { it.startsWith("shadow_") },
            out.map { it.name() }
        )
    }

    @Test
    fun `local requests get the allowlist whether or not a shadow display exists`() {
        val full = specs(registryNames)
        for (shadowCreated in listOf(true, false)) {
            val out = LocalToolAllowlist.specsForRequest(full, LlmProvider.LOCAL, shadowCreated)
            assertEquals(
                LocalToolAllowlist.DECLARED,
                out.map { it.name() }.toSet()
            )
            assertFalse(out.any { it.name().startsWith("shadow_") })
        }
    }

    @Test
    fun `the device context tool count is what the request declares`() {
        // DefaultAgentService.buildDeviceContext reports "- Registered Tools: N"
        // from specsForRequest(...).size — the count the request actually
        // declares. It used to report ToolRegistry.getAllTools().size (44) even
        // on the local path, where only the allowlist cut goes out, so the
        // device info promised tools the on-device model could not call.
        val full = specs(registryNames)
        assertEquals(
            "local declares only the allowlist cut",
            26,
            LocalToolAllowlist.specsForRequest(full, LlmProvider.LOCAL, false).size
        )
        assertEquals(
            "local with a shadow display still declares the same cut",
            26,
            LocalToolAllowlist.specsForRequest(full, LlmProvider.LOCAL, true).size
        )
        assertEquals(
            "cloud without a display declares everything except the shadow tools",
            39,
            LocalToolAllowlist.specsForRequest(full, LlmProvider.OPENAI, false).size
        )
        assertEquals(
            "cloud with a display declares the whole registry",
            44,
            LocalToolAllowlist.specsForRequest(full, LlmProvider.OPENAI, true).size
        )
    }

    // ── Honest degradation ─────────────────────────────────────────────────────

    @Test
    fun `the degradation notice names the tool and is honest about what happened`() {
        for (tool in listOf("calendar_read", "schedule_task")) {
            val notice = LocalToolAllowlist.degradationNotice(tool)
            assertTrue("the model must see which tool was refused", notice.contains(tool))
            assertTrue("the refusal must say the tool did not run", notice.contains("not available"))
            assertTrue("the model must be told not to retry", notice.contains("Do not retry"))
            assertTrue(
                "the user-visible way out must be stated",
                notice.contains("cloud model")
            )
            assertFalse(
                "a refusal must never read as success",
                notice.contains("result") && notice.contains("success")
            )
        }
    }
}
