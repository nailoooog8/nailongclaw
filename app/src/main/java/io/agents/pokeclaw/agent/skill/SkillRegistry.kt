// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

import io.agents.pokeclaw.utils.XLog

/**
 * Registry of built-in and user-defined skills.
 * Skills are loaded at app startup and matched by trigger patterns.
 */
object SkillRegistry {

    private const val TAG = "SkillRegistry"
    private val skills = mutableMapOf<String, Skill>()

    /**
     * Trigger patterns compiled at registration (F7), keyed by skill id and
     * dropped wherever the skill itself is dropped, so matching never
     * compiles a pattern at route time.
     */
    private val compiledTriggers = mutableMapOf<String, List<SkillTrigger>>()

    /**
     * Register a skill. Replaces existing skill with same ID.
     *
     * Trigger patterns are compiled here, once, and refused when they are
     * dangerous (see [SkillTrigger.compile]) — a recipe with one bad
     * trigger keeps its other triggers and logs why the bad one went.
     */
    fun register(skill: Skill) {
        skills[skill.id] = skill
        compiledTriggers[skill.id] = skill.triggerPatterns.mapNotNull { pattern ->
            SkillTrigger.compile(pattern) ?: run {
                XLog.w(
                    TAG,
                    "Skill '${skill.id}': trigger refused (over ${SkillTrigger.MAX_LENGTH} chars, " +
                        "nested quantifier, or invalid regex): $pattern"
                )
                null
            }
        }
        XLog.d(TAG, "Registered skill: ${skill.id} (${skill.name})")
    }

    fun findById(id: String): Skill? = skills[id]

    fun unregister(id: String) {
        skills.remove(id)
        compiledTriggers.remove(id)
    }

    fun unregisterBySource(source: SkillSource) {
        val ids = skills.values.filter { it.source == source }.map { it.id }
        ids.forEach {
            skills.remove(it)
            compiledTriggers.remove(it)
        }
    }

    fun getAll(): List<Skill> = skills.values.toList()

    fun getByCategory(category: SkillCategory): List<Skill> =
        skills.values.filter { it.category == category }

    fun getUserFacing(): List<Skill> =
        skills.values.filter { it.userFacing }

    /**
     * Find a skill that matches a task by trigger patterns.
     * Returns null if no skill matches.
     */
    fun findByTrigger(task: String): Skill? {
        val lower = task.lowercase()
        // Compound tasks with conjunctions should go to agent loop, not skills.
        // Skills are for simple, single-action commands only.
        if (lower.contains(" and ") || lower.contains(" then ") || lower.contains(" after ")) {
            XLog.d(TAG, "Compound task detected, skipping skill matching: $task")
            return null
        }
        return skills.values.find { skill ->
            compiledTriggers[skill.id]?.any { it.matches(lower) } == true
        }
    }

    /**
     * Parameter values for [skill] out of [task], from the first trigger
     * that both matches and carries `{placeholder}`s — the same
     * first-match-wins rule the router used to run inline over the raw
     * patterns. Empty when no trigger yields parameters, which is the
     * honest answer for a skill matched by a plain-phrase trigger.
     */
    fun extractParams(skill: Skill, task: String): Map<String, String> {
        val lower = task.lowercase()
        val triggers = compiledTriggers[skill.id] ?: return emptyMap()
        for (trigger in triggers) {
            val params = trigger.params(lower)
            if (params != null) return params
        }
        return emptyMap()
    }

    /**
     * Load built-in skills. Called once at app startup.
     *
     * Only simple, context-free, app-agnostic skills belong here.
     * Complex app-specific tasks (messaging, camera, etc.) go through the agent loop.
     */
    fun loadBuiltInSkills() {
        register(BuiltInSkills.searchInApp())
        register(BuiltInSkills.submitForm())
        register(BuiltInSkills.dismissPopup())
        register(BuiltInSkills.scrollAndRead())
        register(BuiltInSkills.copyScreenText())
        register(BuiltInSkills.acceptPermission())
        register(BuiltInSkills.swipeGesture())
        register(BuiltInSkills.goBack())
        register(BuiltInSkills.waitForContent())
        XLog.i(TAG, "Loaded ${skills.size} built-in skills")
    }

    fun clear() {
        skills.clear()
        compiledTriggers.clear()
    }
}
