// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent

import java.util.LinkedList

/**
 * One round's fingerprint: the screen state (hash of the screen text, or of
 * the shadow content version for shadow actions) plus the tool calls made.
 * Two equal fingerprints mean "the same calls on the same screen".
 */
data class RoundFingerprint(val screenHash: Int, val toolCall: String)

/**
 * Sliding window of round fingerprints carrying the dead-loop predicate.
 *
 * Extracted from DefaultAgentService, where the same logic existed as a
 * private `isStuckInLoop` that was never called — the hard circuit breaker
 * was dead code. One fingerprint is added per tool call, so a window of
 * [windowSize] identical fingerprints means the same call on the same screen
 * repeated that many times in a row: stop the task instead of letting the
 * model (and the user's tokens) grind on.
 */
class LoopHistory(private val windowSize: Int = DEFAULT_WINDOW) {

    private val history = LinkedList<RoundFingerprint>()

    /** Fingerprints currently in the window. */
    val size: Int
        get() = history.size

    fun add(fingerprint: RoundFingerprint) {
        history.addLast(fingerprint)
        if (history.size > windowSize) {
            history.removeFirst()
        }
    }

    /**
     * True once the whole window is one repeated fingerprint. A window that
     * has not filled yet is never "stuck" — the first rounds of a task
     * legitimately look alike.
     */
    fun isStuckInLoop(): Boolean {
        if (history.size < windowSize) return false
        val first = history.first()
        return history.all { it == first }
    }

    /** Current window contents, oldest first (for logging/tests). */
    fun snapshot(): List<RoundFingerprint> = history.toList()

    companion object {
        /** Four identical fingerprints in a row is a dead loop, not a plan. */
        const val DEFAULT_WINDOW = 4
    }
}
