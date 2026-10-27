// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.splash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4-R1: the exported LAUNCHER must only forward a `task` extra for adb shell
 * (QA) or a token-bearing automation app. [SplashActivity.isShellReferrer] is
 * the pure half of that gate — exercised here so a referrer-parsing regression
 * cannot silently reopen the door to every installed app.
 */
class SplashActivityTest {

    @Test
    fun `adb shell referrer is accepted`() {
        assertTrue(SplashActivity.isShellReferrer("android-app://com.android.shell"))
        assertTrue(SplashActivity.isShellReferrer("ANDROID-APP://com.android.shell"))
        assertTrue(SplashActivity.isShellReferrer("android-app://com.android.shell/extra?x=1"))
    }

    @Test
    fun `third-party or look-alike referrer is rejected`() {
        assertFalse(SplashActivity.isShellReferrer("android-app://com.evil.app"))
        assertFalse(SplashActivity.isShellReferrer("android-app://com.android.shell.evil"))
        assertFalse(SplashActivity.isShellReferrer("android-app://com.android.shellfoo"))
        assertFalse(SplashActivity.isShellReferrer("https://com.android.shell.example.com"))
    }

    @Test
    fun `null, empty, or blank referrer is rejected`() {
        assertFalse(SplashActivity.isShellReferrer(null))
        assertFalse(SplashActivity.isShellReferrer(""))
        assertFalse(SplashActivity.isShellReferrer("   "))
    }
}
