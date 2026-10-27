package io.agents.nailongclaw.agent.skill

import io.agents.nailongclaw.tool.BaseTool
import io.agents.nailongclaw.tool.ToolParameter
import io.agents.nailongclaw.tool.ToolRegistry
import io.agents.nailongclaw.tool.ToolResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the per-tool callbacks [SkillExecutor] hands to the task stream —
 * the seam that makes skill steps visible as tool cards / memory chips on
 * every chat surface.
 *
 * Before 2026-10-24 the executor only reported step progress, so a skill
 * running real tools through the same `ToolRegistry.executeTool` the agent
 * loop uses produced no `ToolAction` / `ToolResult` events at all: the UI
 * showed "Step 1/2 …" lines and never what the tools did. These cases pin
 * the contract the task stream relies on: one call event and one result
 * event per attempt, in step order, carrying the registry tool name and the
 * real outcome (including the retry's second result).
 *
 * The registry is a global singleton, so the stubs are registered under
 * unique names and removed in @After to stay out of other tests' way.
 */
class SkillExecutorTest {

    private class StubTool(
        private val name: String,
        private val outcomes: List<ToolResult>,
    ) : BaseTool() {
        private var calls = 0
        val callLog = mutableListOf<Map<String, Any>>()
        override fun getName() = name
        override fun getDescriptionEN() = "stub"
        override fun getDescriptionCN() = "stub"
        override fun getParameters() = listOf(ToolParameter("msg", "string", "stub", false))
        override fun execute(params: Map<String, Any>): ToolResult {
            callLog += params
            return outcomes[calls.coerceAtMost(outcomes.size - 1)].also { calls++ }
        }
    }

    private val registered = mutableListOf<String>()

    private fun install(tool: BaseTool) {
        ToolRegistry.register(tool)
        registered += tool.getName()
    }

    @After
    fun cleanup() {
        registered.forEach { ToolRegistry.unregister(it) }
    }

    private fun skill(vararg steps: SkillStep) = Skill(
        id = "test_skill",
        name = "Test skill",
        description = "test",
        category = SkillCategory.GENERAL,
        steps = steps.toList(),
    )

    @Test
    fun `each step emits one call and one result event in order`() {
        val ok = StubTool("h28_stub_ok", listOf(ToolResult.success("saved")))
        install(ok)
        val executor = SkillExecutor()
        val calls = mutableListOf<String>()
        val results = mutableListOf<Pair<String, ToolResult>>()

        executor.execute(
            skill(
                SkillStep(toolName = "h28_stub_ok", params = mapOf("msg" to "a"), retries = 1),
                SkillStep(toolName = "h28_stub_ok", params = mapOf("msg" to "b"), retries = 1),
            ),
            emptyMap(),
            onToolCall = { calls += it },
            onToolResult = { name, result -> results += name to result },
        )

        assertEquals(listOf("h28_stub_ok", "h28_stub_ok"), calls)
        assertEquals(2, results.size)
        assertTrue(results.all { it.second.isSuccess })
        // The resolved params reached the tool both times — the events ride
        // real executions, not a parallel summary.
        assertEquals(listOf("a"), ok.callLog[0].values.toList())
        assertEquals(listOf("b"), ok.callLog[1].values.toList())
    }

    @Test
    fun `a retried step emits one pair per attempt`() {
        val flaky = StubTool(
            "h28_stub_flaky",
            listOf(ToolResult.error("transient"), ToolResult.success("second try")),
        )
        install(flaky)
        val results = mutableListOf<Pair<String, ToolResult>>()

        val result = SkillExecutor().execute(
            skill(SkillStep(toolName = "h28_stub_flaky", retries = 2)),
            emptyMap(),
            onToolResult = { name, r -> results += name to r },
        )

        assertTrue(result.success)
        assertEquals(2, results.size)
        assertFalse(results[0].second.isSuccess)
        assertTrue(results[1].second.isSuccess)
    }

    @Test
    fun `an exhausted non-optional step still emits its failure result`() {
        val dead = StubTool("h28_stub_dead", listOf(ToolResult.error("no")))
        install(dead)
        val results = mutableListOf<Pair<String, ToolResult>>()

        val result = SkillExecutor().execute(
            skill(SkillStep(toolName = "h28_stub_dead", retries = 1)),
            emptyMap(),
            onToolResult = { name, r -> results += name to r },
        )

        assertFalse(result.success)
        assertEquals(1, results.size)
        assertFalse(results[0].second.isSuccess)
    }

    @Test
    fun `placeholders resolve into the tool params`() {
        val ok = StubTool("h28_stub_params", listOf(ToolResult.success("ok")))
        install(ok)

        SkillExecutor().execute(
            skill(SkillStep(toolName = "h28_stub_params", params = mapOf("msg" to "{msg}"), retries = 1)),
            mapOf("msg" to "H28"),
        )

        assertEquals(listOf("H28"), ok.callLog.single().values.toList())
    }
}
