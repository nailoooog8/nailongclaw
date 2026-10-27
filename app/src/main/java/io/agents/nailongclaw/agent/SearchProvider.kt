// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent

/**
 * Web search provider definitions for the built-in web_search tool.
 * Used by WebSearchActivity to render the provider tabs, and by
 * WebSearchClient to build the REST request.
 */
enum class SearchProvider(
    val displayName: String,
    val endpoint: String,
    val docsUrl: String,
    val keyHint: String
) {
    DEEPSEEK(
        displayName = "DeepSeek",
        endpoint = "https://api.deepseek.com/v1/tools/search",
        docsUrl = "https://api-docs.deepseek.com/guides/search",
        keyHint = "sk-..."
    ),
    BOCHA(
        displayName = "博查 Bocha",
        endpoint = "https://api.bochaai.com/v1/web-search",
        docsUrl = "https://open.bochaai.com",
        keyHint = "sk-..."
    ),
    TAVILY(
        displayName = "Tavily",
        endpoint = "https://api.tavily.com/search",
        docsUrl = "https://www.tavily.com",
        keyHint = "tvly-..."
    );

    companion object {
        /**
         * Find provider by name (case-insensitive).
         * Returns DEEPSEEK as default.
         */
        fun fromName(name: String): SearchProvider {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: DEEPSEEK
        }
    }
}
