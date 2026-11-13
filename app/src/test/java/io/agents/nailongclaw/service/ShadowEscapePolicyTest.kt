// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shadow escape policy (Mika's watchMainScreen, pull-based). The service
 * method mixes window scans and a clock; the verdict itself is a pure
 * predicate — [ClawAccessibilityService.isShadowEscape] — pinned here.
 */
class ShadowEscapePolicyTest {

    @Test
    fun `on the real screen and gone from the shadow display is an escape`() {
        assertTrue(ClawAccessibilityService.isShadowEscape(true, false))
    }

    @Test
    fun `on both screens is the user's own copy, not an escape`() {
        assertFalse(ClawAccessibilityService.isShadowEscape(true, true))
    }

    @Test
    fun `only on the shadow display is normal automation`() {
        assertFalse(ClawAccessibilityService.isShadowEscape(false, true))
    }

    @Test
    fun `nowhere is nothing to report`() {
        assertFalse(ClawAccessibilityService.isShadowEscape(false, false))
    }

    @Test
    fun `an unreadable real screen fails closed`() {
        // mainScreenUsage() returns null when the main display cannot be
        // inspected — never treat that as an escape and yank a task.
        assertFalse(ClawAccessibilityService.isShadowEscape(null, false))
    }
}
