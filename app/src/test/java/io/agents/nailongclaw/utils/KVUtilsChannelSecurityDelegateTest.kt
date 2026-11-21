// Copyright 2026 NailongClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.utils

import io.agents.nailongclaw.channel.security.ChannelSecurityGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pairing PIN and the security switch have one writer:
 * [ChannelSecurityGuard]. [KVUtils]' channel-security methods used to hold
 * their own copy of the same MMKV keys and a second PIN generator — the two
 * halves only met through a shared `MMKV.defaultMMKV()` — and now delegate.
 *
 * These tests run the delegation on the JVM through
 * [ChannelSecurityGuard.InMemoryStorage] (the delegated paths never touch
 * KVUtils' own MMKV handle), pinning the two behaviours the unification had
 * to preserve: an already-generated PIN never changes, and the toggle's
 * default stays "enabled".
 */
class KVUtilsChannelSecurityDelegateTest {

    private lateinit var inMemoryStorage: ChannelSecurityGuard.InMemoryStorage

    @Before
    fun setUp() {
        inMemoryStorage = ChannelSecurityGuard.InMemoryStorage()
        ChannelSecurityGuard.storage = inMemoryStorage
        ChannelSecurityGuard.clearWhitelist()
        ChannelSecurityGuard.resetLegacyPurgeForTesting()
    }

    @After
    fun tearDown() {
        inMemoryStorage.clear()
        ChannelSecurityGuard.resetLegacyPurgeForTesting()
    }

    // ---- PIN: read path ----

    @Test
    fun `KVUtils reads the pin the guard stored`() {
        ChannelSecurityGuard.setPairPin("654321")
        assertEquals("654321", KVUtils.getChannelPairPin())
    }

    @Test
    fun `an already generated pin is returned unchanged`() {
        // The whole point of the unification: whatever is on disk stays there.
        inMemoryStorage.putString("KEY_CHANNEL_PAIR_PIN", "654321")
        assertEquals("654321", KVUtils.getChannelPairPin())
        assertEquals("654321", KVUtils.getChannelPairPin())
    }

    @Test
    fun `empty storage mints a six digit pin once and persists it through the guard`() {
        val first = KVUtils.getChannelPairPin()
        assertEquals(6, first.length)
        assertTrue(first.all { it.isDigit() })
        // Stable on re-read, and written into the one shared key the guard
        // also reads — no second generator, no second storage location.
        assertEquals(first, KVUtils.getChannelPairPin())
        assertEquals(first, inMemoryStorage.getString("KEY_CHANNEL_PAIR_PIN", ""))
    }

    // ---- PIN: write path ----

    @Test
    fun `KVUtils writes the pin through the guard`() {
        KVUtils.setChannelPairPin("123456")
        assertEquals("123456", ChannelSecurityGuard.getPairPin())
        assertEquals("123456", inMemoryStorage.getString("KEY_CHANNEL_PAIR_PIN", ""))
    }

    // ---- Security toggle ----

    @Test
    fun `the toggle defaults to enabled and delegates in both directions`() {
        assertTrue("default must stay enabled", KVUtils.isChannelSecurityEnabled())

        KVUtils.setChannelSecurityEnabled(false)
        assertFalse(ChannelSecurityGuard.isSecurityEnabled())

        ChannelSecurityGuard.setSecurityEnabled(true)
        assertTrue(KVUtils.isChannelSecurityEnabled())
    }
}
