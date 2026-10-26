// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import io.agents.pokeclaw.tool.ToolRiskPolicy

/**
 * Which exits an MCP confirmation card offers for a risk tier — the one
 * place both surfaces (the native CdsDialog and the WebView card) derive
 * their buttons from.
 *
 * ## Why this exists
 *
 * [McpConfirmationGate.resolve] already downgrades a remembered yes on a
 * DESTRUCTIVE request to a single one. Until now neither surface knew
 * that: both drew 始终允许 unconditionally, so a user tapping it got a
 * silent downgrade — the card offered an exit the gate would not honour.
 * That is the H62 failure in one sentence: a card that lies. Deriving
 * the buttons here, next to the gate, keeps card and policy in lockstep
 * by construction instead of by two people remembering to agree.
 *
 * The rule is the gate's rule: only a DESTRUCTIVE request loses the
 * remembered yes. READ_ONLY and WRITE keep it.
 */
data class ConfirmationButtons(
    /** Whether the card offers the "remember this yes" exit. */
    val offersRememberedYes: Boolean,
) {
    companion object {
        fun forRisk(risk: ToolRiskPolicy.Risk): ConfirmationButtons = ConfirmationButtons(
            offersRememberedYes = risk != ToolRiskPolicy.Risk.DESTRUCTIVE
        )
    }
}
