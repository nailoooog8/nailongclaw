// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool

import io.agents.nailongclaw.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the second defence layer added in SECURITY_AUDIT_R2 F2: tools that act
 * on the world are refused for tasks that arrive from a channel, where nobody
 * is watching the screen to abort.
 */
class ToolRiskPolicyTest {

    // ---- Risk classification ----

    @Test
    fun `ordinary world-acting tools are write tier`() {
        val write = listOf(
            "send_message",
            "send_file",
            "auto_reply",
            "input_text",
            "clipboard",
            "reply_via_notification",
            // Agent-trusted persistent state.
            "kb_write",
            "memory_save",
            "memory_forget",
            // Registered by the UI track; classified here so it lands
            // classified on the day it first appears.
            "calendar_create",
        )
        for (name in write) {
            assertEquals(
                "$name should be WRITE",
                ToolRiskPolicy.Risk.WRITE,
                ToolRiskPolicy.riskOf(name),
            )
        }
    }

    @Test
    fun `irreversible tools are destructive tier`() {
        for (name in listOf("make_call", "system_key")) {
            assertEquals(
                "$name should be DESTRUCTIVE",
                ToolRiskPolicy.Risk.DESTRUCTIVE,
                ToolRiskPolicy.riskOf(name),
            )
        }
    }

    @Test
    fun `observation tools are read only`() {
        val readOnly = listOf(
            "wait",
            "finish",
            "get_screen_info",
            "get_notifications",
            "get_device_info",
            "get_installed_apps",
            "take_screenshot",
            "kb_read",
            "kb_search",
            "memory_recall",
            "web_search",
            "calendar_read",
        )
        for (name in readOnly) {
            assertEquals(
                "$name should be READ_ONLY",
                ToolRiskPolicy.Risk.READ_ONLY,
                ToolRiskPolicy.riskOf(name),
            )
        }
    }

    @Test
    fun `an unclassified tool defaults to destructive`() {
        // Fail closed: a tool nobody classified must not become remotely
        // reachable — or eligible for a remembered yes — just by being added
        // to the registry.
        assertEquals(
            ToolRiskPolicy.Risk.DESTRUCTIVE,
            ToolRiskPolicy.riskOf("some_future_tool"),
        )
    }

    @Test
    fun `remote mcp tools are treated as destructive`() {
        // MCP servers are third parties; their tools can do anything the
        // server decides, so they never get a remote-source exemption and
        // never earn a remembered blanket yes.
        assertEquals(
            ToolRiskPolicy.Risk.DESTRUCTIVE,
            ToolRiskPolicy.riskOf("mcp_my_server_do_anything"),
        )
    }

    @Test
    fun `blank tool name is destructive`() {
        assertEquals(ToolRiskPolicy.Risk.DESTRUCTIVE, ToolRiskPolicy.riskOf(""))
        assertEquals(ToolRiskPolicy.Risk.DESTRUCTIVE, ToolRiskPolicy.riskOf("   "))
    }

    // ---- Source gating ----

    @Test
    fun `high impact tools are blocked for every remote channel`() {
        val remote = listOf(
            Channel.TELEGRAM,
            Channel.DISCORD,
            Channel.WECHAT,
            Channel.QQ,
            Channel.DOUYIN,
        )
        for (channel in remote) {
            assertTrue(
                "make_call must be blocked on $channel",
                ToolRiskPolicy.isBlockedForChannel("make_call", channel),
            )
            assertTrue(
                "system_key must be blocked on $channel",
                ToolRiskPolicy.isBlockedForChannel("system_key", channel),
            )
        }
    }

    @Test
    fun `read only tools stay available to remote channels`() {
        assertFalse(ToolRiskPolicy.isBlockedForChannel("get_screen_info", Channel.TELEGRAM))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("get_notifications", Channel.QQ))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("memory_recall", Channel.DISCORD))
        // Knowledge questions from a chat channel are the primary web_search
        // use case — the gate must not refuse them.
        assertFalse(ToolRiskPolicy.isBlockedForChannel("web_search", Channel.WECHAT))
    }

    @Test
    fun `local tasks keep every tool`() {
        // The app's own UI is the one place a human is present, so the gate
        // must not narrow what the user can ask for by hand.
        assertFalse(ToolRiskPolicy.isBlockedForChannel("make_call", Channel.LOCAL))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("input_text", Channel.LOCAL))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("system_key", Channel.LOCAL))
    }

    @Test
    fun `a null source channel is treated as local`() {
        // Happens before any task session is recorded; refusing here would
        // break the app's own launch path.
        assertFalse(ToolRiskPolicy.isBlockedForChannel("make_call", null))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("send_message", null))
    }

    @Test
    fun `block reason names the tool and the source`() {
        val reason = ToolRiskPolicy.blockReason("make_call", Channel.TELEGRAM)
        assertTrue(reason.contains("make_call"))
        assertTrue(reason.contains("Telegram"))
    }

    // ---- External automation (R3-01) ----

    @Test
    fun `external automation tasks are gated like any remote source`() {
        // The external-automation broadcast is not the user, even when the user
        // enabled the feature: no one is watching the screen when a Tasker task
        // runs. It used to be recorded as Channel.LOCAL and therefore had every
        // tool, which turned the feature into full remote control of the phone.
        for (name in listOf("make_call", "send_message", "send_file", "input_text", "system_key", "clipboard")) {
            assertTrue(
                "$name must be blocked for Channel.EXTERNAL",
                ToolRiskPolicy.isBlockedForChannel(name, Channel.EXTERNAL),
            )
        }
        // Read-only observation still works, so the feature stays useful.
        assertFalse(ToolRiskPolicy.isBlockedForChannel("get_screen_info", Channel.EXTERNAL))
        assertFalse(ToolRiskPolicy.isBlockedForChannel("web_search", Channel.EXTERNAL))
    }

    @Test
    fun `external automation block reason points the user back at the app`() {
        val reason = ToolRiskPolicy.blockReason("send_file", Channel.EXTERNAL)
        assertTrue(reason.contains("send_file"))
        assertTrue(reason.contains("External Automation"))
        assertTrue(reason.contains("NailongClaw app"))
    }

    // ---- Scheduled tasks (M4) ----

    @Test
    fun `scheduled tasks are held to the read only ceiling`() {
        // The alarm fires with nobody in front of the screen, so a fired task
        // gets the same ceiling as any other unwatched source — not the local
        // tier it used to inherit. The scary half: unattended, a scheduled
        // task can no longer dial, type, or press the key that approves
        // system dialogs.
        val acting = listOf(
            "make_call", "system_key", // DESTRUCTIVE
            "send_message", "input_text", "clipboard", "send_file",
            "memory_save", "kb_write", "schedule_task", // WRITE
        )
        for (name in acting) {
            assertTrue(
                "$name must be blocked for Channel.SCHEDULED",
                ToolRiskPolicy.isBlockedForChannel(name, Channel.SCHEDULED),
            )
        }
        // The useful half: a 07:30 brief can still observe and answer, and the
        // answer reaches the user as a notification.
        val observing = listOf(
            "calendar_read", "get_notifications", "web_search",
            "get_screen_info", "take_screenshot", "memory_recall",
            "list_scheduled_tasks",
        )
        for (name in observing) {
            assertFalse(
                "$name must stay available to Channel.SCHEDULED",
                ToolRiskPolicy.isBlockedForChannel(name, Channel.SCHEDULED),
            )
        }
    }

    @Test
    fun `a scheduled task does not inherit the local tier`() {
        // The regression this guards: the alarm used to fire its task as
        // Channel.LOCAL, which handed an unattended run every tool. Same task
        // text, same moment, opposite outcome depending only on the label.
        assertFalse(ToolRiskPolicy.isBlockedForChannel("make_call", Channel.LOCAL))
        assertTrue(ToolRiskPolicy.isBlockedForChannel("make_call", Channel.SCHEDULED))
        // Fail closed unattended: a tool nobody has classified yet is refused.
        assertTrue(ToolRiskPolicy.isBlockedForChannel("some_future_tool", Channel.SCHEDULED))
    }

    @Test
    fun `scheduled block reason asks for a human instead of pointing at the app`() {
        // "Run this from the NailongClaw app instead" is the right advice for a
        // Telegram task and the wrong one at 03:00 — the user is asleep. The
        // model needs a reason it can repeat to them honestly.
        val reason = ToolRiskPolicy.blockReason("make_call", Channel.SCHEDULED)
        assertTrue(reason.contains("make_call"))
        assertTrue(reason.contains("scheduled"))
        assertTrue(reason.contains("observe"))
        assertTrue(reason.contains("Tell the user"))
        // It must not read as a transient tool error the loop should retry.
        assertFalse(reason.contains("Run this from the NailongClaw app instead"))
    }
}
