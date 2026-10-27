// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The effort gear's pure mapping: stored key → level → agent-loop round budget.
 *
 * The gear is the composer's 高努力 pill after it stopped being a dead pill.
 * What makes it real is [EffortLevel.maxIterationsOf] feeding
 * `AgentConfig.maxIterations`, so the two things worth pinning are the
 * key normalisation (what comes back from MMKV / the JS bridge) and the
 * monotonic round budgets (a higher gear must never mean fewer rounds).
 */
class EffortLevelTest {

    @Test
    fun `stored keys round-trip`() {
        EffortLevel.entries.forEach { level ->
            assertEquals(level, EffortLevel.fromKey(level.key))
        }
    }

    @Test
    fun `keys are case and whitespace insensitive`() {
        assertEquals(EffortLevel.LOW, EffortLevel.fromKey("low"))
        assertEquals(EffortLevel.MAX, EffortLevel.fromKey("  MaX  "))
    }

    @Test
    fun `unknown and blank keys fall back to the default`() {
        assertEquals(EffortLevel.DEFAULT, EffortLevel.fromKey("ultra"))
        assertEquals(EffortLevel.DEFAULT, EffortLevel.fromKey(""))
        assertEquals(EffortLevel.DEFAULT, EffortLevel.fromKey(null))
        // The default is the pre-gear behaviour: 60 rounds.
        assertEquals(60, EffortLevel.DEFAULT.maxIterations)
    }

    @Test
    fun `round budgets rise monotonically with the gear`() {
        val budgets = EffortLevel.entries.map { it.maxIterations }
        budgets.zipWithNext { lower, higher ->
            assertTrue("$lower must be < $higher", lower < higher)
        }
        assertEquals(listOf(15, 30, 60, 150), budgets)
    }

    @Test
    fun `maxIterationsOf matches the level table`() {
        EffortLevel.entries.forEach { level ->
            assertEquals(level.maxIterations, EffortLevel.maxIterationsOf(level))
        }
    }
}
