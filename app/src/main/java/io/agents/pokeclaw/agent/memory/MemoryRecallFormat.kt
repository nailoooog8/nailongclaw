// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

/**
 * The text contract of `memory_recall` output, and the parser that reads it
 * back into chips.
 *
 * ## Why the parser lives next to the formatter
 *
 * [MemoryRecallTool] writes the recall result as one text blob, and that blob
 * is all the UI ever sees: `ToolResult` is string-only, and
 * `TaskOrchestrator.onToolResult` forwards it as `TaskEvent.ToolResult.detail`.
 * The web surface renders a `memory_recall` call as memory chips
 * (`#id content`), which means *something* has to read the structure back out
 * of the text. Keeping [chips] in the same file as [chipLine] is the whole
 * safety story: the two cannot drift apart without the round-trip test
 * (`MemoryRecallFormatTest`) failing.
 *
 * ## The format is parsed from two different shapes
 *
 * Live, the text is multi-line (one chip per line). Restored from a saved
 * transcript, it has been through `ChatHistoryManager.oneLine()`, which
 * flattened every newline into a space — so the parser must not anchor to line
 * starts. [chips] uses `findAll` over the whole text with a lookahead for the
 * next `- [` marker (or the end), which reads both shapes identically.
 *
 * ## Truncation loses chips; it cannot invent them
 *
 * `TaskOrchestrator` truncates `detail` at 300 characters, appending `...`.
 * A chip cut in half fails the pattern or the lookahead and is dropped whole —
 * a missing chip is honest (the full text is still on the tool card), a wrong
 * chip would not be. The only terminator after a complete `(命中 N 次)` suffix
 * in untruncated text is the next `- [` or the end of the text, so `...` is
 * accepted as a terminator too: it can only mean truncation, and without it a
 * cut landing exactly after a whole chip would drop that chip as well.
 */
object MemoryRecallFormat {

    /**
     * One recalled entry, reduced to what a chip renders: `#id` and the fact.
     *
     * [id] is the entry's full [MemorySimilarity.idOf] value — a 12-hex-char
     * content hash, not a sequence number — kept whole so the chip reference
     * is the same string `memory_save` / `memory_forget` accept. The parser
     * only matches lowercase hex ids; anything else fails to parse and the
     * step falls back to the plain tool card.
     */
    data class Chip(
        val id: String,
        val kindLabel: String,
        val content: String,
    )

    fun header(query: String): String = "记忆检索「${query.ifBlank { "全部" }}」："

    fun chipLine(kindLabel: String, id: String, content: String, hitsDisplay: Int): String =
        "- [$kindLabel#$id] $content  (命中 $hitsDisplay 次)"

    /**
     * The tail of a chip: `  (命中 N 次)`, followed by the next `- [` bullet,
     * a truncation ellipsis, or the end of the text. The lookahead is what
     * keeps a `(命中 N 次)` occurrence *inside* a memory's content from ending
     * the chip early — the non-greedy content group extends past it instead.
     */
    private val CHIP = Regex(
        """-\s*\[([^\[\]#]+)#([0-9a-f]+)]\s*(.*?)\s*\(命中 (\d+) 次\)(?=\s*(?:- \[|\.{3}|$))"""
    )

    /** Parses chip data out of [chipLine] output, multi-line or flattened. */
    fun chips(text: String): List<Chip> = CHIP.findAll(text).mapNotNull { match ->
        Chip(
            id = match.groupValues[2],
            kindLabel = match.groupValues[1].trim(),
            content = match.groupValues[3].trim(),
        )
    }.toList()
}
