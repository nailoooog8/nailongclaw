// Copyright 2026 NailongClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.llm

import io.agents.nailongclaw.agent.LlmProvider
import dev.langchain4j.agent.tool.ToolSpecification

/**
 * Which tools the LOCAL path may declare to the model, by capability domain.
 *
 * Gemma 4 E2B runs inside an 8192-token window that the fixed cost of a task
 * already fills to the brim: LiteRT-LM pre-fills every declared tool's JSON
 * schema into the context, so declaring all ~44 registry tools spends the
 * window on schemas the on-device model can rarely use correctly, and the
 * user's actual message no longer fits (8202/8218 ≥ 8192). This allowlist is
 * the local-only cut: it keeps what the local task prompt and the briefing's
 * memory rules teach, plus the base UI actions a phone task needs —
 * everything else stays registered and runnable for the cloud path but is
 * not declared on-device.
 *
 * Tool names below must match `BaseTool.getName()` in the registry; a name
 * that drifts would silently stop being declared, which the allowlist test
 * pins against the real registry sets.
 *
 * Pure logic: no Android types, so the selection itself is unit-testable.
 * The per-request wiring lives in [DefaultAgentService.requestToolSpecs]
 * via [specsForRequest] — cloud providers are returned untouched.
 */
object LocalToolAllowlist {

    // ── Keep: observe the phone ────────────────────────────────────────────────
    // Screen reading is the core loop step; the rest are the read-only queries
    // the local prompt tells the model to prefer over opening apps.
    private val OBSERVE = setOf(
        "get_screen_info",      // loop step 1: see what's on screen
        "find_node_info",       // inspect one node by id (verify before acting)
        "get_device_info",      // battery/wifi/storage — prompt says never open Settings for these
        "get_notifications",    // prompt: use this instead of pulling down the shade
        "get_installed_apps",   // prompt: use when asked what apps exist
        "take_screenshot",      // prompt-limited: only for photos/maps/games the tree cannot show
        "read_conversation",    // read the open chat's messages from the tree
    )

    // ── Keep: act on the phone ─────────────────────────────────────────────────
    // The base UI vocabulary. swipe/long_press are not named in the local
    // prompt but are how pull-to-refresh, context menus and shades happen;
    // dropping them would amputate ordinary navigation.
    private val ACT = setOf(
        "tap",
        "tap_node",
        "long_press",
        "swipe",
        "scroll_to_find",
        "find_and_tap",
        "input_text",
        "system_key",           // back/home/enter
        "open_app",
    )

    // ── Keep: communicate ──────────────────────────────────────────────────────
    private val COMMUNICATE = setOf(
        "send_message",
        "reply_via_notification",
        "make_call",
    )

    // ── Keep: information & utility ────────────────────────────────────────────
    private val UTILITY = setOf(
        "web_search",           // knowledge questions without opening a browser
        "clipboard",
        "wait",
        "finish",               // the only way a task ends cleanly
    )

    // ── Keep: long-term memory ─────────────────────────────────────────────────
    // MemoryStore.briefing teaches these three by name in every system prompt;
    // trimming them would make the briefing lie.
    private val MEMORY = setOf(
        "memory_save",
        "memory_recall",
        "memory_forget",
    )

    /** Every tool the LOCAL path declares. Capability domains, flattened. */
    val DECLARED: Set<String> = OBSERVE + ACT + COMMUNICATE + UTILITY + MEMORY

    /** Registry capabilities that stay cloud-only (declared but not on-device). */
    val TRIMMED_EXAMPLES: Set<String> = setOf(
        "kb_write", "kb_read", "kb_search", "kb_append", "kb_add_todo",  // knowledge vault
        "calendar_read", "calendar_create",                              // device calendar
        "schedule_task", "list_scheduled_tasks", "cancel_scheduled_task", // durable timers
        "auto_reply",        // send_message/reply_via_notification cover it
        "send_file",         // file-path surface, low-frequency
        "repeat_actions",    // niche batch helper
        "shadow_get_screen_info", "shadow_open_app", "shadow_tap_node",
        "shadow_swipe", "shadow_input_text",  // second-display orchestration
    )

    /** True when [toolName] is one of the tools the LOCAL path declares. */
    @JvmStatic
    fun isDeclared(toolName: String): Boolean = toolName.trim() in DECLARED

    /**
     * Drops the specs the LOCAL path does not declare, preserving order.
     *
     * Unknown/registry-drifted names are dropped too — the allowlist is a
     * list of names to *keep*, so anything not on it simply does not go out.
     */
    @JvmStatic
    fun filter(specs: List<ToolSpecification>): List<ToolSpecification> =
        specs.filter { it.name() in DECLARED }

    /**
     * Per-request spec pipeline shared by every provider.
     *
     * Shadow-display tools are pruned while no shadow display exists (all
     * providers — existing behaviour, kept verbatim); the capability-domain
     * allowlist then applies to LOCAL only, so a cloud request is returned
     * exactly as before this class existed.
     */
    @JvmStatic
    fun specsForRequest(
        specs: List<ToolSpecification>,
        provider: LlmProvider,
        shadowDisplayCreated: Boolean
    ): List<ToolSpecification> {
        var out = specs
        if (!shadowDisplayCreated) out = out.filterNot { it.name().startsWith("shadow_") }
        if (provider == LlmProvider.LOCAL) out = filter(out)
        return out
    }

    /**
     * The refusal the model receives when it calls a trimmed tool anyway
     * (it hallucinated the name, or copied it from a playbook/history).
     *
     * Honest degradation, not a fake success: the tool did not run, the model
     * is told why and what to do instead, and the user-facing instruction is
     * explicit so the loop ends with an explanation instead of silent failure.
     * Same shape as ToolRiskPolicy.blockReason — one refusal, one instruction.
     */
    @JvmStatic
    fun degradationNotice(toolName: String): String =
        "Tool '$toolName' is not available in local model mode: the on-device " +
            "context window is small, so this capability is trimmed from the " +
            "local toolset. Do not retry it. Complete the task with the basic " +
            "screen tools instead, or answer from what you can observe; if the " +
            "capability is essential, tell the user to switch to a cloud model " +
            "in Settings and run the task again."
}
