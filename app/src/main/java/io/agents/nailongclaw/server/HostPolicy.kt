// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.server

/**
 * Host-header policy for the LAN config server, as pure functions.
 *
 * The server binds 0.0.0.0 so it is reachable from the whole Wi-Fi, and its API
 * reads and writes every bot token and LLM key on the device. The Host header is
 * what stands between that and a DNS-rebinding page in a browser: a rebinding
 * attacker controls DNS, so a domain they own can resolve to the phone's LAN
 * address, and the browser will then happily send authenticated requests to it.
 * Only loopback and private-IP literals are accepted, so a domain name — the
 * attacker's only lever — is rejected before any DNS happens.
 *
 * Extracted from [ConfigServer] so the rules are testable without a socket.
 */
object HostPolicy {

    /** Loopback names a browser may legitimately use for the local console. */
    private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

    /**
     * True when a request carrying [host] may be served.
     *
     * A missing Host header is **refused** (F9). HTTP/1.1 requires one; the only
     * requests that arrive without it are hand-built, and "the client is
     * supposed to send it" is not a security control. Loopback peers that omit
     * it are still served — they are identified by remote address elsewhere.
     */
    fun isValidHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        return isLoopbackOrPrivateIp(stripPort(host))
    }

    /**
     * Removes the port and any IPv6 zone/bracket decoration.
     *
     * `substringBefore(':')` is wrong here: it turns `::1` into the empty string
     * and `[::1]:9527` into `[`, so both loopback forms would have been refused
     * while every IPv4 form passed.
     */
    fun stripPort(host: String): String {
        var h = host.trim()
        if (h.startsWith("[")) {
            val end = h.indexOf(']')
            return if (end > 0) h.substring(1, end) else h
        }
        // Two or more colons means the literal itself is IPv6 (possibly zoned);
        // one colon means host:port.
        if (h.count { it == ':' } >= 2) return h.substringBefore('%')
        return h.substringBefore(':')
    }

    private fun isLoopbackOrPrivateIp(host: String): Boolean {
        if (host in LOOPBACK) return true
        if (!isIpLiteral(host)) return false
        return isPrivateRange(host)
    }

    /** IPv4 dotted quad or IPv6 hex literal — anything else is a domain name. */
    fun isIpLiteral(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size == 4) {
            return parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }
        return host.contains(":") && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
    }

    /**
     * Private / loopback / link-local ranges, checked on the literal's octets.
     *
     * The previous implementation called `InetAddress.getByName`, which resolves
     * names — the one thing this check exists to avoid — and it also accepted
     * anything that resolved. Parsing the literal keeps the check offline.
     */
    fun isPrivateRange(ip: String): Boolean {
        if (!ip.contains(":")) {
            val octets = ip.split(".").mapNotNull { it.toIntOrNull() }
            if (octets.size != 4) return false
            val (a, b) = octets[0] to octets[1]
            return when (a) {
                10 -> true
                127 -> true
                169 -> b == 254                      // link-local
                172 -> b in 16..31                   // 172.16/12
                192 -> b == 168                      // 192.168/16
                100 -> b in 64..127                  // CGNAT, where some carriers put LANs
                else -> false
            }
        }
        // IPv6: accept Unique Local (fd00::/8) and link-local (fe80::/10).
        val lower = ip.lowercase().substringBefore('%')
        return lower.startsWith("fd") || lower.startsWith("fe8") || lower.startsWith("fe9") ||
            lower.startsWith("fea") || lower.startsWith("feb")
    }
}
