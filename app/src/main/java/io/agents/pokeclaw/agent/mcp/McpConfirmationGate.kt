// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

/**
 * One MCP tool call asking a human for permission to run.
 *
 * [displayName] is the remote tool's own name (`echo`), [toolName] the name it
 * was registered under (`mcp_<server>_echo`) — the card shows the former, the
 * allow-always memory keys on the latter, because that is what a later call
 * arrives with.
 *
 * [risk] is the tier the confirmation is shown at, from
 * [io.agents.pokeclaw.tool.ToolRiskPolicy] — it decides what the card offers
 * (a destructive call has no 始终允许) and the gate enforces the same rule on
 * whatever the surface submits back. Defaults to WRITE only so pre-tier
 * call sites keep compiling; the real value is always filled in by
 * [McpTool.describeForConfirmation].
 */
data class McpConfirmationRequest(
    val toolName: String,
    val displayName: String,
    val serverName: String,
    val description: String,
    val paramsPreview: String,
    val risk: io.agents.pokeclaw.tool.ToolRiskPolicy.Risk =
        io.agents.pokeclaw.tool.ToolRiskPolicy.Risk.WRITE,
)

/**
 * Decision policy for MCP tool confirmations — pure state machine, no threads,
 * no Android types, so every transition is unit-testable without mocks.
 *
 * ## Why this exists
 *
 * MCP tools are third-party code that the *model* decides to call. Before this
 * gate, an MCP call ran the moment the agent loop reached it: the only thing
 * standing between "a remote server suggested it" and "the phone did it" was
 * [io.agents.pokeclaw.tool.ToolRiskPolicy], which refuses high-impact tools for
 * remote *channels* and deliberately stops there — "a user-facing per-tool
 * confirmation dialog" was explicitly out of scope for F2 because a remote task
 * has no foreground activity to show one on.
 *
 * That reasoning holds for remote channels and is exactly why the interactive
 * path needs the other half: a task started in the app *does* have a human in
 * front of the screen, so the model asking a stranger's server to read saved
 * personal data should stop and ask. This gate is that stop.
 *
 * ## Design rule: fail closed
 *
 * Every path that cannot reach a human denies: no interactive surface at all,
 * a surface that is not visible (the app is backgrounded mid-task), a second
 * request while one is already waiting, and a wait that nobody answers inside
 * the timeout. The tool does not run and the model is told why, so the agent
 * loop can finish honestly instead of hanging.
 *
 * The one exception is an explicit "always allow", which is remembered for the
 * rest of the process and deliberately **not** persisted: a stored yes would
 * outlive the conversation that earned it.
 *
 * Not thread-safe on its own — [McpConfirmationCoordinator] serialises access
 * and owns the waiting.
 */
class McpConfirmationGate {

    /** What the user picked. Mirrors the three buttons on the confirmation card. */
    enum class Decision {
        /** Run this one call, ask again next time. */
        ALLOW_ONCE,

        /** Run this call and stop asking for this tool in this process. */
        ALLOW_ALWAYS,

        /** Do not run it. */
        DENY,
    }

    /** How a request resolved, including the ways it resolved without an answer. */
    enum class Outcome {
        /** Waiting for the user. */
        ASK,

        /** Allowed by a previous "always allow". */
        ALLOW_REMEMBERED,

        ALLOW_ONCE,
        ALLOW_ALWAYS,

        /**
         * Allowed without asking because the user turned on 自动允许 in
         * 模型与任务 settings. Distinct from [ALLOW_ALWAYS]: that one is a
         * per-tool yes given on a card, this one is a blanket policy applied
         * before any card is drawn.
         */
        AUTO_ALLOWED,

        /** The user tapped 本次拒绝. */
        DENY_BY_USER,

        DENY_TIMED_OUT,
        DENY_CANCELLED,

        /** No interactive PokeClaw surface exists in this process. */
        DENY_NO_SURFACE,

        /** A surface exists but is not in the foreground, so its card is unseen. */
        DENY_NOT_VISIBLE,

        /** Another confirmation is already waiting; one at a time. */
        DENY_BUSY,
    }

    /**
     * What the caller (and the model) is told.
     *
     * Carries [request] so a surface can record the outcome without having
     * remembered what it was showing.
     */
    data class Verdict(
        val allowed: Boolean,
        val outcome: Outcome,
        val reason: String,
        val request: McpConfirmationRequest,
    )

    private val alwaysAllowed = LinkedHashSet<String>()
    private var pending: McpConfirmationRequest? = null

    /**
     * Register a request and say what should happen next.
     *
     * Only [Outcome.ASK] leaves a pending request behind; every other outcome
     * is terminal for this call and leaves the gate idle, so a denied tool
     * never blocks the next one.
     */
    @Synchronized
    fun request(
        request: McpConfirmationRequest,
        surfaceAttached: Boolean,
        surfaceVisible: Boolean,
    ): Outcome {
        if (request.toolName in alwaysAllowed) return Outcome.ALLOW_REMEMBERED
        if (!surfaceAttached) return Outcome.DENY_NO_SURFACE
        if (!surfaceVisible) return Outcome.DENY_NOT_VISIBLE
        if (pending != null) return Outcome.DENY_BUSY
        pending = request
        return Outcome.ASK
    }

    /** Resolve the pending request with the user's choice. No-op when idle. */
    @Synchronized
    fun resolve(decision: Decision): Outcome? {
        val current = pending ?: return null
        pending = null
        return when (decision) {
            Decision.ALLOW_ONCE -> Outcome.ALLOW_ONCE
            Decision.ALLOW_ALWAYS -> {
                // Destructive calls never earn a remembered yes — each run is
                // approved on its own card (M2). A surface that offers the
                // button anyway (or one that was never supposed to) gets its
                // blanket yes downgraded to a single one: the run the user
                // meant to allow proceeds, the memory does not exist.
                if (current.risk == io.agents.pokeclaw.tool.ToolRiskPolicy.Risk.DESTRUCTIVE) {
                    Outcome.ALLOW_ONCE
                } else {
                    alwaysAllowed += current.toolName
                    Outcome.ALLOW_ALWAYS
                }
            }
            Decision.DENY -> Outcome.DENY_BY_USER
        }
    }

    /** The wait expired. No-op when idle. */
    @Synchronized
    fun timeout(): Outcome? {
        if (pending == null) return null
        pending = null
        return Outcome.DENY_TIMED_OUT
    }

    /** The waiting surface went away. No-op when idle. */
    @Synchronized
    fun cancel(): Outcome? {
        if (pending == null) return null
        pending = null
        return Outcome.DENY_CANCELLED
    }

    @Synchronized
    fun pendingRequest(): McpConfirmationRequest? = pending

    @Synchronized
    fun isAlwaysAllowed(toolName: String): Boolean = toolName in alwaysAllowed

    /** Test seam: drop the remembered allows (process-lifetime by design). */
    @Synchronized
    fun reset() {
        alwaysAllowed.clear()
        pending = null
    }

    companion object {

        /** True when the tool may run. Everything else is a refusal. */
        fun allows(outcome: Outcome): Boolean = when (outcome) {
            Outcome.ALLOW_REMEMBERED,
            Outcome.ALLOW_ONCE,
            Outcome.ALLOW_ALWAYS,
            Outcome.AUTO_ALLOWED,
            -> true

            else -> false
        }

        /**
         * Transcript line for a request that is now waiting.
         *
         * Written before the card appears so a task that dies mid-wait still
         * shows what it was waiting for.
         */
        fun pendingNote(request: McpConfirmationRequest): String =
            "⏳ 等待确认：${request.displayName}（MCP:${request.serverName}）"

        /**
         * Transcript line for how the wait ended.
         *
         * Kept next to the gate rather than in each surface so the Compose
         * dialog and the WebView card cannot drift apart in what they record.
         */
        fun transcriptNote(verdict: Verdict): String {
            val request = verdict.request
            val label = "${request.displayName}（MCP:${request.serverName}）"
            return when (verdict.outcome) {
                Outcome.ALLOW_ONCE -> "✓ 已允许一次：$label"
                Outcome.ALLOW_ALWAYS -> "✓ 已始终允许：$label（本次运行内不再询问）"
                Outcome.ALLOW_REMEMBERED -> "✓ 已始终允许：$label"
                Outcome.AUTO_ALLOWED -> "✓ 已自动允许：$label（设置 · 敏感操作 = 自动允许）"
                Outcome.DENY_BY_USER -> "✗ 已拒绝：$label，工具未执行"
                Outcome.DENY_TIMED_OUT -> "✗ 超时未确认：$label，工具未执行"
                Outcome.DENY_CANCELLED -> "✗ 确认被取消：$label，工具未执行"
                Outcome.DENY_BUSY -> "✗ 未确认：$label，另一次确认还在等待"
                Outcome.DENY_NO_SURFACE -> "✗ 未确认：$label，当前没有可确认的界面"
                Outcome.DENY_NOT_VISIBLE -> "✗ 未确认：$label，PokeClaw 在后台、确认卡不可见"
                Outcome.ASK -> "⏳ 等待确认：$label"
            }
        }

        /**
         * Why the tool did not run, phrased for the model that asked for it.
         *
         * English on purpose: this string is read by the agent loop, and the
         * transcript line above is what the user reads. Same split as
         * [io.agents.pokeclaw.tool.ToolRiskPolicy.blockReason].
         */
        fun denialReason(outcome: Outcome, request: McpConfirmationRequest, timeoutMs: Long): String =
            when (outcome) {
                Outcome.DENY_BY_USER ->
                    "The user declined MCP tool '${request.displayName}' from server " +
                        "'${request.serverName}'. It was not run. Do not retry it in this task; " +
                        "tell the user it was not approved."

                Outcome.DENY_TIMED_OUT ->
                    "The user did not answer the confirmation for MCP tool " +
                        "'${request.displayName}' from server '${request.serverName}' within " +
                        "${timeoutMs / 1000}s, so it was not run. Ask the user to approve it " +
                        "and try again."

                Outcome.DENY_CANCELLED ->
                    "The confirmation for MCP tool '${request.displayName}' from server " +
                        "'${request.serverName}' was cancelled (the PokeClaw screen that asked " +
                        "went away), so it was not run."

                Outcome.DENY_NO_SURFACE ->
                    "MCP tool '${request.displayName}' from server '${request.serverName}' " +
                        "was not run because no interactive PokeClaw screen is open. Start " +
                        "the task from the app so the user can approve it."

                Outcome.DENY_NOT_VISIBLE ->
                    "MCP tool '${request.displayName}' from server '${request.serverName}' " +
                        "was not run because PokeClaw is in the background and its " +
                        "confirmation card is not visible. Bring PokeClaw to the foreground " +
                        "and try again."

                Outcome.DENY_BUSY ->
                    "Another MCP tool confirmation is still waiting for the user, so " +
                        "'${request.displayName}' from '${request.serverName}' was not run. " +
                        "Wait for that one to be answered."

                else -> "MCP tool '${request.displayName}' was not run (outcome=$outcome)."
            }
    }
}
