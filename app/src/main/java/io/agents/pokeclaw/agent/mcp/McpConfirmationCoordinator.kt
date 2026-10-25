// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Process-wide bridge between a blocked tool call and the screen that can
 * unblock it.
 *
 * ## The shape of the problem
 *
 * [io.agents.pokeclaw.tool.ToolRegistry] executes tools synchronously on the
 * agent loop's thread, and the loop cannot continue without the tool's result.
 * So "ask the user" means *parking that thread* while the UI thread shows a
 * card — the same shape as `ClawAccessibilityService.awaitRunning`, which the
 * task path has used for years. What must not happen is the thread parking
 * forever, or two calls parking at once and both cards answering each other.
 *
 * ## Who is the surface
 *
 * Exactly one [Surface] at a time, attached by the activity that is in the
 * foreground. Two activities can drive a task — the Compose chat screen and the
 * Claude UI preview — and each attaches itself while it is resumed, so the card
 * always lands on the screen the user is actually looking at. A surface that
 * exists but is not foreground counts as *not visible* and denies rather than
 * waiting: a card the user cannot see would burn the whole timeout and then
 * refuse anyway.
 *
 * ## Threading
 *
 * [awaitDecision] is called on the agent thread and blocks it. [submit] is
 * called on the UI (or WebView JS) thread and releases it. Shared state is
 * guarded by [lock]; the policy itself lives in [McpConfirmationGate], which
 * is `@Synchronized` and pure.
 *
 * The one invariant that matters: a wait ends with exactly one recorded
 * [Resolution], set by whichever of [submit] / [cancelPending] / the timeout
 * gets there first. Reading it *before* falling back to the timeout is what
 * keeps a tap that lands in the same millisecond as the deadline from being
 * misreported as a timeout.
 */
object McpConfirmationCoordinator {

    private const val TAG = "McpConfirmation"

    /**
     * How long a human gets to answer before the call is refused.
     *
     * Comfortably inside the agent watchdog's 120 s inactivity window
     * (`TaskWatchdogState.DEFAULT_INACTIVITY_TIMEOUT_MS`), which is armed
     * before the tool runs — so waiting can never be what trips it.
     */
    const val DEFAULT_CONFIRM_TIMEOUT_MS = 60_000L

    /** A screen that can show the confirmation card and report the user's answer. */
    interface Surface {
        /** True while the user can actually see this surface. */
        val isForeground: Boolean

        /**
         * Show the card for [request].
         *
         * Called on the agent thread, so implementations must marshal to their
         * own UI thread.
         */
        fun showConfirmation(request: McpConfirmationRequest)

        /**
         * The wait is over; drop the card.
         *
         * Called on the agent thread once the answer (or the timeout) is known,
         * with the verdict so the surface can record it. Implementations must
         * marshal to their own UI thread.
         */
        fun hideConfirmation(verdict: McpConfirmationGate.Verdict)
    }

    /** How a wait ended, and for which request. */
    private data class Resolution(
        val request: McpConfirmationRequest,
        val outcome: McpConfirmationGate.Outcome,
    )

    private val gate = McpConfirmationGate()

    @Volatile
    private var surface: Surface? = null

    /**
     * Whether the user turned on 自动允许 (模型与任务 settings). When true,
     * [awaitDecision] never parks the agent thread and never shows a card —
     * every MCP call runs immediately.
     *
     * A provider rather than a direct KVUtils read, for the same reason
     * [io.agents.pokeclaw.tool.ToolRegistry.sourceChannelProvider] is one:
     * this object stays constructible without an app instance, and the default
     * is the fail-closed one (ask).
     */
    var autoAllowProvider: () -> Boolean = { false }

    private val lock = Any()

    @Volatile
    private var resolution: Resolution? = null

    @Volatile
    private var latch: CountDownLatch? = null

    /** Attach (or replace) the surface that answers confirmations. */
    fun attach(surface: Surface) {
        this.surface = surface
        XLog.e(TAG, "[mcpConfirm] surface attached: ${surface.javaClass.simpleName}")
    }

    /**
     * Detach [surface] if it is still the current one.
     *
     * A pending wait is cancelled rather than left to the timeout: the screen
     * that was going to answer is gone, so the honest outcome is a refusal now.
     */
    fun detach(surface: Surface) {
        if (this.surface !== surface) return
        this.surface = null
        XLog.e(TAG, "[mcpConfirm] surface detached: ${surface.javaClass.simpleName}")
        cancelPending()
    }

    /**
     * Ask the user, and block until they answer or [timeoutMs] elapses.
     *
     * Returns a verdict either way; the caller refuses the tool when
     * [McpConfirmationGate.Verdict.allowed] is false. Never throws — a failure
     * to ask must fail closed, not take the task down.
     */
    fun awaitDecision(
        request: McpConfirmationRequest,
        timeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
    ): McpConfirmationGate.Verdict {
        // 自动允许：用户显式关掉了逐次确认。在任何状态判断之前生效 ——
        // 后台、无界面、忙碌都不该让一个「不再询问」的策略突然变成拒绝。
        if (autoAllowProvider()) {
            XLog.e(TAG, "[mcpConfirm] auto-allow on: '${request.toolName}' runs without asking")
            return verdictFor(McpConfirmationGate.Outcome.AUTO_ALLOWED, request, timeoutMs)
        }

        val current = surface
        val outcome = try {
            gate.request(
                request = request,
                surfaceAttached = current != null,
                surfaceVisible = current?.isForeground == true,
            )
        } catch (e: Exception) {
            XLog.e(TAG, "[mcpConfirm] gate threw for '${request.toolName}': ${e.message}", e)
            return verdictFor(McpConfirmationGate.Outcome.DENY_CANCELLED, request, timeoutMs)
        }

        if (outcome != McpConfirmationGate.Outcome.ASK) {
            XLog.e(TAG, "[mcpConfirm] no wait for '${request.toolName}': $outcome")
            return verdictFor(outcome, request, timeoutMs)
        }

        val waiting = gate.pendingRequest() ?: request
        val done = CountDownLatch(1)
        synchronized(lock) {
            resolution = null
            latch = done
        }

        XLog.e(
            TAG,
            "[mcpConfirm] asking for '${request.toolName}' " +
                "(server=${request.serverName}, timeout=${timeoutMs}ms)"
        )
        try {
            current?.showConfirmation(waiting)
        } catch (e: Exception) {
            // A surface that cannot draw the card cannot collect an answer.
            XLog.e(TAG, "[mcpConfirm] show failed for '${request.toolName}': ${e.message}", e)
            release(Resolution(waiting, McpConfirmationGate.Outcome.DENY_CANCELLED))
            return finish(current, waiting, timeoutMs, answered = true)
        }

        val answered = try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return finish(current, waiting, timeoutMs, answered)
    }

    /**
     * The user answered. Safe to call from any thread, and safe to call when
     * nothing is waiting — a stale tap (a card whose wait already ended) is
     * logged and dropped rather than answering a later request.
     */
    fun submit(decision: McpConfirmationGate.Decision) {
        val request = gate.pendingRequest()
        if (request == null) {
            XLog.e(TAG, "[mcpConfirm] submit($decision) ignored — nothing is waiting")
            return
        }
        val outcome = try {
            gate.resolve(decision)
        } catch (e: Exception) {
            XLog.e(TAG, "[mcpConfirm] resolve threw: ${e.message}", e)
            null
        }
        if (outcome == null) {
            XLog.e(TAG, "[mcpConfirm] submit($decision) lost the request — dropped")
            return
        }
        release(Resolution(request, outcome))
        XLog.e(TAG, "[mcpConfirm] submit($decision) for '${request.toolName}'")
    }

    /** Refuse whatever is waiting now (surface gone, task cancelled). */
    fun cancelPending() {
        val request = gate.pendingRequest() ?: return
        val outcome = try {
            gate.cancel()
        } catch (e: Exception) {
            XLog.e(TAG, "[mcpConfirm] cancel threw: ${e.message}", e)
            null
        }
        if (outcome == null) {
            XLog.e(TAG, "[mcpConfirm] cancel found nothing waiting")
            return
        }
        release(Resolution(request, outcome))
        XLog.e(TAG, "[mcpConfirm] pending request for '${request.toolName}' cancelled")
    }

    /** True when a confirmation is on screen right now. */
    fun isAwaiting(): Boolean = gate.pendingRequest() != null

    /**
     * Record the outcome and wake the parked thread.
     *
     * First writer wins: a second release for the same wait finds the latch
     * already cleared and only overwrites nothing, because [latch] is nulled
     * under [lock] together with the resolution.
     */
    private fun release(resolution: Resolution) {
        val toRelease = synchronized(lock) {
            if (this.resolution != null) {
                null
            } else {
                this.resolution = resolution
                latch.also { latch = null }
            }
        }
        toRelease?.countDown()
    }

    /**
     * Turn a finished wait into a verdict, hide the card, and return.
     *
     * [answered] only decides whether the timeout path runs: the recorded
     * resolution always wins, so a tap racing the deadline is reported as the
     * user's answer.
     */
    private fun finish(
        surface: Surface?,
        request: McpConfirmationRequest,
        timeoutMs: Long,
        answered: Boolean,
    ): McpConfirmationGate.Verdict {
        val recorded = synchronized(lock) {
            resolution.also { resolution = null }
        }
        val outcome = recorded?.takeIf { it.request.toolName == request.toolName }?.outcome
            ?: if (answered) {
                // Released without a recorded outcome (cancel raced us): refuse.
                McpConfirmationGate.Outcome.DENY_CANCELLED
            } else {
                gate.timeout() ?: McpConfirmationGate.Outcome.DENY_TIMED_OUT
            }
        val verdict = verdictFor(outcome, request, timeoutMs)
        try {
            surface?.hideConfirmation(verdict)
        } catch (e: Exception) {
            XLog.e(TAG, "[mcpConfirm] hide failed for '${request.toolName}': ${e.message}", e)
        }
        XLog.e(TAG, "[mcpConfirm] '${request.toolName}' -> $outcome")
        return verdict
    }

    private fun verdictFor(
        outcome: McpConfirmationGate.Outcome,
        request: McpConfirmationRequest,
        timeoutMs: Long,
    ): McpConfirmationGate.Verdict = McpConfirmationGate.Verdict(
        allowed = McpConfirmationGate.allows(outcome),
        outcome = outcome,
        reason = if (McpConfirmationGate.allows(outcome)) {
            ""
        } else {
            McpConfirmationGate.denialReason(outcome, request, timeoutMs)
        },
        request = request,
    )
}
