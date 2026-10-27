package io.agents.nailongclaw.ui.chat

import io.agents.nailongclaw.agent.memory.MemoryRecallFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the tool-group round trip in [ChatHistoryManager].
 *
 * A tool group is persisted as one markdown line per tool call
 * ("- ✓ name → summary"). Reading the file back must restore the structure,
 * not just the text: the chat screen's `ToolGroup()` renders `toolSteps` and
 * nothing else, so a group loaded as plain content drew an empty box, and the
 * web surface flattened it into one paragraph.
 */
class ChatHistoryManagerLoadTest {

    private fun transcript(body: String): File {
        val dir = Files.createTempDirectory("pokeclaw-history").toFile()
        val file = File(dir, "2026-10-22-convo.md")
        file.writeText(
            buildString {
                appendLine("---")
                appendLine("id: preview_claude_ui")
                appendLine("title: Convo")
                appendLine("created: 2026-10-22T15:30:00")
                appendLine("model: local")
                appendLine("---")
                appendLine()
                append(body)
            }
        )
        return file
    }

    @Test
    fun `tool group is restored as tool steps, not as text`() {
        val file = transcript(
            """
            ## User
            take a screenshot
            ## Tools
            - ✓ 截屏 → /data/user/0/io.agents.nailongclaw/cache/screenshots/1.png
            - ○ send_file → failed
            """.trimIndent()
        )

        val messages = ChatHistoryManager.load(file)

        val group = messages.first { it.role == ChatMessage.Role.TOOL_GROUP }
        assertNotNull("tool group must come back with steps", group.toolSteps)
        assertEquals(2, group.toolSteps!!.size)
        assertEquals("截屏", group.toolSteps!![0].toolName)
        assertEquals(
            "/data/user/0/io.agents.nailongclaw/cache/screenshots/1.png",
            group.toolSteps!![0].summary
        )
        assertTrue(group.toolSteps!![0].success)
        assertEquals("send_file", group.toolSteps!![1].toolName)
        assertEquals("failed", group.toolSteps!![1].summary)
        assertFalse(group.toolSteps!![1].success)
        // The raw lines are now redundant: keeping them would double-render
        // the group on the web surface (a card plus a paragraph).
        assertEquals("", group.content)
    }

    @Test
    fun `summary that itself contains an arrow stays whole`() {
        val file = transcript(
            """
            ## Tools
            - ✓ navigate → WhatsApp → open chat with 张工
            """.trimIndent()
        )

        val group = ChatHistoryManager.load(file)
            .first { it.role == ChatMessage.Role.TOOL_GROUP }

        assertEquals("navigate", group.toolSteps!![0].toolName)
        assertEquals("WhatsApp → open chat with 张工", group.toolSteps!![0].summary)
    }

    @Test
    fun `an unknown layout keeps its raw text instead of dropping it`() {
        val file = transcript(
            """
            ## Tools
            some free-form tool note
            """.trimIndent()
        )

        val group = ChatHistoryManager.load(file)
            .first { it.role == ChatMessage.Role.TOOL_GROUP }

        assertNull(group.toolSteps)
        assertEquals("some free-form tool note", group.content)
    }

    @Test
    fun `a tool line without a summary still parses`() {
        val file = transcript(
            """
            ## Tools
            - ✓ finish
            """.trimIndent()
        )

        val group = ChatHistoryManager.load(file)
            .first { it.role == ChatMessage.Role.TOOL_GROUP }

        assertEquals("finish", group.toolSteps!![0].toolName)
        assertEquals("", group.toolSteps!![0].summary)
    }

    @Test
    fun `a timestamped tool group keeps its timestamp`() {
        val file = transcript(
            """
            ## Tools
            <!-- pokeclaw:timestamp=1792678558202 -->
            - ✓ 截屏 → /tmp/a.png
            """.trimIndent()
        )

        val group = ChatHistoryManager.load(file)
            .first { it.role == ChatMessage.Role.TOOL_GROUP }

        assertEquals(1792678558202L, group.timestamp)
        assertEquals("截屏", group.toolSteps!![0].toolName)
    }

    @Test
    fun `a restored memory_recall step re-derives its chips from the summary`() {
        // The chips a live recall carried are not persisted as structure — the
        // summary line is the only thing on disk. The loader must parse them
        // back out so a restored feed renders .chips like the live one did.
        // The summary is oneLine()'d on save, so the text here is the
        // flattened shape.
        val file = transcript(
            """
            ## Tools
            - ✓ memory_recall → 记忆检索「全部」： - [偏好#a1b2c3d4e5f6] 妈妈出行优先上午车次 (命中 3 次) - [事实#0f9e8d7c6b5a] 常买杭州东 → 上海虹桥 (命中 1 次)
            """.trimIndent()
        )

        val group = ChatHistoryManager.load(file)
            .first { it.role == ChatMessage.Role.TOOL_GROUP }
        val step = group.toolSteps!!.single()

        assertEquals(
            listOf(
                MemoryRecallFormat.Chip("a1b2c3d4e5f6", "偏好", "妈妈出行优先上午车次"),
                MemoryRecallFormat.Chip("0f9e8d7c6b5a", "事实", "常买杭州东 → 上海虹桥"),
            ),
            step.memories,
        )
    }

    @Test
    fun `a restored memory_recall step with nothing to parse keeps memories null`() {
        // Empty-bank / no-match texts (and any older transcript) fall back to
        // the plain tool card — no chips rather than wrong chips.
        val file = transcript(
            """
            ## Tools
            - ✓ memory_recall → 记忆库还是空的（0 条）。
            """.trimIndent()
        )

        val step = ChatHistoryManager.load(file)
            .first { it.role == ChatMessage.Role.TOOL_GROUP }
            .toolSteps!!.single()

        assertNull(step.memories)
        assertEquals("memory_recall", step.toolName)
    }

    @Test
    fun `a non-recall tool step never gets chips`() {
        // Another tool's summary merely *looks* like recall output — chip
        // parsing is keyed on the tool name, not on the text.
        val file = transcript(
            """
            ## Tools
            - ✓ 截屏 → 结果 - [偏好#12] 妈妈出行优先上午车次 (命中 3 次)
            """.trimIndent()
        )

        val step = ChatHistoryManager.load(file)
            .first { it.role == ChatMessage.Role.TOOL_GROUP }
            .toolSteps!!.single()

        assertNull(step.memories)
    }
}
