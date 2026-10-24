// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import io.agents.pokeclaw.agent.memory.MemoryRecallFormat

data class ChatMessage(
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolSteps: List<ToolStep>? = null,
    val modelName: String? = null
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL_GROUP }
}

data class ToolStep(
    val toolName: String,
    val summary: String,
    val success: Boolean = false,
    /**
     * Structured view of a `memory_recall` result, parsed from [summary] by
     * [MemoryRecallFormat.chips]. Null for every other tool — and for a
     * `memory_recall` whose text parsed to nothing (empty bank, no match,
     * format drift), which falls back to the plain tool card. The summary
     * stays the single persisted truth: a restored transcript re-derives the
     * chips from it, so live and restored feeds render identically.
     */
    val memories: List<MemoryRecallFormat.Chip>? = null
)
