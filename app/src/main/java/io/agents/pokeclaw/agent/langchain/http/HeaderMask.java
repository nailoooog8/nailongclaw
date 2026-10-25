// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.langchain.http;

import java.util.Locale;

/**
 * One masking rule for every place an HTTP header is written down.
 *
 * docs/SECURITY_AUDIT_R2.md F8: the curl string masked `Authorization`, but the
 * "--- Headers ---" block above it wrote every header verbatim — and that block
 * is what lands in `http_logs/` and then in the shareable debug report ZIP. A
 * debugging aid that ships the user's LLM API key is not a debugging aid.
 *
 * Keeping the rule here (rather than in the interceptor) is the point: any new
 * writer — a second interceptor, the report packager, a test — calls the same
 * function instead of remembering which headers matter.
 */
public final class HeaderMask {

    /** Header names whose value is a credential and must never be written in full. */
    private static final java.util.Set<String> SENSITIVE = java.util.Set.of(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "apikey",
            "x-goog-api-key",
            "cookie",
            "set-cookie",
            "x-auth-token",
            "x-session-token"
    );

    private static final String MASK = "********";
    private static final int KEEP_PREFIX = 6;

    private HeaderMask() {
    }

    /** True when [name] carries a credential. */
    public static boolean isSensitive(String name) {
        if (name == null) return false;
        return SENSITIVE.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns [value] safe to write to a log: masked when the header is a
     * credential, unchanged otherwise.
     *
     * A short prefix is kept (not the full 15 chars the old curl path kept) so a
     * log can still be used to tell "which key failed" without being usable as
     * the key.
     */
    public static String mask(String name, String value) {
        if (value == null) return "";
        if (!isSensitive(name)) return value;
        String trimmed = value.trim();
        if (trimmed.length() <= KEEP_PREFIX) return MASK;
        return trimmed.substring(0, KEEP_PREFIX) + "..." + MASK;
    }

    /**
     * Scans a whole log body for credential-shaped text.
     *
     * Defence in depth for logs written before this rule existed, or by code
     * that does not know about it: `Bearer sk-...`, `sk-...`, `tvly-...` and
     * similar provider key shapes are replaced wherever they appear.
     */
    public static String scrubText(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text.replaceAll(
                "(?i)\\b(Bearer|Basic|Token)\\s+[A-Za-z0-9._\\-]{8,}",
                "$1 " + MASK);
        out = out.replaceAll("\\b(sk|tvly|pk|ak)-[A-Za-z0-9_\\-]{12,}", MASK);
        return out;
    }
}
