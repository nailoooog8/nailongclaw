// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure parsing / formatting half of WebSearchClient against the
 * documented Bocha and Tavily response shapes. The HTTP half is exercised
 * on-device via the settings page's 测试连接 button and QA item W5.
 */
class WebSearchClientTest {

    private val bochaBody = """
        {
          "code": 200,
          "data": {
            "webPages": {
              "value": [
                {
                  "name": "上海 <em>天气</em> - 中国天气网",
                  "url": "https://www.weather.com.cn/weather/101020100.shtml",
                  "snippet": "今天 <em>上海</em> 多云，18-24℃",
                  "summary": "一段很长的 AI 摘要，应当只在没有 snippet 时兜底使用",
                  "lastUpdated": "2026-10-25"
                },
                {
                  "name": "Weather in Shanghai",
                  "url": "https://example.com/shanghai",
                  "snippet": "",
                  "summary": "Summary fallback text"
                }
              ]
            }
          }
        }
    """.trimIndent()

    private val tavilyBody = """
        {
          "query": "shanghai weather",
          "answer": "Sunny",
          "results": [
            {
              "title": "Shanghai Weather",
              "url": "https://example.com/weather",
              "content": "Sunny, 22C",
              "score": 0.99
            }
          ]
        }
    """.trimIndent()

    private val deepseekBody = """
        {
          "results": [
            {
              "rerank_score": 2.1,
              "title": "上海天气",
              "url": "https://example.com/shanghai",
              "snippet": "晴，18-24℃",
              "summary": "一段 600-800 字给模型读的摘要",
              "site_name": "Example",
              "date": "2026-10-25T00:00:00Z",
              "authority": 3
            },
            {
              "title": "No snippet here",
              "url": "https://example.com/2",
              "snippet": "",
              "summary": "Summary only"
            }
          ]
        }
    """.trimIndent()

    // ---- Bocha parsing ----

    @Test
    fun `bocha hits carry title url and snippet with html stripped`() {
        val hits = WebSearchClient.parseBocha(bochaBody)
        assertEquals(2, hits.size)
        assertEquals("上海 天气 - 中国天气网", hits[0].title)
        assertEquals("https://www.weather.com.cn/weather/101020100.shtml", hits[0].url)
        assertEquals("今天 上海 多云，18-24℃", hits[0].snippet)
    }

    @Test
    fun `bocha falls back to summary when snippet is blank`() {
        val hits = WebSearchClient.parseBocha(bochaBody)
        assertEquals("Summary fallback text", hits[1].snippet)
    }

    @Test
    fun `bocha body without webPages yields empty list`() {
        assertTrue(WebSearchClient.parseBocha("""{"code":200,"data":{}}""").isEmpty())
        assertTrue(WebSearchClient.parseBocha("not json at all").isEmpty())
    }

    // ---- Tavily parsing ----

    @Test
    fun `tavily hits carry title url and content`() {
        val hits = WebSearchClient.parseTavily(tavilyBody)
        assertEquals(1, hits.size)
        assertEquals("Shanghai Weather", hits[0].title)
        assertEquals("https://example.com/weather", hits[0].url)
        assertEquals("Sunny, 22C", hits[0].snippet)
    }

    @Test
    fun `tavily body without results yields empty list`() {
        assertTrue(WebSearchClient.parseTavily("""{"query":"q"}""").isEmpty())
    }

    // ---- DeepSeek parsing ----

    @Test
    fun `deepseek hits carry title url and snippet`() {
        val hits = WebSearchClient.parseDeepSeek(deepseekBody)
        assertEquals(2, hits.size)
        assertEquals("上海天气", hits[0].title)
        assertEquals("https://example.com/shanghai", hits[0].url)
        assertEquals("晴，18-24℃", hits[0].snippet)
    }

    @Test
    fun `deepseek falls back to summary when snippet is blank`() {
        val hits = WebSearchClient.parseDeepSeek(deepseekBody)
        assertEquals("Summary only", hits[1].snippet)
    }

    @Test
    fun `deepseek body without results yields empty list`() {
        assertTrue(WebSearchClient.parseDeepSeek("""{"results":[]}""").isEmpty())
    }

    @Test
    fun `openai-style nested error message is extracted`() {
        // DeepSeek errors use the OpenAI-compatible shape {"error":{"message":...}}
        assertEquals(
            "Authentication Fails",
            WebSearchClient.extractError("""{"error":{"message":"Authentication Fails","type":"authentication_error"}}"""),
        )
    }

    // ---- Error extraction ----

    @Test
    fun `extractError reads common error fields`() {
        assertEquals("Invalid key", WebSearchClient.extractError("""{"detail":"Invalid key"}"""))
        assertEquals("quota exceeded", WebSearchClient.extractError("""{"message":"quota exceeded"}"""))
        assertEquals("plain body", WebSearchClient.extractError("plain body"))
    }

    // ---- Formatting (self-truncation: ToolResult has no size cap) ----

    @Test
    fun `formatHits numbers title url snippet and trims long snippets`() {
        val long = "x".repeat(300)
        val hits = listOf(WebSearchClient.SearchHit("T1", "https://a.com", long))
        val out = WebSearchClient.formatHits("q", hits)
        assertTrue(out.startsWith("Web search results for \"q\" (1):"))
        assertTrue(out.contains("1. T1"))
        assertTrue(out.contains("https://a.com"))
        assertTrue(out.contains("x".repeat(200)))
        assertTrue(!out.contains("x".repeat(201)))
    }

    @Test
    fun `formatHits caps the whole payload and marks truncation`() {
        val hits = (1..20).map {
            WebSearchClient.SearchHit("T$it", "https://a.com/$it", "snippet $it")
        }
        val out = WebSearchClient.formatHits("q", hits, maxSnippetChars = 50, totalCap = 200)
        assertTrue(out.length <= 200 + "\n(results truncated)".length)
        assertTrue(out.contains("(results truncated)"))
    }

    @Test
    fun `formatHits with no hits says so instead of an empty string`() {
        val out = WebSearchClient.formatHits("q", emptyList())
        assertTrue(out.contains("No results"))
    }
}
