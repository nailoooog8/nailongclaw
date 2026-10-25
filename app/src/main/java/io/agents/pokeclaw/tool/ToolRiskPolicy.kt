// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import io.agents.pokeclaw.channel.Channel

/**
 * Central risk policy for tool execution.
 *
 * PokeClaw can actuate the phone on the user's behalf: place calls, type into
 * whatever app is focused, press the unlock key, send messages. Before this
 * policy existed there was no gate between "the model decided to call it" and
 * "the phone did it" — every tool ran, always, for every task source.
 *
 * That matters because a task can arrive from a source the user is not
 * watching. A Telegram message, an automation broadcast from another app, or
 * a scheduled task all reach the same agent loop as a tap in the app, but they
 * have no human in front of the screen to abort on. Authorisation for those
 * sources is checked upstream in [io.agents.pokeclaw.channel.security.ChannelSecurityGuard];
 * this policy is the second, independent layer that assumes that check was
 * wrong, stale, or bypassed.
 *
 * ## Three tiers, one rule each (M2)
 *
 * - [Risk.READ_ONLY] — observes state. Runs from any source, never asks.
 * - [Risk.WRITE] — acts on the world in ordinary, recoverable ways. Runs
 *   locally without a card (the user typed the task), refused for remote
 *   sources, and when a card *is* shown (MCP path) it may be answered with a
 *   remembered "always allow".
 * - [Risk.DESTRUCTIVE] — irreversible if wrong: dials a real number, presses
 *   the key that approves system dialogs, or is third-party code whose reach
 *   nobody has classified. Same channel gating as WRITE, but a destructive
 *   call is never given a remembered yes — each run is approved on its own
 *   card. Approval fatigue is fought by classifying fewer things destructive,
 *   not by remembering blanket approvals for the worst tier.
 *
 * Design rule: **fail closed**. A tool that is not explicitly classified is
 * DESTRUCTIVE — the most restrictive tier. New tools therefore start blocked
 * for remote sources and ineligible for "always allow" until someone
 * classifies them, instead of silently inheriting unrestricted access.
 * `mcp_*` tools are third-party code and stay DESTRUCTIVE on principle: a
 * server's own description cannot downgrade itself (see
 * [io.agents.pokeclaw.agent.mcp.McpDescriptionSanitizer] for the same rule on
 * the card); only an explicit entry in [WRITE_TOOLS] can, and today nothing
 * has earned one.
 */
object ToolRiskPolicy {

    /**
     * How much damage a tool can do if it runs without a human watching.
     */
    enum class Risk {
        /** Observes state. No outward effect; worst case is disclosure. */
        READ_ONLY,

        /** Acts on the world in ordinary ways; a remembered yes may exist. */
        WRITE,

        /**
         * Irreversible if wrong, or unclassified. No remembered "always
         * allow" — every run is approved on its own.
         */
        DESTRUCTIVE,
    }

    /**
     * Tools that act on the world but whose effects are ordinary: a message
     * can be followed up, a note edited, a screen tapped again. Remote
     * sources still cannot call them — unwatched is unwatched, whatever the
     * tier — but locally they run on the strength of the user's own task, and
     * an MCP-tier equivalent may be answered with 始终允许.
     */
    private val WRITE_TOOLS: Set<String> = setOf(
        // Outward-facing: another human receives the result.
        "send_message",
        "reply_via_notification",
        "send_file",
        "auto_reply",
        // Acts on whatever happens to be focused — recoverable, but it is
        // still the user's text going somewhere they pointed at.
        "input_text",
        // Reads AND writes the clipboard; the read side alone is a live
        // secret (one-time codes, passwords just copied) so both are gated.
        "clipboard",
        // Agent-trusted persistent state: a later task will believe these.
        "kb_write",
        "kb_append",
        "kb_add_todo",
        "memory_save",
        "memory_forget",
        // Creates a calendar event. Registered by the UI track; classified
        // here so it lands classified on the day it first appears.
        "calendar_create",
        // Future unattended execution: not something an unwatched remote
        // channel should be able to mint.
        "schedule_task",
        "cancel_scheduled_task",
    )

    /**
     * Tools whose worst case cannot be undone: a real call is placed and
     * billed, or a keypress can approve a system dialog nothing saw.
     */
    private val DESTRUCTIVE_TOOLS: Set<String> = setOf(
        "make_call",
        "system_key",
    )

    /**
     * Tools that are safe to call from any source.
     *
     * This is an allowlist, not a denylist, so that adding a new tool cannot
     * accidentally expose it to remote control.
     */
    private val READ_ONLY_TOOLS: Set<String> = setOf(
        "wait",
        "finish",
        "get_screen_info",
        "get_notifications",
        "find_node_info",
        "get_device_info",
        "get_installed_apps",
        "take_screenshot",
        "scroll_to_find",
        "find_and_tap",
        "read_conversation",
        "search_app_in_store",
        "kb_read",
        "kb_search",
        "memory_recall",
        // Spend is capped by the configured API key and returns public data
        // only; remote channels asking knowledge questions are the primary use
        // case. Results are third-party web text, so they are fenced as
        // untrusted data by ContextFence (R3-02) — that, not this list, is
        // what stops a poisoned snippet from reading as an instruction.
        "web_search",
        // Same reasoning as web_search: observation only, and the event text
        // it returns is other people's writing, fenced by ContextFence so a
        // poisoned meeting title reads as data.
        "calendar_read",
        // The app's own timers, listed back — pure observation.
        "list_scheduled_tasks",
    )

    /**
     * Risk class of [toolName]. Unknown, blank, and `mcp_*` names are
     * [Risk.DESTRUCTIVE] — fail closed.
     */
    fun riskOf(toolName: String): Risk {
        val name = toolName.trim()
        if (name in READ_ONLY_TOOLS) return Risk.READ_ONLY
        // Explicit classifications win in destructive's favour: a name listed
        // as destructive stays destructive even if it also appears above.
        if (name in DESTRUCTIVE_TOOLS) return Risk.DESTRUCTIVE
        if (name in WRITE_TOOLS) return Risk.WRITE
        // Third-party code whose reach nobody has classified, and any future
        // tool nobody has classified yet: worst tier until proven otherwise.
        return Risk.DESTRUCTIVE
    }

    /**
     * True when [toolName] must not run for a task arriving over [channel].
     *
     * A `null` channel means the task is not associated with a remote source.
     * That happens for a task started from the app before any session is
     * recorded, so it is treated as interactive and allowed — refusing here
     * would break the app's own UI, which is the one place a human is present.
     *
     * Every tier except READ_ONLY is refused: WRITE and DESTRUCTIVE alike
     * act on a phone nobody is watching.
     *
     * [Channel.EXTERNAL] is gated like any other remote channel even though the
     * user turned the feature on: a broadcast still has nobody watching the
     * screen, and "enabled by the user" is not the same statement as "the user
     * is asking for this" (R3-01).
     */
    fun isBlockedForChannel(toolName: String, channel: Channel?): Boolean {
        if (channel == null || channel == Channel.LOCAL) return false
        return riskOf(toolName) != Risk.READ_ONLY
    }

    /**
     * Message shown to the model and echoed to the requesting channel when a
     * tool is refused. Kept explicit so the failure is diagnosable from the
     * transcript instead of looking like a transient tool error.
     */
    fun blockReason(toolName: String, channel: Channel?): String {
        val source = channel?.displayName ?: "Local"
        return "Tool '$toolName' is blocked for tasks arriving from $source. " +
            "It can act on the phone irreversibly (send, dial, type, unlock), and a " +
            "task from $source has no one watching the screen to stop it. " +
            "Run this from the PokeClaw app instead."
    }
}
