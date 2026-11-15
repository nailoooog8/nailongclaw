// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.skill

import io.agents.nailongclaw.tool.ToolRegistry
import io.agents.nailongclaw.tool.ToolResult
import io.agents.nailongclaw.utils.XLog

/**
 * Executes skill steps deterministically using the ToolRegistry.
 *
 * Flow:
 * 1. Execute each step's tool with resolved parameters
 * 2. If a step fails and is not optional, retry up to step.retries times
 * 3. If retries exhausted, return failure with fallbackGoal for LLM agent loop
 *
 * Architecture reference:
 * - Server-side deterministic execution pattern
 * - Hardcoded step sequence pattern
 */
class SkillExecutor {

    /**
     * Execute a skill with resolved parameters.
     *
     * @param skill the skill definition
     * @param params user-provided parameter values (e.g., {"query": "cat videos"})
     * @param onProgress callback for each step progress
     * @param onToolCall callback before each attempt, carrying the tool's
     *   registry name. Optional; lets the caller surface tool calls the same
     *   way the agent loop's onToolCall does.
     * @param onToolResult callback after each attempt, carrying the tool's
     *   registry name and its result — per attempt, matching the agent loop
     *   where every call gets exactly one result. Optional.
     * @param isCancelled polled at step and attempt boundaries; when it fires
     *   the run stops with a [SkillResult.cancelled] result. Tools already in
     *   flight are not interrupted — a stop lands at the next boundary, the
     *   same contract the agent loop has for its own tool calls.
     * @return SkillResult indicating success, failure, or cancellation
     */
    fun execute(
        skill: Skill,
        params: Map<String, String>,
        onProgress: ((step: Int, total: Int, description: String) -> Unit)? = null,
        onToolCall: ((toolName: String) -> Unit)? = null,
        onToolResult: ((toolName: String, result: ToolResult) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): SkillResult {
        XLog.i(TAG, "Executing skill: ${skill.id} with params: $params")
        val totalSteps = skill.steps.size
        var stepsUsed = 0

        for ((index, step) in skill.steps.withIndex()) {
            if (stopRequested(isCancelled)) {
                XLog.i(TAG, "Skill ${skill.id} cancelled before step ${index + 1}")
                return cancelledResult(stepsUsed)
            }
            stepsUsed = index + 1
            val stepNum = index + 1
            onProgress?.invoke(stepNum, totalSteps, step.description)
            XLog.d(TAG, "Step $stepNum/$totalSteps: ${step.toolName} — ${step.description}")

            // Resolve parameter placeholders in tool params
            val resolvedParams = resolveParams(step.params, params)

            // Execute with retries
            var succeeded = false
            for (attempt in 1..step.retries) {
                if (stopRequested(isCancelled)) {
                    XLog.i(TAG, "Skill ${skill.id} cancelled before step $stepNum attempt $attempt")
                    return cancelledResult(stepsUsed)
                }
                try {
                    onToolCall?.invoke(step.toolName)
                    val toolParams = resolvedParams.mapValues { (_, v) -> v as Any }
                    val result = ToolRegistry.getInstance().executeTool(step.toolName, toolParams)
                    onToolResult?.invoke(step.toolName, result)

                    if (result.isSuccess) {
                        XLog.d(TAG, "Step $stepNum OK: ${result.data?.take(100)}")
                        succeeded = true
                        break
                    } else {
                        XLog.w(TAG, "Step $stepNum failed (attempt $attempt/${step.retries}): ${result.error}")
                        if (attempt < step.retries && !retryPause(isCancelled)) {
                            return cancelledResult(stepsUsed)
                        }
                    }
                } catch (e: Exception) {
                    onToolResult?.invoke(
                        step.toolName,
                        ToolResult.error("Tool execution failed: ${e.message}"),
                    )
                    XLog.e(TAG, "Step $stepNum exception (attempt $attempt/${step.retries})", e)
                    if (attempt < step.retries && !retryPause(isCancelled)) {
                        return cancelledResult(stepsUsed)
                    }
                }
            }

            if (!succeeded) {
                if (step.optional) {
                    XLog.i(TAG, "Step $stepNum failed but optional, continuing")
                    continue
                }
                // Non-optional step failed after retries
                XLog.w(TAG, "Skill ${skill.id} failed at step $stepNum: ${step.description}")
                return SkillResult(
                    success = false,
                    stepsUsed = stepsUsed,
                    fallbackUsed = false,
                    message = "Failed at step $stepNum: ${step.description}. " +
                              "Fallback goal: ${resolveTemplate(skill.fallbackGoal, params)}"
                )
            }
        }

        XLog.i(TAG, "Skill ${skill.id} completed in $stepsUsed steps")
        return SkillResult(
            success = true,
            stepsUsed = stepsUsed,
            message = "Skill '${skill.name}' completed successfully in $stepsUsed steps."
        )
    }

    /**
     * Pause between retry attempts. Returns false when the run should cut to
     * a cancelled result: the thread was interrupted, or a stop arrived while
     * sleeping.
     */
    private fun retryPause(isCancelled: (() -> Boolean)?): Boolean {
        try {
            Thread.sleep(RETRY_PAUSE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return !stopRequested(isCancelled)
    }

    private fun stopRequested(isCancelled: (() -> Boolean)?): Boolean = isCancelled?.invoke() ?: false

    private fun cancelledResult(stepsUsed: Int): SkillResult = SkillResult(
        success = false,
        stepsUsed = stepsUsed,
        cancelled = true,
        message = "Cancelled by user",
    )

    /**
     * Replace {param_name} placeholders in step params with actual values.
     */
    private fun resolveParams(
        stepParams: Map<String, String>,
        userParams: Map<String, String>
    ): Map<String, String> {
        return stepParams.mapValues { (_, template) ->
            resolveTemplate(template, userParams)
        }
    }

    private fun resolveTemplate(template: String, params: Map<String, String>): String {
        var result = template
        for ((key, value) in params) {
            result = result.replace("{$key}", value)
        }
        return result
    }

    companion object {
        private const val TAG = "SkillExecutor"
        private const val RETRY_PAUSE_MS = 500L
    }
}
