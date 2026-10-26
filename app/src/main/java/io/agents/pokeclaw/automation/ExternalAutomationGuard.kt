// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.automation

import io.agents.pokeclaw.utils.BruteForceGuard
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.security.SecureRandom

/**
 * The pairing token that gates PokeClaw's exported external-automation entry
 * points ([ExternalAutomationReceiver] and `ExternalAutomationActivity`).
 *
 * Both are exported with no `android:permission`, so before this existed any
 * app installed on the device could start a task and drive the agent — and
 * therefore the accessibility service — with nothing to prove it was allowed
 * to. The `Channel.EXTERNAL` work (R3-01) narrowed what such a task may *do*
 * (READ_ONLY ceiling in `ToolRiskPolicy`); this narrows who may start one at
 * all. The two halves are independent and both are needed: the channel gate
 * does not help against an app that was never supposed to be here.
 *
 * ## Why the token is stored encrypted
 *
 * It goes through [KVUtils.putEncryptedString] like every other credential in
 * this app, not `putString`. A leaked token still buys the reader the
 * disclosure half of the READ_ONLY tier — screen contents, screenshots,
 * notification text — which is exactly the half the channel gate does *not*
 * cover. See F6 in `docs/SECURITY_AUDIT_R3.md` for why the encrypted path
 * fails closed rather than falling back to plaintext: a keystore that refuses
 * to encrypt must produce "no token", never "a readable token".
 *
 * ## Why the lockout is keyed the way it is
 *
 * [BruteForceGuard] wants a stable identity to count failures against, and a
 * broadcast carries none: `intent.package` is the sender's *target*
 * restriction, not the sender. So the caller-declared `return_package` is used
 * when present and a single shared key otherwise. That means a caller that
 * identifies itself consistently gets locked out, while one that rotates
 * identities does not — and, more to the point, an attacker cannot lock the
 * user's own working macro out by guessing at a different package. The lockout
 * is defence in depth, not the primary control: a 160-bit token is not
 * brute-forceable online even with unlimited attempts, and the honest cost of
 * that is a pausable convenience feature, which fails closed.
 */
object ExternalAutomationGuard {

    private const val TAG = "ExternalAutomationGuard"

    /** Extra name on RUN_TASK / RUN_CHAT carrying the token. */
    const val EXTRA_TOKEN = "token"

    /**
     * Crockford base32 — 32 symbols with I, L, O and U left out, so a token a
     * human copies into MacroDroid cannot be mistyped into a different
     * valid-looking string.
     *
     * The length is fixed for a second reason: [BruteForceGuard.constantTimeEquals]
     * does not hide length, and a secret whose length never varies has nothing
     * to leak there.
     */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TOKEN_LENGTH = 32

    private const val KEY_EXTERNAL_AUTOMATION_TOKEN = "KEY_EXTERNAL_AUTOMATION_TOKEN"

    /** Used when the caller did not name a return package to key against. */
    private const val LOCKOUT_KEY_FALLBACK = "external_automation"

    private val random = SecureRandom()

    /**
     * Storage seams, isolated so the policy can be exercised without an app
     * instance — same reason `ToolRegistry` isolates `sourceChannelProvider`.
     * MMKV needs a Context; unit tests swap these for a map.
     */
    internal var readToken: () -> String = {
        KVUtils.getEncryptedString(KEY_EXTERNAL_AUTOMATION_TOKEN)
    }
    internal var writeToken: (String) -> Boolean = { token ->
        KVUtils.putEncryptedString(KEY_EXTERNAL_AUTOMATION_TOKEN, token)
    }

    /** What [verify] concluded. */
    sealed class Decision {
        object Allow : Decision()

        /**
         * @param reason short machine-readable tag for logcat and the callback
         *   (`MISSING_TOKEN`, `INVALID_TOKEN`, `LOCKED_OUT`, `NO_TOKEN`).
         * @param message what the caller and the user are told.
         */
        data class Reject(val reason: String, val message: String) : Decision()
    }

    /**
     * The token, minted on first use. Returns "" when it cannot be stored
     * encrypted — the caller must treat that as "no token", because a token
     * that had to be written in the clear is worse than none.
     */
    @Synchronized
    fun getOrCreateToken(): String {
        val existing = readToken()
        if (existing.isNotEmpty()) return existing
        return mint()
    }

    /** Replaces the token, invalidating every caller still holding the old one. */
    @Synchronized
    fun resetToken(): String = mint()

    /**
     * Decides whether a request carrying [presented] may run.
     *
     * [callerPackage] is the request's own `return_package`, used only to key
     * the failure counter; it is caller-declared and proves nothing.
     */
    fun verify(presented: String?, callerPackage: String? = null): Decision {
        val stored = readToken()
        // No token has ever been minted, so nothing can match one. Fail closed
        // rather than treating "nothing stored" as "nothing required".
        if (stored.isEmpty()) {
            XLog.w(TAG, "Rejected external automation request: NO_TOKEN (none has been set up)")
            return Decision.Reject(
                "NO_TOKEN",
                "No pairing token has been set up in PokeClaw yet. Open " +
                    "Settings -> Remote Control -> Pairing token to create one.",
            )
        }

        val lockKey = lockoutKey(callerPackage)
        if (BruteForceGuard.isLocked(lockKey)) {
            val seconds = (BruteForceGuard.lockoutRemainingMs(lockKey) + 999) / 1000
            XLog.w(TAG, "Rejected external automation request: LOCKED_OUT (${seconds}s left)")
            return Decision.Reject(
                "LOCKED_OUT",
                "Too many failed attempts. External automation is paused for " +
                    "${seconds}s — a correct token is not exempt from the pause.",
            )
        }

        // A request that sends no token at all is still a failed attempt: an
        // attacker probing without one gets no free unlimited tries.
        if (presented.isNullOrEmpty()) {
            BruteForceGuard.onFailure(lockKey)
            XLog.w(TAG, "Rejected external automation request: MISSING_TOKEN")
            return Decision.Reject(
                "MISSING_TOKEN",
                "This request is missing the pairing token. Add a \"token\" extra " +
                    "with the token from Settings -> Remote Control -> Pairing token.",
            )
        }

        if (!BruteForceGuard.constantTimeEquals(presented, stored)) {
            val lockoutMs = BruteForceGuard.onFailure(lockKey)
            if (lockoutMs > 0) {
                XLog.w(TAG, "Rejected external automation request: LOCKED_OUT (backoff started)")
                return Decision.Reject(
                    "LOCKED_OUT",
                    "Too many failed attempts. External automation is paused for " +
                        "${(lockoutMs + 999) / 1000}s — a correct token is not exempt from the pause.",
                )
            }
            XLog.w(TAG, "Rejected external automation request: INVALID_TOKEN")
            return Decision.Reject(
                "INVALID_TOKEN",
                "The pairing token is not correct. Copy it again from " +
                    "Settings -> Remote Control -> Pairing token.",
            )
        }

        BruteForceGuard.onSuccess(lockKey)
        return Decision.Allow
    }

    private fun mint(): String {
        val token = buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
        if (!writeToken(token)) {
            XLog.e(TAG, "Refused to mint a token: encryption unavailable, nothing stored")
            return ""
        }
        XLog.i(TAG, "Minted a new external-automation pairing token")
        return token
    }

    private fun lockoutKey(callerPackage: String?): String =
        callerPackage?.takeIf { it.isNotBlank() } ?: LOCKOUT_KEY_FALLBACK
}
