// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Host-header rules that stand between a DNS-rebinding page in a
 * browser and the LAN config API (which reads and writes every bot token and
 * LLM key on the device).
 *
 * The check is offline on purpose: the previous version called
 * `InetAddress.getByName`, which resolves names — the one thing a rebinding
 * attacker controls.
 */
class HostPolicyTest {

    @Test
    fun `a missing Host header is refused`() {
        // F9: fail-open here let a hand-built request skip the only check in
        // front of the credential API. HTTP/1.1 requires the header, so the
        // requests that lack it are exactly the ones worth refusing.
        assertFalse(HostPolicy.isValidHost(null))
        assertFalse(HostPolicy.isValidHost(""))
        assertFalse(HostPolicy.isValidHost("   "))
    }

    @Test
    fun `loopback hosts are accepted`() {
        for (host in listOf("localhost", "localhost:9527", "127.0.0.1", "127.0.0.1:9527", "::1", "[::1]:9527")) {
            assertTrue("$host should be accepted", HostPolicy.isValidHost(host))
        }
    }

    @Test
    fun `LAN addresses are accepted`() {
        for (host in listOf(
            "192.168.1.42", "192.168.1.42:9527", "10.0.2.5", "172.16.4.9",
            "172.31.255.254", "169.254.1.1", "fd00::1"
        )) {
            assertTrue("$host should be accepted", HostPolicy.isValidHost(host))
        }
    }

    @Test
    fun `domain names are refused`() {
        // This is the rebinding lever: the attacker owns DNS for the name.
        for (host in listOf(
            "evil.example.com", "evil.example.com:9527", "attacker.test",
            "127.0.0.1.evil.com", "localhost.evil.com", "0x7f.0.0.1", "2130706433"
        )) {
            assertFalse("$host must be refused", HostPolicy.isValidHost(host))
        }
    }

    @Test
    fun `public addresses are refused`() {
        for (host in listOf("8.8.8.8", "1.1.1.1:9527", "172.32.0.1", "11.0.0.1", "192.169.0.1")) {
            assertFalse("$host must be refused", HostPolicy.isValidHost(host))
        }
    }

    @Test
    fun `malformed literals are refused`() {
        for (host in listOf("192.168.1", "192.168.1.999", ":::", "1.2.3.4.5")) {
            assertFalse("$host must be refused", HostPolicy.isValidHost(host))
        }
    }

    @Test
    fun `ip literal detection does not resolve names`() {
        // A name that *looks* hex-ish is still a name.
        assertFalse(HostPolicy.isIpLiteral("deadbeef"))
        assertFalse(HostPolicy.isIpLiteral("abc.def"))
        assertTrue(HostPolicy.isIpLiteral("10.0.0.1"))
        assertTrue(HostPolicy.isIpLiteral("fd00::1"))
    }
}
