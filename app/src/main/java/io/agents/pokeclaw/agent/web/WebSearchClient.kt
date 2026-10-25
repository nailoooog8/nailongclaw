// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.web

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.agents.pokeclaw.agent.SearchProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal REST client behind the web_search tool.
 *
 * Bocha:  POST /v1/web-search {"query","count","summary"} -> data.webPages.value[].{name,url,snippet}
 * Tavily: POST /search        {"query","max_results"}     -> results[].{title,url,content}
 *
 * Parsing and formatting live in pure companion functions so they can be
 * unit-tested without Android or a network.
 */
class WebSearchClient(private val provider: SearchProvider, private val apiKey: String) {

    data class SearchHit(val title: String, val url: String, val snippet: String)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val HTML_TAG = Regex("<[^>]+>")

        // Search calls must stay snappy — the agent loop blocks on this tool.
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        private fun obj(parent: JsonObject?, name: String): JsonObject? =
            parent?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject

        private fun arr(parent: JsonObject?, name: String): JsonArray? =
            parent?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray

        private fun str(o: JsonObject, name: String): String =
            o.get(name)?.takeIf { !it.isJsonNull }?.asString ?: ""

        private fun stripTags(s: String): String =
            s.replace(HTML_TAG, "").replace("&amp;", "&").trim()

        private fun hitsFrom(
            array: JsonArray?,
            map: (JsonObject) -> SearchHit
        ): List<SearchHit> {
            if (array == null) return emptyList()
            val out = ArrayList<SearchHit>(array.size())
            for (el in array) {
                if (!el.isJsonObject) continue
                out.add(map(el.asJsonObject))
            }
            return out
        }

        /** Bocha web-search response -> hits. Prefers the short snippet, falls back to summary. */
        fun parseBocha(body: String): List<SearchHit> {
            val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return emptyList()
            val values = arr(obj(obj(root, "data"), "webPages"), "value")
            return hitsFrom(values) { o ->
                val snippet = str(o, "snippet").ifBlank { str(o, "summary") }
                SearchHit(
                    title = stripTags(str(o, "name")),
                    url = str(o, "url"),
                    snippet = stripTags(snippet)
                )
            }
        }

        /** Tavily search response -> hits. */
        fun parseTavily(body: String): List<SearchHit> {
            val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return emptyList()
            return hitsFrom(arr(root, "results")) { o ->
                SearchHit(
                    title = stripTags(str(o, "title")),
                    url = str(o, "url"),
                    snippet = stripTags(str(o, "content"))
                )
            }
        }

        /**
         * DeepSeek Search API response -> hits. Same results[] envelope as
         * Tavily; snippet is the ~200-char display text, summary the
         * 600-800-char model-oriented text.
         */
        fun parseDeepSeek(body: String): List<SearchHit> {
            val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return emptyList()
            return hitsFrom(arr(root, "results")) { o ->
                val snippet = str(o, "snippet").ifBlank { str(o, "summary") }
                SearchHit(
                    title = stripTags(str(o, "title")),
                    url = str(o, "url"),
                    snippet = stripTags(snippet)
                )
            }
        }

        /** Pulls a human-readable message out of common error body shapes. */
        fun extractError(body: String): String {
            val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return body.take(200)
            for (field in listOf("message", "msg", "detail", "error")) {
                val v = root.get(field) ?: continue
                if (v.isJsonNull) continue
                if (v.isJsonPrimitive) {
                    val s = v.asString
                    if (s.isNotBlank()) return s
                } else if (v.isJsonObject) {
                    val s = str(v.asJsonObject, "message").ifBlank { str(v.asJsonObject, "detail") }
                    if (s.isNotBlank()) return s
                } else if (v.isJsonArray) {
                    return v.toString()
                }
            }
            return body.take(200)
        }

        /**
         * Formats hits as numbered "title / URL / snippet" blocks for the LLM.
         * ToolResult has no built-in truncation, so this caps itself: each snippet
         * to [maxSnippetChars] and the whole payload to [totalCap].
         */
        fun formatHits(
            query: String,
            hits: List<SearchHit>,
            maxSnippetChars: Int = 200,
            totalCap: Int = 4000
        ): String {
            if (hits.isEmpty()) return "No results found for \"$query\"."
            val sb = StringBuilder("Web search results for \"$query\" (${hits.size}):\n\n")
            hits.forEachIndexed { i, h ->
                val block = buildString {
                    append(i + 1).append(". ").append(h.title.ifBlank { "(untitled)" }).append('\n')
                    if (h.url.isNotBlank()) append(h.url).append('\n')
                    append(h.snippet.take(maxSnippetChars))
                }
                if (sb.length + block.length > totalCap) {
                    sb.append("\n(results truncated)")
                    return sb.toString().trimEnd()
                }
                sb.append(block).append("\n\n")
            }
            return sb.toString().trimEnd()
        }
    }

    /**
     * Runs one search. Blocking; safe on the agent executor thread.
     * Never throws — failures come back via Result.
     */
    fun search(query: String, maxResults: Int): Result<List<SearchHit>> {
        val count = maxResults.coerceIn(1, 8)
        val body = JsonObject().apply {
            addProperty("query", query)
            when (provider) {
                SearchProvider.BOCHA -> {
                    addProperty("count", count)
                    addProperty("summary", true)
                }
                SearchProvider.TAVILY -> addProperty("max_results", count)
                SearchProvider.DEEPSEEK -> addProperty("count", count)
            }
        }
        val request = Request.Builder()
            .url(provider.endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = extractError(text).take(300)
                    return Result.failure(IllegalStateException("HTTP ${resp.code}: $detail"))
                }
                val hits = when (provider) {
                    SearchProvider.BOCHA -> parseBocha(text)
                    SearchProvider.TAVILY -> parseTavily(text)
                    SearchProvider.DEEPSEEK -> parseDeepSeek(text)
                }
                Result.success(hits.take(count))
            }
        } catch (e: IOException) {
            Result.failure(IllegalStateException("network error: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
