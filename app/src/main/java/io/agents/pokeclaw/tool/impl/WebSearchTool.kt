// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import io.agents.pokeclaw.agent.SearchProvider
import io.agents.pokeclaw.agent.web.WebSearchClient
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.KVUtils

/**
 * Web search over a configured search API (Bocha / Tavily).
 *
 * This is an information tool, not a device-automation tool: it answers
 * knowledge questions directly. "Search inside a specific app" stays on the
 * open_app + input_text automation path.
 */
class WebSearchTool : BaseTool() {

    override fun getName() = "web_search"

    override fun getDisplayName() = "Web Search"

    override fun getDescriptionEN() =
        "Search the web for up-to-date information (news, weather, prices, facts, anything you are " +
        "unsure about) via a configured search API. Returns clean text results with URLs. " +
        "Use this for knowledge questions instead of opening a browser or search app. " +
        "For searching INSIDE a specific app (e.g. \"search Taobao for shoes\"), do NOT use this — " +
        "use open_app + input_text automation instead."

    override fun getDescriptionCN() =
        "通过网络搜索 API 查询实时信息（新闻、天气、价格、事实等一切不确定的知识），" +
        "返回带 URL 的干净文本结果。查知识/资讯类问题优先用本工具，不要为查信息去打开浏览器或搜索 App。" +
        "若要在某个具体 App 里搜索（如“在淘宝搜鞋子”），不要用本工具，" +
        "仍应使用 open_app + input_text 自动化路径。"

    override fun getParameters() = listOf(
        ToolParameter(
            "query", "string",
            "Search query text.",
            true
        ),
        ToolParameter(
            "max_results", "integer",
            "Optional: number of results to return, 1-8. Default 5.",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        return try {
            val query = requireString(params, "query").trim()
            if (query.isEmpty()) return ToolResult.error("web_search: query is empty")
            val maxResults = optionalInt(params, "max_results", 5).coerceIn(1, 8)
            val provider = SearchProvider.fromName(KVUtils.getWebSearchProvider())
            val apiKey = KVUtils.getWebSearchApiKeyFor(provider.name)
            if (apiKey.isBlank()) {
                return ToolResult.error(
                    "web_search is not configured: no API key for ${provider.displayName}. " +
                        "Tell the user to open Settings → Web Search and save a key (Bocha or Tavily), " +
                    "then answer from your own knowledge if you can."
                )
            }
            WebSearchClient(provider, apiKey).search(query, maxResults).fold(
                onSuccess = { hits ->
                    ToolResult.success(WebSearchClient.formatHits(query, hits))
                },
                onFailure = { ToolResult.error("web_search failed: ${it.message}") }
            )
        } catch (e: IllegalArgumentException) {
            ToolResult.error("web_search: missing required param — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("web_search error: ${e.message}")
        }
    }
}
