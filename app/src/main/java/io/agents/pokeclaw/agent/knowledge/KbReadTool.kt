// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.knowledge

import io.agents.pokeclaw.agent.ContextFence
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult

class KbReadTool : BaseTool() {

    override fun getName() = "kb_read"

    override fun getDescriptionEN() =
        "Read the full content of a note from the knowledge base vault by its path."

    override fun getDescriptionCN() =
        "根據路徑讀取知識庫中筆記的完整內容。"

    override fun getParameters() = listOf(
        ToolParameter(
            "path", "string",
            "File path relative to vault root, e.g. 'todos/2026-04-07.md'",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val path = requireString(params, "path")
            val result = KBManager.read(path)
            result.fold(
                // Vault notes are user/agent-written text that a later task
                // trusts; a note can be planted once and re-read forever.
                onSuccess = { ToolResult.success(ContextFence.wrap("knowledge_base", it)) },
                onFailure = { ToolResult.error(it.message ?: "kb_read failed") }
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.error("kb_read: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("kb_read error: ${e.message}")
        }
    }
}
