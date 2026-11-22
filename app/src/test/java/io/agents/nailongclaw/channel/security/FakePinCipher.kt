// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.channel.security

/**
 * Reversible stand-in for [ChannelSecurityGuard.PinCipher]: the production
 * cipher routes through SecretBox, whose Android Keystore does not exist on
 * the JVM, so every test that touches the PIN swaps this in.
 */
class FakePinCipher : ChannelSecurityGuard.PinCipher {
    private val vault = mutableMapOf<String, String>()
    override fun isEncrypted(stored: String): Boolean = stored.startsWith("enc:fake:")
    override fun encrypt(plain: String): String? {
        val token = "enc:fake:${vault.size}"
        vault[token] = plain
        return token
    }
    override fun decrypt(stored: String): String? = vault[stored]
}
