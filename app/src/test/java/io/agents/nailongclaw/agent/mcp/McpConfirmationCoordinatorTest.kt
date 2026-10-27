// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.mcp

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the waiting half of the confirmation loop.
 *
 * The gate is pure and covered separately; what is worth testing here is the
 * part that can deadlock or mis-answer: a thread parks, exactly one outcome is
 * recorded, and a tap that arrives after the wait ended cannot answer a later
 * request. Uses a real thread and real latches — the failure mode this guards
 * against *is* the threading.
 *
 * The coordinator is a process-wide singleton, so every case attaches its own
 * surface (which replaces the previous one) and drains whatever it started.
 */
class McpConfirmationCoordinatorTest {

    private class FakeSurface(override var isForeground: Boolean = true) :
        McpConfirmationCoordinator.Surface {
        val shown = mutableListOf<McpConfirmationRequest>()
        val hidden = mutableListOf<McpConfirmationGate.Verdict>()
        override fun showConfirmation(request: McpConfirmationRequest) {
            shown += request
        }
        override fun hideConfirmation(verdict: McpConfirmationGate.Verdict) {
            hidden += verdict
        }
    }

    private fun request(name: String) = McpConfirmationRequest(
        toolName = name,
        displayName = "echo",
        serverName = "qatools",
        description = "Echoes text back",
        paramsPreview = "text = hello",
    )

    /** Parks [block] on its own thread, the way the agent loop calls it. */
    private fun park(block: () -> Unit): Thread {
        val thread = Thread(block, "mcp-confirm-test")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun waitUntil(deadlineMs: Long = 3_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition never became true within ${deadlineMs}ms")
    }

    /** Leaves the coordinator with no surface at all. */
    private fun clearSurface() {
        val surface = FakeSurface()
        McpConfirmationCoordinator.attach(surface)
        McpConfirmationCoordinator.detach(surface)
    }

    @Test
    fun `no surface refuses immediately instead of waiting`() {
        clearSurface()
        val result = AtomicReference<McpConfirmationGate.Verdict>()
        val thread = park {
            result.set(McpConfirmationCoordinator.awaitDecision(request("mcp_t_nosurface")))
        }
        thread.join(5_000)
        assertFalse(thread.isAlive)
        assertEquals(McpConfirmationGate.Outcome.DENY_NO_SURFACE, result.get().outcome)
        assertFalse(result.get().allowed)
        assertTrue(result.get().reason.isNotBlank())
    }

    @Test
    fun `a backgrounded surface refuses immediately`() {
        val surface = FakeSurface(isForeground = false)
        McpConfirmationCoordinator.attach(surface)
        val result = AtomicReference<McpConfirmationGate.Verdict>()
        val thread = park {
            result.set(McpConfirmationCoordinator.awaitDecision(request("mcp_t_bg")))
        }
        thread.join(5_000)
        assertEquals(McpConfirmationGate.Outcome.DENY_NOT_VISIBLE, result.get().outcome)
        // The card was never drawn, so there is nothing to take down either.
        assertTrue(surface.shown.isEmpty())
        assertTrue(surface.hidden.isEmpty())
    }

    @Test
    fun `allow once shows the card, then releases the parked thread`() {
        val surface = FakeSurface()
        McpConfirmationCoordinator.attach(surface)
        val result = AtomicReference<McpConfirmationGate.Verdict>()
        val thread = park {
            result.set(McpConfirmationCoordinator.awaitDecision(request("mcp_t_once")))
        }
        waitUntil { McpConfirmationCoordinator.isAwaiting() }
        waitUntil { surface.shown.size == 1 }
        assertTrue(McpConfirmationCoordinator.isAwaiting())

        McpConfirmationCoordinator.submit(McpConfirmationGate.Decision.ALLOW_ONCE)
        thread.join(5_000)

        assertEquals(McpConfirmationGate.Outcome.ALLOW_ONCE, result.get().outcome)
        assertTrue(result.get().allowed)
        assertTrue(result.get().reason.isEmpty())
        // Card taken down exactly once, with the verdict that ended the wait.
        assertEquals(1, surface.hidden.size)
        assertEquals(McpConfirmationGate.Outcome.ALLOW_ONCE, surface.hidden.single().outcome)
        assertFalse(McpConfirmationCoordinator.isAwaiting())
    }

    @Test
    fun `deny releases the parked thread with a refusal the model can act on`() {
        val surface = FakeSurface()
        McpConfirmationCoordinator.attach(surface)
        val result = AtomicReference<McpConfirmationGate.Verdict>()
        val thread = park {
            result.set(McpConfirmationCoordinator.awaitDecision(request("mcp_t_deny")))
        }
        waitUntil { surface.shown.size == 1 }

        McpConfirmationCoordinator.submit(McpConfirmationGate.Decision.DENY)
        thread.join(5_000)

        assertEquals(McpConfirmationGate.Outcome.DENY_BY_USER, result.get().outcome)
        assertFalse(result.get().allowed)
        assertTrue(result.get().reason.contains("declined"))
        assertTrue(result.get().reason.contains("echo"))
    }

    @Test
    fun `an unanswered wait times out into a refusal`() {
        val surface = FakeSurface()
        McpConfirmationCoordinator.attach(surface)
        val result = AtomicReference<McpConfirmationGate.Verdict>()
        val thread = park {
            result.set(
                McpConfirmationCoordinator.awaitDecision(request("mcp_t_timeout"), timeoutMs = 300)
            )
        }
        waitUntil { surface.shown.size == 1 }
        thread.join(5_000)

        assertEquals(McpConfirmationGate.Outcome.DENY_TIMED_OUT, result.get().outcome)
        assertFalse(result.get().allowed)
        // The card is removed either way — a timed-out card must not linger.
        assertEquals(1, surface.hidden.size)
        assertFalse(McpConfirmationCoordinator.isAwaiting())
    }

    @Test
    fun `allow always stops asking on the next call`() {
        val surface = FakeSurface()
        McpConfirmationCoordinator.attach(surface)
        val first = AtomicReference<McpConfirmationGate.Verdict>()
        val thread = park {
            first.set(McpConfirmationCoordinator.awaitDecision(request("mcp_t_always")))
        }
        waitUntil { surface.shown.size == 1 }
        McpConfirmationCoordinator.submit(McpConfirmationGate.Decision.ALLOW_ALWAYS)
        thread.join(5_000)
        assertEquals(McpConfirmationGate.Outcome.ALLOW_ALWAYS, first.get().outcome)

        val second = AtomicReference<McpConfirmationGate.Verdict>()
        val thread2 = park {
            second.set(McpConfirmationCoordinator.awaitDecision(request("mcp_t_always")))
        }
        thread2.join(5_000)

        assertEquals(McpConfirmationGate.Outcome.ALLOW_REMEMBERED, second.get().outcome)
        assertTrue(second.get().allowed)
        // No second card: that is the point of the button.
        assertEquals(1, surface.shown.size)
    }

    @Test
    fun `a tap that lands after the wait ended cannot answer a later request`() {
        val surface = FakeSurface()
        McpConfirmationCoordinator.attach(surface)
        val first = AtomicReference<McpConfirmationGate.Verdict>()
        val thread = park {
            first.set(
                McpConfirmationCoordinator.awaitDecision(request("mcp_t_late"), timeoutMs = 250)
            )
        }
        waitUntil { surface.shown.size == 1 }
        thread.join(5_000)
        assertEquals(McpConfirmationGate.Outcome.DENY_TIMED_OUT, first.get().outcome)

        // The user's finger lands just after the card was refused.
        McpConfirmationCoordinator.submit(McpConfirmationGate.Decision.ALLOW_ALWAYS)

        val second = AtomicReference<McpConfirmationGate.Verdict>()
        val thread2 = park {
            second.set(McpConfirmationCoordinator.awaitDecision(request("mcp_t_late")))
        }
        waitUntil { surface.shown.size == 2 }

        // Still asking — the stale tap must not have granted anything.
        assertTrue(McpConfirmationCoordinator.isAwaiting())
        McpConfirmationCoordinator.submit(McpConfirmationGate.Decision.DENY)
        thread2.join(5_000)
        assertEquals(McpConfirmationGate.Outcome.DENY_BY_USER, second.get().outcome)
    }

    @Test
    fun `detaching the surface refuses the wait instead of parking forever`() {
        val surface = FakeSurface()
        McpConfirmationCoordinator.attach(surface)
        val result = AtomicReference<McpConfirmationGate.Verdict>()
        val thread = park {
            result.set(McpConfirmationCoordinator.awaitDecision(request("mcp_t_detach")))
        }
        waitUntil { surface.shown.size == 1 }

        McpConfirmationCoordinator.detach(surface)
        thread.join(5_000)

        assertEquals(McpConfirmationGate.Outcome.DENY_CANCELLED, result.get().outcome)
        assertFalse(result.get().allowed)
        assertEquals(1, surface.hidden.size)
    }

    @Test
    fun `a second concurrent call is refused rather than queued`() {
        val surface = FakeSurface()
        McpConfirmationCoordinator.attach(surface)
        val first = AtomicReference<McpConfirmationGate.Verdict>()
        val thread = park {
            first.set(McpConfirmationCoordinator.awaitDecision(request("mcp_t_busy_a")))
        }
        waitUntil { surface.shown.size == 1 }

        // Called from this thread: it must not park, or the test would hang
        // here instead of failing.
        val second = McpConfirmationCoordinator.awaitDecision(request("mcp_t_busy_b"))
        assertEquals(McpConfirmationGate.Outcome.DENY_BUSY, second.outcome)
        assertFalse(second.allowed)
        assertEquals(1, surface.shown.size)

        McpConfirmationCoordinator.submit(McpConfirmationGate.Decision.DENY)
        thread.join(5_000)
        assertEquals(McpConfirmationGate.Outcome.DENY_BY_USER, first.get().outcome)
    }
}
