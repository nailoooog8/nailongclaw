package io.agents.nailongclaw.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers [ChatHistoryManager.rename]'s title sanitisation.
 *
 * The transcript is line-oriented: [ChatHistoryManager.listConversations]
 * decides what a line *is* from the line itself, and keeps the last `id:` it
 * reads. A rename that writes a user-supplied title verbatim can therefore
 * inject a second `id:` line and re-point the whole file at another
 * conversation — the exact "forged id" scenario [ChatHistoryManager.appendFrontmatter]
 * was written to prevent on the save path. Both doors that reach rename take
 * free text (the chat screen's rename dialog, the preview surface's bridge),
 * so the discipline lives in this shared seam rather than at either caller.
 */
class ChatHistoryManagerRenameTest {

    private fun transcript(id: String = "preview_abc"): File {
        val dir = Files.createTempDirectory("pokeclaw-rename").toFile()
        val file = File(dir, "2026-10-27-convo.md")
        file.writeText(
            buildString {
                appendLine("---")
                appendLine("id: $id")
                appendLine("title: Convo")
                appendLine("created: 2026-10-27T15:30:00")
                appendLine("model: local")
                appendLine("---")
                appendLine()
                appendLine("## User")
                appendLine("hello")
            }
        )
        return file
    }

    private fun File.idLines() = readLines().filter { it.startsWith("id: ") }

    private fun File.titleLine() = readLines().first { it.startsWith("title: ") }

    @Test
    fun `ordinary rename replaces the title`() {
        val file = transcript()

        assertTrue(ChatHistoryManager.rename(file, "Mom's birthday"))

        assertEquals("title: Mom's birthday", file.titleLine())
        assertEquals(listOf("id: preview_abc"), file.idLines())
    }

    @Test
    fun `a title carrying a newline cannot inject a second id line`() {
        val file = transcript()

        assertTrue(
            ChatHistoryManager.rename(
                file,
                "innocent\nid: preview_claude_ui\n---"
            )
        )

        // The injected id line never reaches the frontmatter: the id count
        // stays at one, and it is still the file's own.
        assertEquals(listOf("id: preview_abc"), file.idLines())
        // The title collapses onto one line instead of continuing the block.
        assertEquals("title: innocent id: preview_claude_ui ---", file.titleLine())
    }

    @Test
    fun `crlf in a title collapses too`() {
        val file = transcript()

        assertTrue(ChatHistoryManager.rename(file, "a\r\nb"))

        assertEquals("title: a b", file.titleLine())
        assertEquals(listOf("id: preview_abc"), file.idLines())
    }

    @Test
    fun `a blank title is refused and the file is left alone`() {
        val file = transcript()
        val before = file.readText()

        assertFalse(ChatHistoryManager.rename(file, "   \n  "))

        assertEquals(before, file.readText())
    }

    @Test
    fun `renaming a missing file fails`() {
        val file = File(Files.createTempDirectory("pokeclaw-rename").toFile(), "nope.md")

        assertFalse(ChatHistoryManager.rename(file, "x"))
    }
}
