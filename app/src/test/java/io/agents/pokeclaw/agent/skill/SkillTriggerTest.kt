// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [SkillTrigger] and the compiled-trigger side of [SkillRegistry] —
 * the F7 fix.
 *
 * Before this, `findByTrigger` compiled every trigger pattern of every
 * skill on every routing attempt (`Regex(...)` inside the `any {}`), and
 * user recipes — YAML on the external files dir, triggers documented as
 * regex — could park the matching thread with a catastrophic-backtracking
 * pattern, because Java's regex engine has no match timeout. The fix
 * compiles once at registration and refuses the dangerous shapes there.
 *
 * The matching contract itself is unchanged and pinned here too: `{name}`
 * becomes `(.+)`, both sides lowercase, the first trigger that matches
 * *and* carries placeholders wins, and a plain-phrase trigger that fires
 * yields no parameters rather than shadowing a later one.
 *
 * The registry is a global singleton, so every case cleans up in [After]
 * and skills are registered under unique ids.
 */
class SkillTriggerTest {

    @After
    fun tearDown() {
        SkillRegistry.clear()
    }

    private fun skill(id: String, vararg triggers: String, source: SkillSource = SkillSource.BUILTIN) =
        Skill(
            id = id,
            name = id,
            description = "test skill",
            category = SkillCategory.GENERAL,
            steps = listOf(SkillStep("wait")),
            triggerPatterns = triggers.toList(),
            source = source
        )

    // ---- compile: the shapes a recipe may and may not use ----

    @Test
    fun `a placeholder phrase compiles, matches, and extracts params`() {
        val trigger = SkillTrigger.compile("open {app} and search {query}")
        assertNotNull(trigger)
        assertTrue(trigger!!.matches("open youtube and search cats"))
        assertEquals(
            mapOf("app" to "youtube", "query" to "cats"),
            trigger.params("open youtube and search cats")
        )
    }

    @Test
    fun `matching lowercases both sides like the inline version did`() {
        // The registry lowercases the task; the pattern was lowercased at
        // compile. End-to-end through the registry, that is this case.
        SkillRegistry.register(skill("case", "SUBMIT Form"))
        assertEquals("case", SkillRegistry.findByTrigger("submit form")?.id)
    }

    @Test
    fun `alternation and optional groups stay legal`() {
        // Compile-level only: mixing hand-written capture groups with
        // placeholders mis-binds parameters (a pre-existing boundary the
        // old inline extractor shared — see BACKLOG F7 note), so the param
        // contract is pinned on placeholder-only patterns below.
        assertNotNull(SkillTrigger.compile("send (message|msg) to {who}"))
        assertNotNull(SkillTrigger.compile("go (back|home)? please"))
    }

    @Test
    fun `nested quantifiers are refused`() {
        assertNull(SkillTrigger.compile("(a+)+b"))
        assertNull(SkillTrigger.compile("(a*)*"))
        assertNull(SkillTrigger.compile("(a{1,2})+"))
        assertNull(SkillTrigger.compile("((a+)+)+"))
        // The recipe-realistic shape: a quantifier on a group that wraps a
        // placeholder. Without the + it is linear and stays legal.
        assertNull(SkillTrigger.compile("(打开{app})+并搜索"))
        assertNotNull(SkillTrigger.compile("(打开{app})并搜索"))
    }

    @Test
    fun `wrapper text cannot hide the dangerous group`() {
        // The shape a two-level regex could not see: a group nested three
        // deep, and the same shape with literal text around it. Both are
        // catastrophic; both must be refused.
        assertNull(SkillTrigger.compile("(((a+)+)+)+"))
        assertNull(SkillTrigger.compile("x((a+)+)y"))
        assertNull(SkillTrigger.compile("x(a+)+y"))
        assertNull(SkillTrigger.compile("please (open (.+))+ now"))
        // A group whose body is clean stays legal however it is wrapped.
        assertNotNull(SkillTrigger.compile("x(ab)+y"))
        assertNotNull(SkillTrigger.compile("go (back|home)? please"))
    }

    @Test
    fun `patterns that only look dangerous are still allowed`() {
        // Two placeholder groups side by side are quadratic, not exponential,
        // and a quantified literal group has no quantifier in its body.
        assertNotNull(SkillTrigger.compile("open {app} search {query}"))
        assertNotNull(SkillTrigger.compile("(ab)+ now"))
        assertNotNull(SkillTrigger.compile("go (back|home)? please"))
    }

    @Test
    fun `an over-long pattern is refused and the boundary is exact`() {
        assertNotNull(SkillTrigger.compile("a".repeat(SkillTrigger.MAX_LENGTH)))
        assertNull(SkillTrigger.compile("a".repeat(SkillTrigger.MAX_LENGTH + 1)))
    }

    @Test
    fun `invalid regex is refused instead of throwing`() {
        assertNull(SkillTrigger.compile("("))
        assertNull(SkillTrigger.compile("a)b"))
        assertNull(SkillTrigger.compile("a**"))
    }

    // ---- registry: one bad trigger must not take the skill down ----

    @Test
    fun `a skill keeps its valid triggers when one is refused`() {
        SkillRegistry.register(skill("mixed", "submit", "(a+)+b"))
        assertEquals("mixed", SkillRegistry.findByTrigger("submit")?.id)
        // Pre-fix this call compiled and ran the evil pattern; it now finds
        // nothing, immediately.
        assertNull(SkillRegistry.findByTrigger("aaaaab"))
    }

    @Test
    fun `other skills still match after a refusal`() {
        SkillRegistry.register(skill("evil", "(a+)+b"))
        SkillRegistry.register(skill("good", "go back"))
        assertEquals("good", SkillRegistry.findByTrigger("go back")?.id)
    }

    @Test
    fun `re-registering a skill replaces its compiled triggers`() {
        SkillRegistry.register(skill("dup", "foo"))
        SkillRegistry.register(skill("dup", "bar"))
        assertNull(SkillRegistry.findByTrigger("foo"))
        assertEquals("dup", SkillRegistry.findByTrigger("bar")?.id)
    }

    // ---- the matching contract the router depends on ----

    @Test
    fun `extractParams takes the first matching placeholder trigger`() {
        SkillRegistry.register(skill("params", "open {app}", "press {key}"))
        val matched = SkillRegistry.findByTrigger("press back")
        assertNotNull(matched)
        assertEquals(mapOf("key" to "back"), SkillRegistry.extractParams(matched!!, "press back"))
    }

    @Test
    fun `a plain-phrase trigger matches the skill but yields no params`() {
        SkillRegistry.register(skill("plain", "go back", "open {app}"))
        val matched = SkillRegistry.findByTrigger("go back")
        assertNotNull(matched)
        assertEquals(emptyMap<String, String>(), SkillRegistry.extractParams(matched!!, "go back"))
        // The phrase trigger must not shadow the placeholder one either.
        assertEquals(
            mapOf("app" to "maps"),
            SkillRegistry.extractParams(SkillRegistry.findByTrigger("open maps")!!, "open maps")
        )
    }

    @Test
    fun `compound tasks still skip skill matching`() {
        SkillRegistry.register(skill("compound", "open {app}"))
        assertNull(SkillRegistry.findByTrigger("open youtube and search cats"))
    }

    @Test
    fun `unregistering drops the compiled triggers`() {
        SkillRegistry.register(skill("gone", "go back"))
        SkillRegistry.unregister("gone")
        assertNull(SkillRegistry.findByTrigger("go back"))
    }

    @Test
    fun `unregisterBySource drops only that source`() {
        SkillRegistry.register(skill("builtin", "go back"))
        SkillRegistry.register(skill("user", "open {app}", source = SkillSource.USER))
        SkillRegistry.unregisterBySource(SkillSource.USER)
        assertEquals("builtin", SkillRegistry.findByTrigger("go back")?.id)
        assertNull(SkillRegistry.findByTrigger("open maps"))
    }
}
