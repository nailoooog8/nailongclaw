// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopHistoryTest {

    @Test
    fun `partial window is never stuck`() {
        val history = LoopHistory(windowSize = 4)
        history.add(RoundFingerprint(1, "tap"))
        history.add(RoundFingerprint(1, "tap"))
        history.add(RoundFingerprint(1, "tap"))
        assertFalse(history.isStuckInLoop())
    }

    @Test
    fun `full window of identical fingerprints is a dead loop`() {
        val history = LoopHistory(windowSize = 4)
        repeat(4) { history.add(RoundFingerprint(7, "tap:x=100")) }
        assertTrue(history.isStuckInLoop())
        assertEquals(4, history.size)
    }

    @Test
    fun `same call on a different screen is not a loop`() {
        val history = LoopHistory(windowSize = 4)
        history.add(RoundFingerprint(1, "tap:x=100"))
        history.add(RoundFingerprint(1, "tap:x=100"))
        history.add(RoundFingerprint(1, "tap:x=100"))
        history.add(RoundFingerprint(2, "tap:x=100")) // screen moved
        assertFalse(history.isStuckInLoop())
    }

    @Test
    fun `same screen with a different call is not a loop`() {
        val history = LoopHistory(windowSize = 4)
        history.add(RoundFingerprint(1, "tap:x=100"))
        history.add(RoundFingerprint(1, "tap:x=100"))
        history.add(RoundFingerprint(1, "tap:x=100"))
        history.add(RoundFingerprint(1, "swipe:x=100")) // different action
        assertFalse(history.isStuckInLoop())
    }

    @Test
    fun `window slides past the loop`() {
        val history = LoopHistory(windowSize = 4)
        repeat(4) { history.add(RoundFingerprint(7, "tap")) }
        assertTrue(history.isStuckInLoop())
        history.add(RoundFingerprint(9, "swipe"))
        assertFalse(history.isStuckInLoop())
        assertEquals(4, history.size)
    }

    @Test
    fun `default window is four`() {
        val history = LoopHistory()
        repeat(3) { history.add(RoundFingerprint(7, "tap")) }
        assertFalse(history.isStuckInLoop())
        history.add(RoundFingerprint(7, "tap"))
        assertTrue(history.isStuckInLoop())
    }
}
