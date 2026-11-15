// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shadow escape policy (Mika's watchMainScreen, pull-based). The service
 * method mixes window scans and a clock; the verdict itself is a pure
 * predicate — [ClawAccessibilityService.isShadowEscape] — pinned here, and
 * so is the package-tracking filter
 * ([ClawAccessibilityService.isTrackableTaskPackage]) that keeps the
 * watchdog pointed at the package actually being automated.
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

    @Test
    fun `an ordinary app on the shadow display is trackable`() {
        // In-shadow navigations (a link tap opening another app) re-point the
        // watchdog at the new package — it must be accepted.
        assertTrue(
            ClawAccessibilityService.isTrackableTaskPackage("com.zhiliaoapp.musically", "io.agents.nailongclaw")
        )
    }

    @Test
    fun `a launcher on the shadow display means no shadow task`() {
        // Launchers are TYPE_APPLICATION like any app, so only the name
        // filters them — otherwise an empty shadow display plus the user's
        // home screen would read as a launcher escape.
        assertFalse(ClawAccessibilityService.isTrackableTaskPackage("com.bbk.launcher2", "io.agents.nailongclaw"))
        assertFalse(
            ClawAccessibilityService.isTrackableTaskPackage("com.google.android.apps.nexuslauncher", "io.agents.nailongclaw")
        )
    }

    @Test
    fun `our own window on the shadow display is not a task`() {
        assertFalse(ClawAccessibilityService.isTrackableTaskPackage("io.agents.nailongclaw", "io.agents.nailongclaw"))
    }

    @Test
    fun `a blank package name is not trackable`() {
        assertFalse(ClawAccessibilityService.isTrackableTaskPackage(null, "io.agents.nailongclaw"))
        assertFalse(ClawAccessibilityService.isTrackableTaskPackage("", "io.agents.nailongclaw"))
    }
}
