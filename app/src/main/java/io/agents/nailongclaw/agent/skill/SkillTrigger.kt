// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.skill

/**
 * A skill trigger pattern compiled once, when the skill is registered,
 * instead of on every routing attempt.
 *
 * F7: user skill recipes are YAML files on the external files dir and their
 * triggers are documented as regex, so a pattern is attacker-influenced
 * text that used to reach `Regex(...)` inside `findByTrigger` — one compile
 * per skill per pattern per task, with nothing to stop a
 * catastrophic-backtracking pattern from parking the matching thread.
 * Compiling at registration moves that cost to load time, where it happens
 * once and can refuse the pattern instead of executing it.
 *
 * The matching semantics are unchanged from the inline version: `{name}`
 * becomes `(.+)`, both sides are lowercased, and the first trigger that
 * matches and carries placeholders wins. Only *when* the compile happens
 * has moved.
 */
class SkillTrigger private constructor(
    /** The pattern as written in the recipe, for logs and error messages. */
    val source: String,
    private val regex: Regex,
    private val paramNames: List<String>
) {

    fun matches(taskLower: String): Boolean = regex.containsMatchIn(taskLower)

    /**
     * Parameters this trigger extracts out of [taskLower], or null when the
     * trigger does not apply. A trigger that matches but carries no
     * `{placeholder}` yields null, not an empty map: the router's rule is
     * "first trigger that matches *and* has parameters", so a plain-phrase
     * trigger that fires must not shadow a later one that would extract.
     */
    fun params(taskLower: String): Map<String, String>? {
        val match = regex.find(taskLower) ?: return null
        if (paramNames.isEmpty()) return null
        return paramNames.zip(match.groupValues.drop(1)).toMap()
    }

    companion object {
        /**
         * Triggers are command phrases, not regex exercises. A pattern past
         * this length is refused: it runs against a task string, and no
         * real command needs two hundred characters of pattern.
         */
        const val MAX_LENGTH = 200

        private val PLACEHOLDER = Regex("""\{\w+\}""")
        private val PARAM_NAME = Regex("""\{(\w+)\}""")

        /**
         * The exponential-backtracking shape: a quantified group whose own
         * body carries a quantifier — `(a+)+`, `(a*)*`, `(a{1,2})+`, and
         * nested forms such as `(打开(.+))+` or `((a+)+)+`. Java's regex
         * engine has no match timeout, so the only defence is refusing the
         * pattern.
         *
         * Checked on the substituted text because that is what actually gets
         * compiled. Substitution can only *add* the shape — `{app}` becomes
         * `(.+)`, giving a body a `+` it did not have — and can never remove
         * a quantifier character the source already carried. So scanning the
         * substituted text over-refuses (`(a{app})+` is a harmless source
         * pattern that becomes the dangerous `(a(.+))+`) but never
         * under-refuses. A bounded group like `(ab){2}` stays legal: its body
         * is quantifier-free, so there is nothing to backtrack over.
         */
        private val BODY_QUANTIFIERS = charArrayOf('+', '*', '}')
        private val GROUP_QUANTIFIERS = charArrayOf('+', '*', '{')

        /**
         * True when [text] contains a quantified group whose body itself
         * carries a quantifier.
         *
         * Walks parens with a depth counter rather than a regex, because a
         * regex can only see a fixed number of nesting levels and only the
         * character immediately after a match. Both limits are exploitable:
         * a group nested three deep is invisible to a two-level pattern, and
         * `x((a+)+)y` — the same catastrophic shape as `((a+)+)+` with
         * wrapper text — escapes a check that only looks at what follows a
         * top-level group. Here every group at every depth is examined, so
         * the wrapper cannot hide the inner one.
         *
         * `?` is deliberately absent from [GROUP_QUANTIFIERS]: an optional
         * group cannot backtrack exponentially, and recipes legitimately
         * write `go (back|home)? please`.
         */
        private fun hasNestedQuantifier(text: String): Boolean {
            // Open-paren index per depth level; the innermost is at the top.
            val openStack = ArrayDeque<Int>()
            for ((i, c) in text.withIndex()) {
                when (c) {
                    '(' -> openStack.addLast(i)
                    ')' -> {
                        val open = openStack.removeLastOrNull() ?: continue
                        // Body is everything between this pair of parens.
                        var bodyHasQuantifier = false
                        for (j in open + 1 until i) {
                            if (text[j] in BODY_QUANTIFIERS) {
                                bodyHasQuantifier = true
                                break
                            }
                        }
                        if (!bodyHasQuantifier) continue
                        // The char right after the group decides whether it
                        // is quantified. End of text is not a quantifier.
                        val after = if (i + 1 < text.length) text[i + 1] else ' '
                        if (after in GROUP_QUANTIFIERS) return true
                    }
                }
            }
            return false
        }

        /**
         * Compiles [pattern] into a trigger, or returns null when it is
         * refused — over [MAX_LENGTH], a nested quantifier, or not valid
         * regex at all. The caller logs the refusal; a skill keeps whatever
         * other triggers it has.
         */
        fun compile(pattern: String): SkillTrigger? {
            if (pattern.length > MAX_LENGTH) return null
            val regexText = pattern.lowercase().replace(PLACEHOLDER, "(.+)")
            if (hasNestedQuantifier(regexText)) return null
            val regex = try {
                Regex(regexText)
            } catch (_: RuntimeException) {
                return null
            }
            val names = PARAM_NAME.findAll(pattern).map { it.groupValues[1] }.toList()
            return SkillTrigger(pattern, regex, names)
        }
    }
}
