// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool

import io.agents.pokeclaw.agent.knowledge.*
import io.agents.pokeclaw.agent.memory.MemoryForgetTool
import io.agents.pokeclaw.agent.memory.MemoryRecallTool
import io.agents.pokeclaw.agent.memory.MemorySaveTool
import io.agents.pokeclaw.tool.impl.*
import io.agents.pokeclaw.tool.impl.mobile.*
import io.agents.pokeclaw.tool.impl.tv.*

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
            io.agents.pokeclaw.utils.XLog.w(
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
            val prompt = (tool as? io.agents.pokeclaw.agent.mcp.McpTool)
                ?.describeForConfirmation(params)
            if (prompt == null) {
                io.agents.pokeclaw.utils.XLog.e(
                    "ToolRegistry",
                    "Refused '$name': an MCP tool that is not a registered McpTool cannot be confirmed"
                )
                return ToolResult.error(
                    "Refused '$name': an unrecognised MCP tool cannot be confirmed and was not run."
                )
            }
            val verdict = io.agents.pokeclaw.agent.mcp.McpConfirmationCoordinator
                .awaitDecision(prompt)
            if (!verdict.allowed) {
                io.agents.pokeclaw.utils.XLog.w(
                    "ToolRegistry",
                    "MCP confirmation refused '$name': ${verdict.outcome}"
                )
                return ToolResult.error(verdict.reason)
            }
        }

        return try {
            val result = tool.executeWithWaitAfter(params)
            // Feed the "save as skill" capture pipeline (no-op unless armed by the agent loop)
            io.agents.pokeclaw.agent.skill.ToolCallRecorder.record(name, params, result)
            result
        } catch (e: Exception) {
            io.agents.pokeclaw.utils.XLog.e("ToolRegistry", "Tool '$name' execution failed with params=$params", e)
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }

    /**
     * Channel the running task arrived on, or null when the task is local /
     * not associated with a session.
     *
     * Isolated here so the policy can be exercised without an app instance.
     */
    internal var sourceChannelProvider: () -> io.agents.pokeclaw.channel.Channel? = {
        try {
            io.agents.pokeclaw.appViewModel.inProgressTaskChannel
        } catch (_: Throwable) {
            // Before Application.onCreate there is no view model; treat as local.
            null
        }
    }

    private fun currentSourceChannel(): io.agents.pokeclaw.channel.Channel? {
        return try {
            sourceChannelProvider()
        } catch (_: Throwable) {
            null
        }
    }
}
