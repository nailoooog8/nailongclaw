// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.memory

import io.agents.nailongclaw.agent.ContextFence
import io.agents.nailongclaw.tool.BaseTool
import io.agents.nailongclaw.tool.ToolParameter
import io.agents.nailongclaw.tool.ToolResult

/**
 * Searches the memory bank by topic — the "what do you remember about X"
 * tool. Recalled entries are reinforced, so the ones that keep proving useful
 * stay near the top of future briefings.
 */
class MemoryRecallTool : BaseTool() {

    override fun getName() = "memory_recall"

    override fun getDescriptionEN() =
        "Search the long-term memory bank by topic or keyword. Returns the most relevant remembered " +
            "facts (identity, preferences, device/account facts, past outcomes). " +
            "A blank query lists the most relevant recent and pinned memories. " +
            "By default only currently-valid facts come back — a fact replaced by a newer one stays " +
            "out unless include_history is true, which surfaces superseded entries marked as such. " +
            "Each result carries a #id you can pass to memory_save's related_ids to link a new " +
            "memory to it."

    override fun getDescriptionCN() =
        "按主题或关键词检索长期记忆库，返回最相关的已记住事实（身份、偏好、设备/账号信息、" +
            "过往结果）。query 留空则返回最近与置顶的记忆。" +
            "默认只返回仍有效的事实——被新事实顶替的旧记忆不会出现，" +
            "除非 include_history 为 true（此时会带「已作废」标注）。" +
            "每条结果带 #编号，可用 memory_save 的 related_ids 把新记忆关联到它。"

    override fun getParameters() = listOf(
        ToolParameter(
            "query", "string",
            "Topic or keyword to search for, e.g. '悬浮窗 权限' or '用户的偏好'. Blank = recent + pinned.",
            false
        ),
        ToolParameter(
            "limit", "integer",
            "Maximum number of memories to return (1-25, default 5)",
            false
        ),
        ToolParameter(
            "kind", "string",
            "Filter by category: profile | preference | fact | event (default: no filter)",
            false
        ),
        ToolParameter(
            "include_history", "boolean",
            "Also return superseded memories (marked 已作废), for questions like " +
                "'what did you used to believe'. Default: false (only currently-valid facts).",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val query = optionalString(params, "query", "")
            val limit = optionalInt(params, "limit", 5)
            val kind = optionalString(params, "kind", "").takeIf { it.isNotBlank() }
            val includeHistory = optionalBoolean(params, "include_history", false)

            val total = MemoryStore.count()
            if (total == 0) {
                return ToolResult.success("记忆库还是空的（0 条）。")
            }

            val results = MemoryStore.recall(query, limit, kind, includeHistory)
            if (results.isEmpty()) {
                val scope = if (includeHistory) "全部（含已作废）" else query.ifBlank { "全部" }
                return ToolResult.success(
                    "记忆库里没有匹配「$scope」的内容（共 $total 条）。"
                )
            }
            // The line format is a UI contract, not just model-facing text: the
            // web surface reads chips back out of this exact text. Format and
            // parser live together in MemoryRecallFormat so they cannot drift.
            // Content is sanitized first — a memory can be written by the agent
            // from untrusted input, so it must not be able to close the fence.
            val body = results.joinToString("\n") { r ->
                val e = r.entry
                val content =
                    if (e.isInvalidated) e.content + MemoryRecallFormat.historyMark(e.supersededBy)
                    else e.content
                MemoryRecallFormat.chipLine(e.kindLabel, e.id, ContextFence.sanitize(content), e.hits + 1)
            }
            ToolResult.success(
                // Recall returns stored text, and stored text is data — fence it
                // the same way the briefing already was (R3-03).
                ContextFence.wrap("memory_recall", MemoryRecallFormat.header(query) + "\n" + body)
            )
        } catch (e: Exception) {
            ToolResult.error("memory_recall error: ${e.message}")
        }
    }
}