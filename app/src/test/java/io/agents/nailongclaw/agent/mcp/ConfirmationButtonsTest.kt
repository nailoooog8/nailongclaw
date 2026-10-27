// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.mcp

import io.agents.nailongclaw.tool.ToolRiskPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card-vs-gate contract, pinned: a button the gate would silently
 * downgrade must never be drawn. The destructive tier loses 始终允许 on
 * the card *and* in the gate, from one rule, so the two cannot drift —
 * and the cross-class test below fails loudly if someone changes one
 * side without the other (the H62 failure mode).
 */
class ConfirmationButtonsTest {

    @Test
    fun `a destructive request never offers the remembered yes`() {
        val buttons = ConfirmationButtons.forRisk(ToolRiskPolicy.Risk.DESTRUCTIVE)
        assertFalse(buttons.offersRememberedYes)
    }

    @Test
    fun `read-only and write requests keep the remembered yes`() {
        assertTrue(ConfirmationButtons.forRisk(ToolRiskPolicy.Risk.READ_ONLY).offersRememberedYes)
        assertTrue(ConfirmationButtons.forRisk(ToolRiskPolicy.Risk.WRITE).offersRememberedYes)
    }

    @Test
    fun `the card's buttons match what the gate will honour`() {
        // 跨类契约：卡面给的每个出口，gate 都必须兑现。破坏性档若卡面还给
        // 「始终允许」，用户点下去得到的是静默降级——按钮在说谎。
        for (risk in ToolRiskPolicy.Risk.entries) {
            val offers = ConfirmationButtons.forRisk(risk).offersRememberedYes
            val gate = McpConfirmationGate()
            val outcome = gate.request(
                McpConfirmationRequest(
                    toolName = "mcp_server_echo",
                    displayName = "echo",
                    serverName = "server",
                    description = "echoes",
                    paramsPreview = "text: hi",
                    risk = risk,
                ),
                surfaceAttached = true,
                surfaceVisible = true,
            )
            assertEquals(McpConfirmationGate.Outcome.ASK, outcome)
            val resolved = gate.resolve(McpConfirmationGate.Decision.ALLOW_ALWAYS)
            assertEquals(
                "risk=$risk offered remembered yes=$offers",
                if (offers) McpConfirmationGate.Outcome.ALLOW_ALWAYS
                else McpConfirmationGate.Outcome.ALLOW_ONCE,
                resolved,
            )
        }
    }
}
