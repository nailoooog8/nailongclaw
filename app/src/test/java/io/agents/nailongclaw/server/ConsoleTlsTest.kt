// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Date

/**
 * F5 — the console's self-signed TLS material. These pins cover the parts a
 * browser or an attacker can observe: the certificate is genuinely self-signed,
 * server-auth capable, long-lived, and its fingerprint (the thing the user
 * verifies against the phone screen) is stable across reloads. The TLS
 * handshake itself is exercised on-device (emulator QA), not here.
 */
class ConsoleTlsTest {

    private val dir: File = Files.createTempDirectory("console-tls-test").toFile()

    @Test
    fun `first ensure generates, second loads the same material`() {
        val first = ConsoleTls.ensure(dir)
        assertTrue("first call must generate", first.createdNow)
        val second = ConsoleTls.ensure(dir)
        assertTrue("second call must load, not regenerate", !second.createdNow)
        assertEquals("fingerprint must be stable across reloads", first.fingerprintSha256, second.fingerprintSha256)
    }

    @Test
    fun `certificate is self-signed, server-auth, long-lived`() {
        val ks = ConsoleTls.ensure(dir).keyStore
        val cert = ks.getCertificate("console") as java.security.cert.X509Certificate
        cert.verify(cert.publicKey)
        assertTrue("RSA 2048", cert.publicKey.algorithm == "RSA" && cert.publicKey.format == "X.509")
        assertEquals("KeyUsage digitalSignature+keyCertSign", cert.keyUsage?.let { it[0] && it[5] }, true)
        assertTrue(
            "serverAuth extended key usage",
            cert.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") == true
        )
        assertTrue(
            "sanity: validity ≥ 9 years",
            cert.notAfter.time - cert.notBefore.time >= 9L * 365 * 24 * 60 * 60 * 1000
        )
        assertTrue("already valid now", cert.notBefore.before(Date()))
    }

    @Test
    fun `fingerprint is 64 hex chars of the DER encoding`() {
        val material = ConsoleTls.ensure(dir)
        assertTrue(material.fingerprintSha256.matches(Regex("[0-9a-f]{64}")))
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(material.keyStore.getCertificate("console").encoded)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, material.fingerprintSha256)
    }

    @Test
    fun `a different directory gets its own independent certificate`() {
        val other = Files.createTempDirectory("console-tls-other").toFile()
        val a = ConsoleTls.ensure(dir)
        val b = ConsoleTls.ensure(other)
        assertTrue("independent generation", b.createdNow)
        assertTrue(
            "per-device keys must differ — a shipped shared key would be worthless",
            a.fingerprintSha256 != b.fingerprintSha256
        )
    }

    @Test
    fun `an unloadable keystore file self-heals by regeneration`() {
        val healDir = Files.createTempDirectory("console-tls-heal").toFile()
        // Simulate the Android PKCS12 empty-store failure mode: a 0-byte file
        // that exists but can never load.
        File(healDir, "console_tls.p12").writeBytes(ByteArray(0))
        val material = ConsoleTls.ensure(healDir)
        assertTrue("self-heal must regenerate", material.createdNow)
        assertTrue(material.fingerprintSha256.matches(Regex("[0-9a-f]{64}")))
        // And the healed keystore loads again.
        val reloaded = ConsoleTls.ensure(healDir)
        assertEquals(material.fingerprintSha256, reloaded.fingerprintSha256)
    }

    @Test
    fun `tls handshake serves over makeSecure end to end`() {
        val material = ConsoleTls.ensure(dir)
        val server = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: fi.iki.elonen.NanoHTTPD.IHTTPSession): fi.iki.elonen.NanoHTTPD.Response =
                fi.iki.elonen.NanoHTTPD.newFixedLengthResponse("TLS-OK")
        }
        server.makeSecure(material.serverSocketFactory, null)
        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val trustAll = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, java.security.SecureRandom())
            }
            val conn = java.net.URL("https://127.0.0.1:${server.listeningPort}/")
                .openConnection() as javax.net.ssl.HttpsURLConnection
            conn.sslSocketFactory = trustAll.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            assertEquals("TLS-OK", conn.inputStream.bufferedReader().readText())
            assertTrue("negotiated a TLS cipher", conn.cipherSuite.startsWith("TLS"))
        } finally {
            server.stop()
        }
    }
}
