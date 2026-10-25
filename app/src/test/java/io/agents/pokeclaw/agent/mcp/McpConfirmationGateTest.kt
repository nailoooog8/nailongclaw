// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the confirmation policy.
 *
 * Pure logic by construction (no Android types, no threads, no mocks), which is
 * the exception CLAUDE.md allows — everything that touches a surface or a
 * thread is covered by [McpConfirmationCoordinatorTest] and by QA H27 on a
 * device.
 */
class McpConfirmationGateTest {

    private fun request(name: String = "mcp_qatools_echo") = McpConfirmationRequest(
        toolName = name,
        displayName = "echo",
        serverName = "qatools",
        description = "Echoes text back",
        paramsPreview = "text = hello",
    )

    @Test
    fun `first request with a visible surface asks`() {
        val gate = McpConfirmationGate()
        val req = request()
        assertEquals(
            McpConfirmationGate.Outcome.ASK,
            gate.request(req, surfaceAttached = true, surfaceVisible = true)
        )
        assertEquals(req, gate.pendingRequest())
    }

    @Test
    fun `no surface at all denies without waiting`() {
        val gate = McpConfirmationGate()
        val outcome = gate.request(request(), surfaceAttached = false, surfaceVisible = false)
        assertEquals(McpConfirmationGate.Outcome.DENY_NO_SURFACE, outcome)
        assertFalse(McpConfirmationGate.allows(outcome))
        // Nothing is left pending, so the next call is not blocked by this one.
        assertNull(gate.pendingRequest())
    }

    @Test
    fun `a surface that is not visible denies instead of burning the timeout`() {
        // A card the user cannot see would wait the full 60s and then refuse
        // anyway; refusing now is the same outcome without the stall.
        val gate = McpConfirmationGate()
        val outcome = gate.request(request(), surfaceAttached = true, surfaceVisible = false)
        assertEquals(McpConfirmationGate.Outcome.DENY_NOT_VISIBLE, outcome)
        assertNull(gate.pendingRequest())
    }

    @Test
    fun `a second request while one waits is busy and leaves the first waiting`() {
        val gate = McpConfirmationGate()
        val first = request("mcp_qatools_echo")
        val second = request("mcp_qatools_lookup")
        assertEquals(McpConfirmationGate.Outcome.ASK, gate.request(first, true, true))
        assertEquals(McpConfirmationGate.Outcome.DENY_BUSY, gate.request(second, true, true))
        // The refused call must not take the pending slot from the waiting one.
        assertEquals(first, gate.pendingRequest())
    }

    @Test
    fun `deny resolves and clears`() {
        val gate = McpConfirmationGate()
        gate.request(request(), true, true)
        assertEquals(McpConfirmationGate.Outcome.DENY_BY_USER, gate.resolve(McpConfirmationGate.Decision.DENY))
        assertNull(gate.pendingRequest())
    }

    @Test
    fun `allow once runs the call but is not remembered`() {
        val gate = McpConfirmationGate()
        val req = request()
        gate.request(req, true, true)
        assertEquals(McpConfirmationGate.Outcome.ALLOW_ONCE, gate.resolve(McpConfirmationGate.Decision.ALLOW_ONCE))
        assertFalse(gate.isAlwaysAllowed(req.toolName))
        // Next call must ask again — that is the whole difference from ALWAYS.
        assertEquals(McpConfirmationGate.Outcome.ASK, gate.request(req, true, true))
    }

    @Test
    fun `allow always is remembered and outranks every other refusal`() {
        val gate = McpConfirmationGate()
        val req = request()
        gate.request(req, true, true)
        assertEquals(McpConfirmationGate.Outcome.ALLOW_ALWAYS, gate.resolve(McpConfirmationGate.Decision.ALLOW_ALWAYS))
        assertTrue(gate.isAlwaysAllowed(req.toolName))
        // Checked before the surface checks: a remembered yes must survive the
        // app going to the background, or "always" would only mean "while the
        // screen happens to be on".
        assertEquals(
            McpConfirmationGate.Outcome.ALLOW_REMEMBERED,
            gate.request(req, surfaceAttached = false, surfaceVisible = false)
        )
    }

    @Test
    fun `timeout clears the pending request and is idempotent`() {
        val gate = McpConfirmationGate()
        gate.request(request(), true, true)
        assertEquals(McpConfirmationGate.Outcome.DENY_TIMED_OUT, gate.timeout())
        assertNull(gate.pendingRequest())
        assertNull(gate.timeout())
    }

    @Test
    fun `cancel clears the pending request`() {
        val gate = McpConfirmationGate()
        gate.request(request(), true, true)
        assertEquals(McpConfirmationGate.Outcome.DENY_CANCELLED, gate.cancel())
        assertNull(gate.pendingRequest())
        assertNull(gate.cancel())
    }

    @Test
    fun `resolving with nothing pending is a no-op`() {
        val gate = McpConfirmationGate()
        assertNull(gate.resolve(McpConfirmationGate.Decision.ALLOW_ONCE))
    }

    @Test
    fun `only the allow outcomes let a tool run`() {
        val allowing = listOf(
            McpConfirmationGate.Outcome.ALLOW_REMEMBERED,
            McpConfirmationGate.Outcome.ALLOW_ONCE,
            McpConfirmationGate.Outcome.ALLOW_ALWAYS,
            // The blanket policy from 模型与任务 settings — allowed without a
            // card, but still an explicit allow, not a fallthrough.
            McpConfirmationGate.Outcome.AUTO_ALLOWED,
        )
        val refusing = McpConfirmationGate.Outcome.entries - allowing.toSet()
        allowing.forEach { assertTrue("$it must allow", McpConfirmationGate.allows(it)) }
        refusing.forEach { assertFalse("$it must refuse", McpConfirmationGate.allows(it)) }
    }

    @Test
    fun `every refusal names the tool and the server`() {
        val req = request()
        val refusing = McpConfirmationGate.Outcome.entries.filterNot { McpConfirmationGate.allows(it) }
        // ASK is not a refusal; it is the only non-allowing outcome with no
        // denial text of its own.
        refusing.forEach { outcome ->
            if (outcome == McpConfirmationGate.Outcome.ASK) return@forEach
            val reason = McpConfirmationGate.denialReason(outcome, req, timeoutMs = 60_000)
            assertTrue("$outcome reason must name the tool", reason.contains("echo"))
            assertTrue("$outcome reason must name the server", reason.contains("qatools"))
        }
    }

    @Test
    fun `the timeout denial states the wait the user got`() {
        val reason = McpConfirmationGate.denialReason(
            McpConfirmationGate.Outcome.DENY_TIMED_OUT,
            request(),
            timeoutMs = 60_000,
        )
        assertTrue(reason.contains("60s"))
    }

    @Test
    fun `transcript notes record what the user decided`() {
        val req = request()
        fun note(outcome: McpConfirmationGate.Outcome) =
            McpConfirmationGate.transcriptNote(
                McpConfirmationGate.Verdict(
                    allowed = McpConfirmationGate.allows(outcome),
                    outcome = outcome,
                    reason = "",
                    request = req,
                )
            )
        assertTrue(note(McpConfirmationGate.Outcome.ALLOW_ONCE).startsWith("✓"))
        assertTrue(note(McpConfirmationGate.Outcome.ALLOW_ALWAYS).contains("不再询问"))
        assertTrue(note(McpConfirmationGate.Outcome.DENY_BY_USER).contains("工具未执行"))
        assertTrue(note(McpConfirmationGate.Outcome.DENY_NOT_VISIBLE).contains("后台"))
        // The pending note is what a task killed mid-wait leaves behind.
        assertTrue(McpConfirmationGate.pendingNote(req).startsWith("⏳"))
    }

    @Test
    fun `reset drops remembered allows`() {
        val gate = McpConfirmationGate()
        val req = request()
        gate.request(req, true, true)
        gate.resolve(McpConfirmationGate.Decision.ALLOW_ALWAYS)
        gate.reset()
        assertFalse(gate.isAlwaysAllowed(req.toolName))
        assertEquals(McpConfirmationGate.Outcome.ASK, gate.request(req, true, true))
    }
}
