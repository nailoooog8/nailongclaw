// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent

import io.agents.nailongclaw.utils.KVUtils

/**
 * The composer's effort gear: how hard the agent loop may work on one task.
 *
 * The gear maps to [io.agents.nailongclaw.agent.AgentConfig.maxIterations] — the
 * one knob that changes what a task can actually accomplish:
 *
 *  - LOW    — quick, cheap tasks; bail out early rather than grinding
 *  - MEDIUM — the everyday default for multi-step tasks
 *  - HIGH   — the pre-gear behaviour (60 rounds); long tasks, app archaeology
 *  - MAX    — no round limit worth mentioning; slow and expensive on purpose
 *
 * Persisted through [KVUtils] so the Claude UI composer pill and the native
 * 模型与任务 page read the same value. The pill is presentation; this enum is
 * the behaviour.
 */
enum class EffortLevel(
    val key: String,
    val label: String,
    val maxIterations: Int,
    val hint: String,
) {
    LOW("LOW", "低", 15, "快速收尾，适合一两步的小任务"),
    MEDIUM("MEDIUM", "中", 30, "日常多步任务的默认档"),
    HIGH("HIGH", "高", 60, "长任务、需要反复观察屏幕时用"),
    MAX("MAX", "最大", 150, "不轻易放弃，慢且贵，只用于硬任务"),
    ;

    companion object {
        val DEFAULT = HIGH

        /** Stored value → level, with [DEFAULT] for unknown/blank keys. */
        fun fromKey(raw: String?): EffortLevel {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.key == normalized } ?: DEFAULT
        }

        /** The level currently configured on this device. */
        fun current(): EffortLevel = fromKey(KVUtils.getEffortLevel())

        /** The agent-loop round budget for [level]. */
        fun maxIterationsOf(level: EffortLevel): Int = level.maxIterations
    }
}
