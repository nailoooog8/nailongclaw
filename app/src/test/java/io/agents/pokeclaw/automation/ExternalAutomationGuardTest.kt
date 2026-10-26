// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.automation

import io.agents.pokeclaw.utils.BruteForceGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The pairing-token gate on PokeClaw's exported automation entry points.
 *
 * Storage is swapped for a map: MMKV needs a Context, and the property under
 * test is the decision logic plus the fail-closed contract, not AES. What the
 * real storage layer does with the token is covered by
 * `KVUtils`/`SecretBox` behaviour and the F6 note in the R3 audit — here the
 * seam is driven to fail so the guard's half of that contract is pinned.
 */
class ExternalAutomationGuardTest {

    private val stored = mutableMapOf<String, String>()
    private var writeShouldFail = false

    // Captured, not reconstructed: the defaults close over KVUtils, which
    // cannot be touched from a plain JVM test.
    private val originalRead = ExternalAutomationGuard.readToken
    private val originalWrite = ExternalAutomationGuard.writeToken

    /** Advanced past BruteForceGuard's 30-minute TTL and lockout ceiling. */
    private var now = 1_000_000_000_000L

    @Before
    fun setUp() {
        now += 2 * 60 * 60 * 1000L
        stored.clear()
        writeShouldFail = false
        ExternalAutomationGuard.readToken = { stored["token"] ?: "" }
        ExternalAutomationGuard.writeToken = { token ->
            if (writeShouldFail) false else {
                stored["token"] = token
                true
            }
        }
        BruteForceGuard.clock = { now }
    }

    @After
    fun tearDown() {
        ExternalAutomationGuard.readToken = originalRead
        ExternalAutomationGuard.writeToken = originalWrite
        BruteForceGuard.clock = { System.currentTimeMillis() }
    }

    private fun mint(): String = ExternalAutomationGuard.getOrCreateToken()

    private fun verify(token: String?, caller: String? = null) =
        ExternalAutomationGuard.verify(token, caller)

    // ---- The gate ----

    @Test
    fun `the right token is allowed`() {
        val token = mint()
        assertTrue(verify(token) is ExternalAutomationGuard.Decision.Allow)
    }

    @Test
    fun `a request with no token is refused, not waved through`() {
        mint()
        val decision = verify(null)
        assertTrue(decision is ExternalAutomationGuard.Decision.Reject)
        assertEquals("MISSING_TOKEN", (decision as ExternalAutomationGuard.Decision.Reject).reason)
        assertTrue(decision.message.contains("token"))
    }

    @Test
    fun `a wrong token is refused`() {
        val token = mint()
        val decision = verify(token.dropLast(4) + "XXXX")
        assertTrue(decision is ExternalAutomationGuard.Decision.Reject)
        assertEquals("INVALID_TOKEN", (decision as ExternalAutomationGuard.Decision.Reject).reason)
    }

    @Test
    fun `an empty string is not a token`() {
        mint()
        // Guards the comparison against "" matching a stored "" — the
        // no-token-minted case below covers that, this covers a caller
        // that sends the extra with no value.
        val decision = verify("")
        assertTrue(decision is ExternalAutomationGuard.Decision.Reject)
    }

    // ---- Fail closed ----

    @Test
    fun `no token minted means every request is refused`() {
        // Nothing has ever been stored, so nothing can match one. This must not
        // degrade into "nothing stored, nothing required".
        val decision = verify("anything")
        assertTrue(decision is ExternalAutomationGuard.Decision.Reject)
        assertEquals("NO_TOKEN", (decision as ExternalAutomationGuard.Decision.Reject).reason)
    }

    @Test
    fun `a keystore that refuses to encrypt yields no token rather than a readable one`() {
        // F6's contract, from the guard's side: encryption unavailable must
        // produce "no token", never a token written in the clear.
        writeShouldFail = true
        assertEquals("", mint())
        assertTrue(stored.isEmpty())

        val decision = verify("")
        assertTrue(decision is ExternalAutomationGuard.Decision.Reject)
        assertEquals("NO_TOKEN", (decision as ExternalAutomationGuard.Decision.Reject).reason)
    }

    // ---- Lockout ----

    @Test
    fun `repeated wrong tokens end in a lockout that a correct token cannot ride out`() {
        val token = mint()
        val wrong = token.dropLast(4) + "XXXX"

        // MAX_FAILURES is 5; the 5th wrong attempt starts the backoff.
        repeat(4) { assertEquals("INVALID_TOKEN", (verify(wrong) as ExternalAutomationGuard.Decision.Reject).reason) }
        assertEquals("LOCKED_OUT", (verify(wrong) as ExternalAutomationGuard.Decision.Reject).reason)

        // The correct token is not exempt while the pause is running — this is
        // what stops an attacker from finishing a brute force after the fact.
        val paused = verify(token)
        assertTrue(paused is ExternalAutomationGuard.Decision.Reject)
        assertEquals("LOCKED_OUT", (paused as ExternalAutomationGuard.Decision.Reject).reason)
        assertTrue(paused.message.contains("correct token is not exempt"))
    }

    @Test
    fun `the pause expires and the correct token works again`() {
        val token = mint()
        val wrong = token.dropLast(4) + "XXXX"
        repeat(5) { verify(wrong) }
        assertTrue(verify(token) is ExternalAutomationGuard.Decision.Reject)

        now += 31 * 60 * 1000L // past the 30s base backoff
        assertTrue(verify(token) is ExternalAutomationGuard.Decision.Allow)
    }

    @Test
    fun `a caller that identifies itself is locked out on its own, not everyone`() {
        // The lockout is keyed by the caller-declared return_package, so one
        // app's failures must not pause a different app's working macro.
        val token = mint()
        val wrong = token.dropLast(4) + "XXXX"
        repeat(5) { verify(wrong, caller = "com.attacker") }
        assertTrue(verify(wrong, caller = "com.attacker") is ExternalAutomationGuard.Decision.Reject)

        assertTrue(verify(token, caller = "com.macrodroid") is ExternalAutomationGuard.Decision.Allow)
    }

    @Test
    fun `a request with no token at all still counts against the caller`() {
        // An attacker probing without a token gets no free unlimited tries.
        mint()
        repeat(5) { verify(null, caller = "com.attacker") }
        val paused = verify(null, caller = "com.attacker")
        assertEquals("LOCKED_OUT", (paused as ExternalAutomationGuard.Decision.Reject).reason)
    }

    // ---- Token lifecycle ----

    @Test
    fun `the token is stable across reads and minted only once`() {
        val first = mint()
        assertEquals(first, mint())
        assertEquals(first, ExternalAutomationGuard.getOrCreateToken())
    }

    @Test
    fun `reset replaces the token and invalidates the old one`() {
        val old = mint()
        val new = ExternalAutomationGuard.resetToken()
        assertNotEquals(old, new)
        assertTrue(verify(old) is ExternalAutomationGuard.Decision.Reject)
        assertTrue(verify(new) is ExternalAutomationGuard.Decision.Allow)
    }

    // ---- Token format ----

    @Test
    fun `the token is fixed length and free of characters a human mistypes`() {
        // Fixed length because BruteForceGuard's constant-time compare does
        // not hide it; no I/L/O/U because the token is copied by hand into
        // another app and 0/O and 1/l cost an afternoon of support.
        val token = mint()
        assertEquals(32, token.length)
        assertTrue(token.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
        assertFalse(token.any { it in "ILOU" })
    }

    @Test
    fun `two tokens are not the same string`() {
        // 160 bits of Crockford base32; a collision here would mean the
        // generator is broken, not unlucky.
        assertNotEquals(mint(), ExternalAutomationGuard.resetToken())
    }
}
