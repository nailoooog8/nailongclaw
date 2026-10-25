package io.agents.pokeclaw.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `memory_recall` text contract: what [MemoryRecallTool] writes and
 * what [MemoryRecallFormat.chips] must read back.
 *
 * The round trip is the safety story — the formatter and the parser live in
 * the same object so this test can drive one through the other. If the tool's
 * line format ever changes without the parser following, the first case here
 * fails; the parser drifting alone breaks the flattened case, because a
 * restored transcript only has the flattened text left to parse.
 */
class MemoryRecallFormatTest {

    private fun line(kind: String, id: String, content: String, hits: Int) =
        MemoryRecallFormat.chipLine(kind, id, content, hits)

    private fun text(query: String, vararg lines: String) =
        MemoryRecallFormat.header(query) + "\n" + lines.joinToString("\n")

    @Test
    fun `a formatted result parses back to the same chips`() {
        val out = text(
            "妈妈的偏好",
            line("偏好", "a1b2c3d4e5f6", "妈妈出行优先上午车次", 3),
            line("事实", "0f9e8d7c6b5a", "常买杭州东 → 上海虹桥", 1),
            line("身份", "5e4d3c2b1a09", "妈妈身份证已存", 2),
        )

        val chips = MemoryRecallFormat.chips(out)

        assertEquals(
            listOf(
                MemoryRecallFormat.Chip("a1b2c3d4e5f6", "偏好", "妈妈出行优先上午车次"),
                MemoryRecallFormat.Chip("0f9e8d7c6b5a", "事实", "常买杭州东 → 上海虹桥"),
                MemoryRecallFormat.Chip("5e4d3c2b1a09", "身份", "妈妈身份证已存"),
            ),
            chips,
        )
    }

    @Test
    fun `a flattened result parses the same way`() {
        // ChatHistoryManager.oneLine() flattens newlines into spaces before a
        // transcript is saved. The parser must read that shape too — restore
        // has nothing else to work from.
        val multi = text(
            "全部",
            line("偏好", "a1b2c3d4e5f6", "妈妈出行优先上午车次", 3),
            line("事实", "0f9e8d7c6b5a", "常买杭州东 → 上海虹桥", 1),
        )
        val flat = multi.replace("\n", " ")

        assertEquals(MemoryRecallFormat.chips(multi), MemoryRecallFormat.chips(flat))
        assertEquals(2, MemoryRecallFormat.chips(flat).size)
        assertEquals("0f9e8d7c6b5a", MemoryRecallFormat.chips(flat)[1].id)
    }

    @Test
    fun `a truncated result loses whole chips, never halves one`() {
        // TaskOrchestrator cuts detail at 300 chars and appends "...". A chip
        // cut in half must vanish, not come back with its content beheaded.
        val full = text(
            "全部",
            line("偏好", "a1b2c3d4e5f6", "妈妈出行优先上午车次", 3),
            line("事实", "0f9e8d7c6b5a", "常买杭州东 → 上海虹桥", 1),
            line("身份", "5e4d3c2b1a09", "妈妈身份证已存", 2),
        )

        val cut = full.substring(0, full.indexOf("#5e4d3c2b1a09")) + "..."

        val chips = MemoryRecallFormat.chips(cut)
        assertEquals(2, chips.size)
        assertTrue(chips.none { it.id == "5e4d3c2b1a09" })
        assertEquals("妈妈出行优先上午车次", chips[0].content)
    }

    @Test
    fun `a cut landing exactly after a whole chip still parses that chip`() {
        val out = text("全部", line("偏好", "a1b2c3d4e5f6", "优先上午车次", 3))

        // Truncation appended "..." right after the complete chip.
        val cut = out + "..."

        assertEquals(listOf(MemoryRecallFormat.Chip("a1b2c3d4e5f6", "偏好", "优先上午车次")), MemoryRecallFormat.chips(cut))
    }

    @Test
    fun `the empty-bank and no-match texts yield no chips`() {
        assertTrue(MemoryRecallFormat.chips("记忆库还是空的（0 条）。").isEmpty())
        assertTrue(
            MemoryRecallFormat.chips("记忆库里没有匹配「车票」的内容（共 4 条）。").isEmpty()
        )
    }

    @Test
    fun `a hit-count phrase inside the content does not end the chip early`() {
        val out = text(
            "全部",
            line("事件", "9a8b7c6d5e4f", "备注里写着 (命中 2 次) 的是旧版本", 7),
        )

        assertEquals(
            listOf(MemoryRecallFormat.Chip("9a8b7c6d5e4f", "事件", "备注里写着 (命中 2 次) 的是旧版本")),
            MemoryRecallFormat.chips(out),
        )
    }

    @Test
    fun `an empty query still renders the full-width label`() {
        assertEquals("记忆检索「全部」：", MemoryRecallFormat.header(""))
        assertEquals("记忆检索「车票」：", MemoryRecallFormat.header("车票"))
    }

    @Test
    fun `a superseded entry's history mark survives the round trip inside the content`() {
        // The mark rides in the content region on purpose: the `(命中 N 次)`
        // tail is the parser's anchor, and anything between the tail and the
        // next `- [` would break the lookahead and lose the chip entirely.
        val dead = "用户的手机是Pixel。" + MemoryRecallFormat.historyMark("0f9e8d7c6b5a")
        val out = text(
            "手机",
            line("事实", "a1b2c3d4e5f6", "用户的手机是小米。", 1),
            line("事实", "0f9e8d7c6b5a", dead, 2),
        )

        val chips = MemoryRecallFormat.chips(out)
        assertEquals(2, chips.size)
        assertEquals("0f9e8d7c6b5a", chips[1].id)
        assertTrue(chips[1].content.contains("已作废，被 #0f9e8d7c6b5a 接替"))
        // And the flattened transcript shape keeps it too.
        assertEquals(chips, MemoryRecallFormat.chips(out.replace("\n", " ")))
    }
}
