package io.agents.pokeclaw.ui.web

import io.agents.pokeclaw.agent.mcp.McpConfirmationRequest
import io.agents.pokeclaw.agent.memory.MemoryRecallFormat
import io.agents.pokeclaw.ui.chat.ChatMessage
import io.agents.pokeclaw.ui.chat.ChatSink
import io.agents.pokeclaw.ui.chat.ToolStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [WebChatSink] — the third `ChatSink` implementation, and the one that
 * renders into a WebView instead of Compose state.
 *
 * The class keeps a plain list and pushes snapshots through a callback rather
 * than touching a WebView, which is what makes it testable on the JVM without
 * Robolectric. These cases pin the parts that would otherwise only be visible
 * as a wrong-looking page on a device.
 */
class WebChatSinkTest {

    private var last: List<WebChatSink.WebMessage> = emptyList()
    private var renders = 0
    private var lastConfirmation: Any? = "untouched"

    private fun sink() = WebChatSink(
        onRender = { messages ->
            last = messages
            renders++
        },
        onConfirmation = { request -> lastConfirmation = request },
    )

    private fun typing() =
        ChatMessage(ChatMessage.Role.ASSISTANT, ChatSink.TYPING_INDICATOR)

    private fun user(text: String) = ChatMessage(ChatMessage.Role.USER, text)

    private fun assistant(text: String, model: String? = null) =
        ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = model)

    @Test
    fun `an emitted snapshot is immutable`() {
        val s = sink()
        s.append(user("a"))
        val captured = last
        s.append(user("b"))
        // The callback handed out a view of the list, not the live one. If it
        // aliased, every previously delivered frame would silently mutate.
        assertEquals(1, captured.size)
        assertEquals(2, last.size)
    }

    @Test
    fun `appends preserve order and role`() {
        val s = sink()
        s.append(user("first"))
        s.append(assistant("second"))
        assertEquals(listOf("USER", "ASSISTANT"), last.map { it.role })
    }

    @Test
    fun `the typing indicator is flagged, not inferred from content`() {
        val s = sink()
        s.append(assistant("..."))
        assertTrue(last.single().isTypingIndicator)

        // Same text, different role: a user who literally types "..." must not
        // be treated as an in-flight assistant turn.
        val s2 = sink()
        s2.append(user("..."))
        assertFalse(last.single().isTypingIndicator)
    }

    @Test
    fun `replaceTypingIndicator fills the indicator in place`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())
        s.replaceTypingIndicator("the answer", "gpt-x")

        assertEquals(2, last.size)
        assertEquals("the answer", last[1].content)
        assertEquals("gpt-x", last[1].modelName)
        assertFalse(last[1].isTypingIndicator)
    }

    @Test
    fun `replaceTypingIndicator with no indicator is a no-op`() {
        val s = sink()
        s.append(user("q"))
        val before = renders
        s.replaceTypingIndicator("late reply", null)
        // Terminal TaskEvents can arrive after a Response already filled the
        // indicator; inventing a message there would duplicate the reply.
        assertEquals(1, last.size)
        assertEquals(before, renders)
    }

    @Test
    fun `completeAssistantTurn appends after a tool action removed the indicator`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())
        s.removeTypingIndicator()
        s.appendToolStep(ToolStep("Get Screen Info", "screen tree", true))

        s.completeAssistantTurn("The marker is visible.", "Gemma")

        assertEquals(
            listOf("USER", "TOOL_GROUP", "ASSISTANT"),
            last.map { it.role },
        )
        assertEquals("The marker is visible.", last.last().content)
        assertEquals("Gemma", last.last().modelName)
        assertFalse(last.last().isTypingIndicator)
    }

    @Test
    fun `completeAssistantTurn replaces a pending indicator in place`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())
        s.appendToolStep(ToolStep("Get Screen Info", "screen tree", true))

        s.completeAssistantTurn("The marker is visible.", "Gemma")

        assertEquals(listOf("USER", "ASSISTANT", "TOOL_GROUP"), last.map { it.role })
        assertEquals("The marker is visible.", last[1].content)
        assertFalse(last[1].isTypingIndicator)
    }

    @Test
    fun `a mid-turn text and a final answer both land`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())

        // A mid-turn utterance replaces the pending indicator; the final answer
        // then has no indicator to fill and must be appended rather than
        // swallowed. `TaskEvent.Response` publishes the first, `Completed` the
        // second.
        s.completeAssistantTurn("Working on it.", "Gemma")
        s.completeAssistantTurn("All done.", "Gemma")

        assertEquals(listOf("USER", "ASSISTANT", "ASSISTANT"), last.map { it.role })
        assertEquals("Working on it.", last[1].content)
        assertEquals("All done.", last[2].content)
        assertFalse(last[1].isTypingIndicator)
        assertFalse(last[2].isTypingIndicator)
    }

    @Test
    fun `a second replace after the first is a no-op`() {
        val s = sink()
        s.append(typing())
        s.replaceTypingIndicator("first", null)
        s.replaceTypingIndicator("second", null)
        assertEquals("first", last.single().content)
    }

    @Test
    fun `removeTypingIndicator drops it and shifts the index`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())
        s.removeTypingIndicator()
        assertEquals(1, last.size)
        assertFalse(last.single().isTypingIndicator)

        // The next indicator must land on the new tail, not the stale index.
        s.append(typing())
        s.replaceTypingIndicator("after", null)
        assertEquals(2, last.size)
        assertEquals("after", last[1].content)
    }

    @Test
    fun `removeTypingIndicator twice is a no-op`() {
        val s = sink()
        s.append(typing())
        s.removeTypingIndicator()
        val before = renders
        s.removeTypingIndicator()
        assertEquals(0, last.size)
        assertEquals(before, renders)
    }

    @Test
    fun `scalar changes re-emit`() {
        val s = sink()
        val before = renders
        s.modelStatus = "● doubao · Cloud"
        s.awaitingReply = true
        s.taskRunning = true
        assertEquals(before + 3, renders)
        assertEquals("● doubao · Cloud", s.modelStatus)
        assertTrue(s.awaitingReply)
        assertTrue(s.taskRunning)
    }

    @Test
    fun `snapshotMessages rebuilds real ChatMessages`() {
        val s = sink()
        s.append(user("hi"))
        s.append(typing())
        s.replaceTypingIndicator("hello", "cloud-1")

        val rebuilt = s.snapshotMessages()
        assertEquals(2, rebuilt.size)
        assertEquals(ChatMessage.Role.USER, rebuilt[0].role)
        assertEquals("hello", rebuilt[1].content)
        assertEquals("cloud-1", rebuilt[1].modelName)
        assertNull(rebuilt[0].toolSteps)
    }

    @Test
    fun `tool steps survive the trip through the web view model`() {
        val s = sink()
        s.append(
            ChatMessage(
                ChatMessage.Role.ASSISTANT,
                "done",
                toolSteps = listOf(
                    ToolStep("accessibility.open_app", "com.MobileTicket", true),
                    ToolStep("tap", "btn_send", false),
                ),
            )
        )
        assertEquals(2, last.single().toolSteps.size)

        val rebuilt = s.snapshotMessages().single()
        assertEquals(2, rebuilt.toolSteps?.size)
        assertEquals("accessibility.open_app", rebuilt.toolSteps?.get(0)?.toolName)
        assertFalse(rebuilt.toolSteps?.get(1)?.success ?: true)
    }

    @Test
    fun `memory chips ride the web view model and the snapshot back`() {
        // The page renders a memory_recall step as .chips instead of the tool
        // card, so the chips must survive both directions of the sink: out to
        // JSON (through ToolStepView) and back for saveChat (through
        // snapshotMessages). Losing them on the way back would make the next
        // save drop chips that the current screen shows.
        val chips = listOf(
            MemoryRecallFormat.Chip("a1b2c3d4e5f6", "偏好", "妈妈出行优先上午车次"),
            MemoryRecallFormat.Chip("0f9e8d7c6b5a", "事实", "常买杭州东 → 上海虹桥"),
        )
        val s = sink()
        s.appendToolStep(
            ToolStep(
                "memory_recall",
                "记忆检索「全部」：\n- [偏好#12] 妈妈出行优先上午车次  (命中 3 次)",
                success = true,
                memories = chips,
            )
        )

        assertEquals(chips, last.single().toolSteps.single().memories)

        val rebuilt = s.snapshotMessages().single().toolSteps?.single()
        assertEquals(chips, rebuilt?.memories)
    }

    @Test
    fun `a step without chips still snapshots as memories-null`() {
        // Not every tool step is a recall; snapshotMessages must not invent an
        // empty chip list for them — null is what TaskFlowController sets.
        val s = sink()
        s.appendToolStep(ToolStep("截屏", "/tmp/a.png", true))

        assertTrue(last.single().toolSteps.single().memories.isEmpty()) // view default: empty list
        val rebuilt = s.snapshotMessages().single().toolSteps?.single()
        assertNull(rebuilt?.memories)
    }

    @Test
    fun `clear drops messages and any pending indicator`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())
        s.clear()
        assertEquals(0, last.size)

        // A stale index here would make the next replace rewrite an old message.
        s.append(typing())
        s.replaceTypingIndicator("fresh", null)
        assertEquals("fresh", last.single().content)
        assertEquals(1, last.size)
    }

    @Test
    fun `model name is kept when replace does not supply one`() {
        val s = sink()
        s.append(assistant("partial", "gpt-4"))
        s.append(typing())
        s.replaceTypingIndicator("done", null)
        // The indicator carries no model, so it must not blank the earlier one.
        assertEquals("gpt-4", last[0].modelName)
        assertNull(last[1].modelName)
    }

    // ---------------------------------------------------------------------
    // restore(): reloading a transcript that a killed process left on disk.
    // ---------------------------------------------------------------------

    @Test
    fun `restore replaces whatever is on screen`() {
        val s = sink()
        s.append(user("stale"))
        s.restore(listOf(user("persisted q"), assistant("persisted a")))
        assertEquals(listOf("persisted q", "persisted a"), last.map { it.content })
    }

    @Test
    fun `restore drops a typing indicator left behind by a killed task`() {
        val s = sink()
        // A task killed mid-flight persists its indicator. Reloading it would
        // show a permanent "thinking" bubble with no task left to resolve it.
        s.restore(listOf(user("q"), typing()))
        assertEquals(listOf("q"), last.map { it.content })
        assertFalse(last.any { it.isTypingIndicator })
    }

    @Test
    fun `restore of an empty history clears the screen`() {
        val s = sink()
        s.append(user("old"))
        s.restore(emptyList())
        assertTrue(last.isEmpty())
    }

    @Test
    fun `restore leaves the sink ready to accept new messages`() {
        val s = sink()
        s.restore(listOf(user("persisted")))
        s.append(typing())
        s.replaceTypingIndicator("fresh", null)
        // The restored list must not leave a stale indicator index behind, or
        // the replace would rewrite an older message instead.
        assertEquals(listOf("persisted", "fresh"), last.map { it.content })
    }

    @Test
    fun `restore keeps tool steps from the saved transcript`() {
        val s = sink()
        s.restore(
            listOf(
                ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "done",
                    toolSteps = listOf(ToolStep("tap", "btn_send", true)),
                )
            )
        )
        assertEquals(1, last.single().toolSteps.size)
        assertEquals("tap", last.single().toolSteps.single().name)
    }

    @Test
    fun `restore keeps memory chips from the saved transcript`() {
        // A restored memory_recall step must render as chips again, not fall
        // back to a tool card — the restore path carries whatever the loader
        // re-derived from the persisted summary.
        val chips = listOf(MemoryRecallFormat.Chip("5e4d3c2b1a09", "身份", "妈妈身份证已存"))
        val s = sink()
        s.restore(
            listOf(
                ChatMessage(
                    ChatMessage.Role.TOOL_GROUP,
                    "",
                    toolSteps = listOf(ToolStep("memory_recall", "记忆检索「全部」：…", true, chips)),
                )
            )
        )
        assertEquals(chips, last.single().toolSteps.single().memories)
    }

    // ---------------------------------------------------------------------
    // appendToolStep: the producer side that was entirely missing until now.
    // ChatMessage.toolSteps was never assigned anywhere in TaskFlowController,
    // so every tool card in the design was a static sample.
    // ---------------------------------------------------------------------

    @Test
    fun `appendToolStep lands as its own TOOL_GROUP message`() {
        val s = sink()
        s.append(user("do it"))
        s.appendToolStep(ToolStep("Open App", "com.MobileTicket", true))

        assertEquals(2, last.size)
        assertEquals("TOOL_GROUP", last[1].role)
        assertEquals("", last[1].content)
    }

    @Test
    fun `a tool step does not disturb the typing indicator`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())
        s.appendToolStep(ToolStep("Open App", "com.MobileTicket", true))

        // A tool call happens while the turn is in flight. The indicator is
        // tracked by index, not by being the tail, so appending after it must
        // leave it alone — otherwise the reply would have nowhere to land.
        // The reply fills the indicator's own slot (index 1), not the newest one.
        assertEquals(3, last.size)
        assertTrue(last[1].isTypingIndicator)
        s.replaceTypingIndicator("done", null)
        assertEquals("done", last[1].content)
        assertEquals("TOOL_GROUP", last[2].role)
    }

    @Test
    fun `a tool step survives the round trip back to ChatMessage`() {
        val s = sink()
        s.appendToolStep(ToolStep("Send Message", "Mom: hi", false))

        val rebuilt = s.snapshotMessages().single()
        assertEquals(ChatMessage.Role.TOOL_GROUP, rebuilt.role)
        assertEquals(1, rebuilt.toolSteps?.size)
        assertEquals("Send Message", rebuilt.toolSteps?.get(0)?.toolName)
        assertFalse(rebuilt.toolSteps?.get(0)?.success ?: true)
    }

    @Test
    fun `consecutive tool steps each get their own row`() {
        val s = sink()
        s.appendToolStep(ToolStep("Open App", "com.A", true))
        s.appendToolStep(ToolStep("Tap", "btn_send", true))
        s.appendToolStep(ToolStep("Send Message", "Mom: hi", false))

        // One row per call, not one row per message batch: the design shows
        // each action as its own card, and merging them would hide the order.
        assertEquals(3, last.size)
        assertEquals(listOf("Open App", "Tap", "Send Message"), last.map { it.toolSteps.single().name })
    }

    @Test
    fun `a tool step restored from disk renders as a card`() {
        val s = sink()
        s.restore(
            listOf(
                ChatMessage(
                    ChatMessage.Role.TOOL_GROUP,
                    "",
                    toolSteps = listOf(ToolStep("Open App", "com.MobileTicket", true)),
                )
            )
        )
        // buildTurn keys off role === 'TOOL_GROUP' falling through to the
        // toolSteps branch; without content it must still emit a card, so the
        // role and the payload both have to survive persistence.
        assertEquals("TOOL_GROUP", last.single().role)
        assertEquals("com.MobileTicket", last.single().toolSteps.single().summary)
    }

    @Test
    fun `a pending MCP confirmation is live state, not a message`() {
        val s = sink()
        val request = McpConfirmationRequest(
            toolName = "mcp_qatools_echo",
            displayName = "echo",
            serverName = "qatools",
            description = "Echoes text back",
            paramsPreview = "text = hello",
        )
        s.showMcpConfirmation(request)

        // Its own channel, so the page can draw it after a full feed redraw
        // instead of being wiped by it.
        assertEquals(request, lastConfirmation)
        // ...and it must never enter the transcript: a card that is still
        // waiting cannot be persisted, and a restored one would have no task
        // behind it to answer it.
        assertTrue(last.isEmpty())
        assertTrue(s.snapshotMessages().isEmpty())

        s.hideMcpConfirmation()
        assertNull(lastConfirmation)
        assertTrue(last.isEmpty())
    }
}
