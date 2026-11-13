// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signals that used to be fed broken data from DefaultAgentService:
 * ZeroDiff received the total screen line count (never zero on a non-empty
 * screen) and RepeatedError received a hardwired null. These pin the fixed
 * contract the task loop now feeds.
 *
 * Signal precedence (StuckDetector checks in order): SameAction →
 * ScreenUnchanged → ZeroDiff → HighRepetition → RepeatedError. Isolating one
 * signal therefore means varying everything the earlier checks look at.
 */
class StuckDetectorTest {

    @Test
    fun `zero diff three times in a row is stuck`() {
        val detector = StuckDetector()
        // Different actions and screen hashes each round: only the diff is
        // constant at zero.
        assertNull(detector.record("tap", 1, 0, null))
        assertNull(detector.record("swipe", 2, 0, null))
        val detection = detector.record("scroll", 3, 0, null)
        assertNotNull(detection)
        assertTrue(detection!!.signal is StuckDetector.Signal.ZeroDiff)
    }

    @Test
    fun `a real diff breaks the zero-diff signal`() {
        val detector = StuckDetector()
        detector.record("tap", 1, 0, null)
        detector.record("swipe", 2, 0, null)
        // Screen actually moved: 3 lines changed. This is what the task loop
        // now passes (added + removed) instead of the total line count.
        assertNull(detector.record("scroll", 3, 3, null))
    }

    @Test
    fun `same error three times in a row is stuck`() {
        val detector = StuckDetector()
        assertNull(detector.record("tap", 1, 5, "Node not found"))
        assertNull(detector.record("swipe", 2, 5, "Node not found"))
        val detection = detector.record("scroll", 3, 5, "Node not found")
        assertNotNull(detection)
        assertTrue(detection!!.signal is StuckDetector.Signal.RepeatedError)
    }

    @Test
    fun `a success between errors breaks the repeated-error signal`() {
        val detector = StuckDetector()
        detector.record("tap", 1, 5, "Node not found")
        detector.record("swipe", 2, 5, "Node not found")
        assertNull(detector.record("scroll", 3, 5, null)) // error == null clears the window
    }

    @Test
    fun `same screen hash three times is stuck`() {
        val detector = StuckDetector()
        // Different actions each round: only the screen is unchanged.
        detector.record("tap", 42, 5, null)
        detector.record("swipe", 42, 5, null)
        val detection = detector.record("scroll", 42, 5, null)
        assertNotNull(detection)
        assertTrue(detection!!.signal is StuckDetector.Signal.ScreenUnchanged)
    }

    @Test
    fun `escalates to auto-kill after five stuck steps`() {
        val detector = StuckDetector()
        var last = detector.record("tap", 42, 0, null)
        repeat(6) {
            last = detector.record("tap", 42, 0, null)
        }
        assertNotNull(last)
        assertEquals(StuckDetector.RecoveryLevel.AUTO_KILL, last!!.level)
    }
}
