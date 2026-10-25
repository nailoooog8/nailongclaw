// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.langchain.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Covers the F8 masking rule.
 *
 * The bug this replaces: {@code Authorization} was masked in the curl string,
 * but the "--- Headers ---" block written to disk kept every header verbatim, so
 * the full LLM API key reached {@code http_logs/} and the shareable debug report
 * ZIP. One function, used by every writer, is the fix — these tests are what
 * keep the writers from drifting apart again.
 */
public class HeaderMaskTest {

    @Test
    public void credentialHeadersAreMasked() {
        String[] names = {
            "Authorization", "authorization", "X-Api-Key", "api-key",
            "X-Goog-Api-Key", "Cookie", "Proxy-Authorization", "X-Auth-Token"
        };
        for (String name : names) {
            String masked = HeaderMask.mask(name, "sk-super-secret-value-123456");
            assertFalse(name + " must not be written in full", masked.contains("secret-value"));
            assertTrue(name + " should keep a short prefix", masked.startsWith("sk-sup..."));
        }
    }

    @Test
    public void ordinaryHeadersPassThroughUntouched() {
        assertEquals("application/json", HeaderMask.mask("Content-Type", "application/json"));
        assertEquals("gzip", HeaderMask.mask("Accept-Encoding", "gzip"));
    }

    @Test
    public void aShortCredentialIsMaskedEntirely() {
        // No usable prefix to keep, so nothing is kept.
        assertEquals("********", HeaderMask.mask("Authorization", "abc"));
    }

    @Test
    public void nullValueIsNotACrash() {
        assertEquals("", HeaderMask.mask("Authorization", null));
    }

    @Test
    public void scrubTextRemovesProviderKeyShapesWhereverTheyAppear() {
        String log = "url=https://api.example.com\nAuthorization: Bearer sk-abcdef1234567890\n"
                + "body={\"key\":\"tvly-AAAABBBBCCCCDDDD\"}\n";
        String scrubbed = HeaderMask.scrubText(log);
        assertFalse(scrubbed.contains("sk-abcdef1234567890"));
        assertFalse(scrubbed.contains("tvly-AAAABBBBCCCCDDDD"));
        // The surrounding text a triager needs survives.
        assertTrue(scrubbed.contains("api.example.com"));
        assertTrue(scrubbed.contains("Bearer ********"));
    }

    @Test
    public void scrubTextLeavesOrdinaryTextAlone() {
        String text = "round 3 ok, tokens=1234, no credentials here";
        assertEquals(text, HeaderMask.scrubText(text));
    }

    @Test
    public void scrubTextHandlesEmptyAndNull() {
        assertEquals("", HeaderMask.scrubText(""));
        assertNull(HeaderMask.scrubText(null));
    }
}
