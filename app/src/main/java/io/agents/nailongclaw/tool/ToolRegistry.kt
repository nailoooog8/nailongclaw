// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool

import io.agents.nailongclaw.agent.knowledge.*
import io.agents.nailongclaw.agent.memory.MemoryForgetTool
import io.agents.nailongclaw.agent.memory.MemoryRecallTool
import io.agents.nailongclaw.agent.memory.MemorySaveTool
import io.agents.nailongclaw.tool.impl.*
import io.agents.nailongclaw.tool.impl.mobile.*
import io.agents.nailongclaw.tool.impl.tv.*

object ToolRegistry {

    enum class DeviceType { TV, MOBILE }

    /** Third-party MCP tools, registered as `mcp_<server>_<tool>`. */
    private const val MCP_TOOL_PREFIX = "mcp_"

    private val tools = LinkedHashMap<String, BaseTool>()
    var deviceType: DeviceType = DeviceType.TV
        private set

    @JvmStatic
    fun getInstance(): ToolRegistry = this

    fun registerAllTools(type: DeviceType = DeviceType.TV) {
        deviceType = type
        tools.clear()
        registerCommonTools()
        when (type) {
            DeviceType.TV -> registerTvTools()
            DeviceType.MOBILE -> registerMobileTools()
        }
    }

    private fun registerCommonTools() {
        register(GetScreenInfoTool())
        register(FindNodeInfoTool())
        register(InputTextTool())
        register(SystemKeyTool())
        register(OpenAppTool())
        register(GetInstalledAppsTool())
        register(TakeScreenshotTool())
        register(WaitTool())
        register(RepeatActionsTool())
        register(ClipboardTool())
        register(SendFileTool())
        register(GetDeviceInfoTool())
        register(GetNotificationsTool())
        register(ReplyViaNotificationTool())
        register(MakeCallTool())
        register(FinishTool())
        // Knowledge Base tools — shared vault available in all modes
        register(KbWriteTool())
        register(KbReadTool())
        register(KbSearchTool())
        register(KbAppendTool())
        register(KbAddTodoTool())
        // Long-term memory bank — what the agent carries between sessions
        register(MemorySaveTool())
        register(MemoryRecallTool())
        register(MemoryForgetTool())
        // Web information retrieval — knowledge questions without touching the UI
        register(WebSearchTool())
        // Device calendar — schedule questions without opening the app,
        // and the write tool that puts an event on it
        register(io.agents.nailongclaw.agent.calendar.CalendarReadTool())
        register(io.agents.nailongclaw.agent.calendar.CalendarCreateTool())
        // Scheduled tasks — durable timers, honest queue, calendar anchor
        register(ScheduleTaskTool())
        register(ListScheduledTasksTool())
        register(CancelScheduledTaskTool())
    }

    private fun registerTvTools() {
        register(DpadUpTool())
        register(DpadDownTool())
        register(DpadLeftTool())
        register(DpadRightTool())
        register(DpadCenterTool())
        register(VolumeUpTool())
        register(VolumeDownTool())
        register(PressMenuTool())
        register(PressPowerTool())
    }

    private fun registerMobileTools() {
        register(TapTool())
        register(TapNodeTool())
        register(LongPressTool())
        register(SwipeTool())
        register(ScrollToFindTool())
        register(FindAndTapTool())
        register(ReadConversationTool())
        register(SendMessageTool())
        register(AutoReplyTool())
    }

    fun register(tool: BaseTool) {
        tools[tool.getName()] = tool
    }

    fun unregister(name: String): Boolean = tools.remove(name) != null

    /** Removes every tool whose registered name starts with [prefix]; returns how many were removed. */
    fun unregisterByPrefix(prefix: String): Int {
        val removed = tools.keys.filter { it.startsWith(prefix) }
        for (name in removed) tools.remove(name)
        return removed.size
    }

    fun getTool(name: String): BaseTool? = tools[name]

    fun getDisplayName(name: String): String = tools[name]?.getDisplayName() ?: name

    fun getAllTools(): List<BaseTool> = tools.values.toList()

    fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")

        // Second defence layer. A task can arrive from a channel or an
        // automation broadcast with nobody watching the screen, so tools that
        // act on the world are refused there. See ToolRiskPolicy for why this
        // is separate from the sender whitelist.
        val sourceChannel = currentSourceChannel()
        if (ToolRiskPolicy.isBlockedForChannel(name, sourceChannel)) {
            val reason = ToolRiskPolicy.blockReason(name, sourceChannel)
            io.agents.nailongclaw.utils.XLog.w(
                "ToolRegistry",
                "Refused '$name' for source=$sourceChannel risk=${ToolRiskPolicy.riskOf(name)}"
            )
            return ToolResult.error(reason)
        }

        // Third layer, and the one that needs a human. An MCP tool is
        // third-party code that the *model* decided to call, so on the
        // interactive path it waits for the user to approve it.
        // ToolRiskPolicy already refuses these for remote channels; this
        // covers the task the user started themselves. Fail closed — no
        // visible screen, a second call while one waits, or no answer inside
        // the timeout all mean the tool does not run, and the model is told
        // why so the loop can finish honestly. See McpConfirmationGate.
        if (name.startsWith(MCP_TOOL_PREFIX)) {
            val prompt = (tool as? io.agents.nailongclaw.agent.mcp.McpTool)
                ?.describeForConfirmation(params)
            if (prompt == null) {
                io.agents.nailongclaw.utils.XLog.e(
                    "ToolRegistry",
                    "Refused '$name': an MCP tool that is not a registered McpTool cannot be confirmed"
                )
                return ToolResult.error(
                    "Refused '$name': an unrecognised MCP tool cannot be confirmed and was not run."
                )
            }
            val verdict = io.agents.nailongclaw.agent.mcp.McpConfirmationCoordinator
                .awaitDecision(prompt)
            if (!verdict.allowed) {
                io.agents.nailongclaw.utils.XLog.w(
                    "ToolRegistry",
                    "MCP confirmation refused '$name': ${verdict.outcome}"
                )
                return ToolResult.error(verdict.reason)
            }
        }

        return try {
            val result = tool.executeWithWaitAfter(params)
            // Feed the "save as skill" capture pipeline (no-op unless armed by the agent loop)
            io.agents.nailongclaw.agent.skill.ToolCallRecorder.record(name, params, result)
            result
        } catch (e: Exception) {
            // Params are redacted before they are written: a failed input_text
            // carries the user's text, a failed send_message the whole message,
            // a failed make_call the phone number. This log is on disk in every
            // build (XLog.e ignores the DEBUG flag) and DebugReportManager ships
            // it to whoever the user asks for help. See F8.
            io.agents.nailongclaw.utils.XLog.e(
                "ToolRegistry",
                "Tool '$name' execution failed with params=${redactParams(params)}",
                e
            )
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }

    /** Parameter name fragments whose value must not be written to a log. */
    private val SENSITIVE_PARAM_MARKERS = listOf(
        "text", "message", "content", "body", "phone", "number", "contact",
        "password", "passwd", "pin", "code", "otp", "token", "key", "secret",
        "query", "prompt", "subject", "note",
    )

    /**
     * Renders [params] for a log line with credential-ish values replaced.
     *
     * Keep-list by fragment rather than an exact allow-list of tool parameter
     * names: a new tool with a new parameter name must not silently start
     * logging user content.
     */
    internal fun redactParams(params: Map<String, Any>): String {
        if (params.isEmpty()) return "{}"
        return params.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val lower = key.lowercase()
            val sensitive = SENSITIVE_PARAM_MARKERS.any { lower.contains(it) }
            if (sensitive) "$key=<redacted:${value.javaClass.simpleName}>" else "$key=$value"
        }
    }

    /**
     * Channel the running task arrived on, or null when the task is local /
     * not associated with a session.
     *
     * Isolated here so the policy can be exercised without an app instance.
     */
    internal var sourceChannelProvider: () -> io.agents.nailongclaw.channel.Channel? = {
        try {
            io.agents.nailongclaw.appViewModel.inProgressTaskChannel
        } catch (_: Throwable) {
            // Before Application.onCreate there is no view model; treat as local.
            null
        }
    }

    private fun currentSourceChannel(): io.agents.nailongclaw.channel.Channel? {
        return try {
            sourceChannelProvider()
        } catch (_: Throwable) {
            null
        }
    }
}
