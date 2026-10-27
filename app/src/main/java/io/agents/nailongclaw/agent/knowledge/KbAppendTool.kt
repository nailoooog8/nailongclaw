// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.knowledge

import io.agents.nailongclaw.tool.BaseTool
import io.agents.nailongclaw.tool.ToolParameter
import io.agents.nailongclaw.tool.ToolResult

class KbAppendTool : BaseTool() {

    override fun getName() = "kb_append"

    override fun getDescriptionEN() =
        "Append content to an existing note without overwriting it. " +
        "Use for adding items to a list, logging new entries, or extending notes."

    override fun getDescriptionCN() =
        "在已有筆記末尾追加內容，不覆蓋原有內容。適用於向清單添加條目、記錄新事項或擴展筆記。"

    override fun getParameters() = listOf(
        ToolParameter(
            "path", "string",
            "File path relative to vault root, e.g. 'todos/shopping.md'",
            true
        ),
        ToolParameter(
            "content", "string",
            "Markdown content to append at the end of the file",
            true
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val path = requireString(params, "path")
            val content = requireString(params, "content")
            val result = KBManager.append(path, content)
            result.fold(
                onSuccess = { ToolResult.success(it) },
                onFailure = { ToolResult.error(it.message ?: "kb_append failed") }
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.error("kb_append: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("kb_append error: ${e.message}")
        }
    }
}
