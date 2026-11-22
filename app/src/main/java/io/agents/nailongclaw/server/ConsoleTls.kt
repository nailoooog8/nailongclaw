// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.server

import io.agents.nailongclaw.utils.XLog
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * The LAN console's self-signed TLS material (BACKLOG F5).
 *
 * The console carries real channel bot tokens and the LLM API key in its POST
 * bodies, plus the `X-Config-PIN` header on every request. Over cleartext HTTP
 * a passive same-Wi-Fi observer harvests all of it. Serving the console over
 * TLS is the fix; the certificate must be generated **on the device** — a
 * keystore shipped in the APK would give every install the same private key,
 * which is worthless against anyone who has the APK.
 *
 * Trust model: self-signed means the browser warns on first visit and the user
 * accepts through the exception flow (TOFU). The pinning half is the SHA-256
 * fingerprint displayed on the phone's own screen (channel config page) — the
 * phone is the origin of trust, never the served page, so an active MITM
 * cannot show a matching fingerprint there. The certificate is intentionally
 * stable for 10 years so users never re-verify.
 *
 * The PKCS12 keystore needs a non-empty password on Android's implementation
 * (an empty/null store writes a 0-byte file that can never load again), so a
 * random one is generated once and persisted next to the keystore — both live
 * in the app's private directory, protected by the same OS sandbox that
 * protects MMKV and the channel tokens themselves. The password guards the
 * file structure, not the wire; TLS is the wire's protection.
 *
 * A corrupt or empty keystore file (partial write, sandbox restore) self-heals:
 * it is regenerated, which invalidates previously-trusted browsers — the user
 * re-accepts the warning and compares the new fingerprint.
 *
 * JVM-testable by construction: everything takes a [File] directory, no
 * android.* references.
 */
internal object ConsoleTls {

    private const val TAG = "ConsoleTls"
    private const val FILE_NAME = "console_tls.p12"
    private const val PASSWORD_FILE_NAME = "console_tls.pwd"
    private const val ALIAS = "console"
    private const val CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000
    private const val DISTINGUISHED_NAME = "CN=NailongClaw LAN Console,O=NailongClaw,C=CN"

    class Material(
        val keyStore: KeyStore,
        /** TLS-ready server socket factory — what NanoHTTPD.makeSecure consumes. */
        val serverSocketFactory: SSLServerSocketFactory,
        /** 64-char hex SHA-256 of the DER-encoded certificate, shown on the phone screen. */
        val fingerprintSha256: String,
        /** True when this call generated the keystore (first console start, or self-heal). */
        val createdNow: Boolean,
    )

    // Keyed by directory so JVM tests can point different cases at different
    // temp dirs without poisoning each other through the process-global cache.
    private val cache = mutableMapOf<String, Material>()

    /** Loads — or on first use generates — the console keystore under [dir]. */
    @Synchronized
    fun ensure(dir: File): Material {
        val key = dir.absolutePath
        // A cache hit re-materializes with createdNow=false: that flag means
        // "this call generated the keystore", true only on the generating call
        // (the log line and tests key off it — it must not persist in the cache).
        cache[key]?.let { return Material(it.keyStore, it.serverSocketFactory, it.fingerprintSha256, createdNow = false) }
        val file = File(dir, FILE_NAME)
        val material = if (file.exists()) {
            try {
                load(dir, file)
            } catch (e: Exception) {
                // Partial write or sandbox restore left an unloadable file —
                // regenerate rather than failing every console start forever.
                XLog.w(TAG, "Console keystore unloadable, regenerating: ${e.message}")
                file.delete()
                generate(file, dir)
            }
        } else {
            generate(file, dir)
        }
        cache[key] = material
        if (material.createdNow) {
            XLog.i(TAG, "Generated self-signed console certificate sha256=${material.fingerprintSha256}")
        }
        return material
    }

    private fun passwordFor(dir: File, file: File): CharArray {
        val pwdFile = File(dir, PASSWORD_FILE_NAME)
        if (pwdFile.exists()) return pwdFile.readText().trim().toCharArray()
        // Alphabet-only so the PKCS12 password never trips charset quirks.
        val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val pwd = buildString {
            val rng = SecureRandom()
            repeat(32) { append(alphabet[rng.nextInt(alphabet.length)]) }
        }
        dir.mkdirs()
        pwdFile.writeText(pwd)
        return pwd.toCharArray()
    }

    private fun load(dir: File, file: File): Material {
        val password = passwordFor(dir, file)
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { keyStore.load(it, password) }
        val cert = keyStore.getCertificate(ALIAS) as X509Certificate
        cert.verify(cert.publicKey)
        return material(dir, file, keyStore, password, createdNow = false)
    }

    private fun generate(file: File, dir: File): Material {
        val password = passwordFor(dir, file)
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name(DISTINGUISHED_NAME)
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(63, SecureRandom()),
            Date(now),
            Date(now + CERT_VALIDITY_MS),
            subject,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        ).addExtension(
            Extension.subjectAlternativeName, false,
            GeneralNames(GeneralName(GeneralName.dNSName, "nailongclaw.local"))
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign)
            )
            .addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
            )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, password, arrayOf(cert))
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { keyStore.store(it, password) }

        return material(dir, file, keyStore, password, createdNow = true)
    }

    private fun material(dir: File, file: File, keyStore: KeyStore, password: CharArray, createdNow: Boolean): Material {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)
        val cert = keyStore.getCertificate(ALIAS) as X509Certificate
        return Material(
            keyStore = keyStore,
            serverSocketFactory = sslContext.serverSocketFactory,
            fingerprintSha256 = fingerprintOf(cert),
            createdNow = createdNow,
        )
    }

    private fun fingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
}
