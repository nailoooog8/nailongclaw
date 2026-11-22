// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.automation

import io.agents.nailongclaw.utils.BruteForceGuard
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
    fun `repeated wrong tokens end in a lockout, but a correct token is never locked out`() {
        val token = mint()
        val wrong = token.dropLast(4) + "XXXX"

        // MAX_FAILURES is 5; the 5th wrong attempt starts the backoff.
        repeat(4) { assertEquals("INVALID_TOKEN", (verify(wrong) as ExternalAutomationGuard.Decision.Reject).reason) }
        assertEquals("LOCKED_OUT", (verify(wrong) as ExternalAutomationGuard.Decision.Reject).reason)

        // R5 §5 P3 fix: guessing is throttled (the pause starts and wrong
        // tokens keep failing), but a CORRECT token rides through — the old
        // "correct token is not exempt" policy let any hostile app poison the
        // shared unattributed bucket and lock legit automation out entirely.
        assertTrue("correct token must be exempt from the pause", verify(token) is ExternalAutomationGuard.Decision.Allow)
        // The exempt success reset the bucket (BruteForceGuard semantics: a
        // genuine success clears the failure count) — a fresh wrong token is a
        // plain invalid again, counting toward a new pause.
        assertEquals("INVALID_TOKEN", (verify(wrong) as ExternalAutomationGuard.Decision.Reject).reason)
    }

    @Test
    fun `an empty presented token is refused but does not count toward the lockout`() {
        val token = mint()
        // Unlimited empty-token probes must NOT lock the bucket: they can never
        // be correct, so counting them only enables the shared-bucket DoS.
        repeat(10) {
            val d = verify(null)
            assertEquals("MISSING_TOKEN", (d as ExternalAutomationGuard.Decision.Reject).reason)
        }
        assertEquals("still unlocked", "INVALID_TOKEN", (verify(token.dropLast(4) + "XXXX") as ExternalAutomationGuard.Decision.Reject).reason)
    }

    @Test
    fun `the pause expires and wrong-token guessing resumes being throttled`() {
        val token = mint()
        val wrong = token.dropLast(4) + "XXXX"
        repeat(5) { verify(wrong) }
        // While paused: wrong refused; correct exempt (R5 §5 P3 fix).
        assertEquals("LOCKED_OUT", (verify(wrong) as ExternalAutomationGuard.Decision.Reject).reason)
        assertTrue(verify(token) is ExternalAutomationGuard.Decision.Allow)

        now += 31 * 60 * 1000L // past the 30s base backoff
        assertEquals("after expiry the first wrong guess is a plain INVALID again",
            "INVALID_TOKEN", (verify(wrong) as ExternalAutomationGuard.Decision.Reject).reason)
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
