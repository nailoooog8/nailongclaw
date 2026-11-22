// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.llm

import io.agents.nailongclaw.agent.AgentConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H79-a regression pins for the SDK tool-call parse-failure salvage path.
 *
 * litertlm 0.18's parser rejects Gemma 4's mirrored close marker
 * (`<|tool_call>…<tool_call|>`) and throws with the raw output embedded in the
 * error message. The old recovery chain silently turned that error string into
 * the task's answer (answerLen=83, three reproductions on 2026-11-22); the
 * salvage path must recover the model's actual tool call instead — and return
 * null (→ honest task failure) when nothing is recoverable.
 */
class LocalLlmClientToolCallSalvageTest {

    private val client = LocalLlmClient(
        AgentConfig(apiKey = "test", baseUrl = "http://localhost")
    )

    /** The exact 0.18 error shape observed on-device (toolResult hop, ×3 reproductions). */
    private val error018 = """
        Failed to call nativeSendMessage: INVALID_ARGUMENT: Failed to parse tool calls from code block: call:tap_node{node_id="n8"}
        full response: <|tool_call>call:tap_node{node_id="n8"}<tool_call|>
    """.trimIndent()

    @Test
    fun salvageRecoversTheFullMarkedOutputFromThe018TwoLineError() {
        val salvaged = client.salvageRawOutputFromParseError(error018)
        assertEquals("""<|tool_call>call:tap_node{node_id="n8"}<tool_call|>""", salvaged)
    }

    @Test
    fun salvaged018OutputParsesIntoTheRightToolCallAndArgs() {
        val salvaged = client.salvageRawOutputFromParseError(error018)!!
        val calls = client.extractToolCalls(salvaged)
        assertEquals(1, calls.size)
        assertEquals("tap_node", calls[0].name())
        assertEquals("""{"node_id":"n8"}""", calls[0].arguments())
    }

    @Test
    fun salvageNeverReturnsTheSdkErrorPrefixAsContent() {
        // The old chain produced exactly this 83-char prefix as the "answer".
        val prefix = "Failed to call nativeSendMessage: INVALID_ARGUMENT: Failed to parse tool calls from"
        val salvaged = client.salvageRawOutputFromParseError(error018)!!
        assertFalse(salvaged.startsWith(prefix))
        assertFalse(salvaged.contains("nativeSendMessage"))
        assertTrue(salvaged.contains("tap_node"))
    }

    @Test
    fun salvageHandlesThe010FromResponseShape() {
        val error010 = """Failed to parse tool calls from response: <|tool_call>call:open_app{app_name:<|"|>Camera<|"|>}<tool_call|>"""
        val salvaged = client.salvageRawOutputFromParseError(error010)!!
        val calls = client.extractToolCalls(salvaged)
        assertEquals(1, calls.size)
        assertEquals("open_app", calls[0].name())
        assertTrue(calls[0].arguments().contains("Camera"))
    }

    @Test
    fun salvageRewrapsABareCodeBlockWhenFullResponseLineIsAbsent() {
        val bare = """INVALID_ARGUMENT: Failed to parse tool calls from code block: call:finish{summary:"done"}"""
        val salvaged = client.salvageRawOutputFromParseError(bare)!!
        assertEquals("""<|tool_call>call:finish{summary:"done"}<tool_call|>""", salvaged)
        val calls = client.extractToolCalls(salvaged)
        assertEquals(1, calls.size)
        assertEquals("finish", calls[0].name())
    }

    @Test
    fun salvageReturnsNullWhenNothingRecoverable() {
        assertNull(client.salvageRawOutputFromParseError("some unrelated engine crash"))
        assertNull(client.salvageRawOutputFromParseError(""))
        // "Failed to parse tool calls" without any raw-output section: null.
        assertNull(
            client.salvageRawOutputFromParseError("INVALID_ARGUMENT: Failed to parse tool calls")
        )
    }
}
