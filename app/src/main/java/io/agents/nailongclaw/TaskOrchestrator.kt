// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw

import io.agents.nailongclaw.agent.AgentCallback
import io.agents.nailongclaw.agent.AgentConfig
import io.agents.nailongclaw.agent.AgentService
import io.agents.nailongclaw.agent.AgentServiceFactory
import io.agents.nailongclaw.agent.PipelineRouter
import io.agents.nailongclaw.agent.TaskWatchdog
import io.agents.nailongclaw.agent.TaskWatchdogState
import io.agents.nailongclaw.agent.skill.SkillExecutor
import io.agents.nailongclaw.agent.skill.SkillRegistry
import io.agents.nailongclaw.channel.Channel
import io.agents.nailongclaw.channel.ChannelManager
import io.agents.nailongclaw.floating.FloatingCircleManager
import io.agents.nailongclaw.service.ClawAccessibilityService
import io.agents.nailongclaw.service.ForegroundService
import io.agents.nailongclaw.task.TaskRecordStore
import io.agents.nailongclaw.tool.ToolResult
import io.agents.nailongclaw.utils.XLog

/**
 * Task orchestrator — manages agent lifecycle, task locking, pipeline routing, and execution.
 */
class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit,
    private val inactivityTimeoutMs: Long = TaskWatchdogState.DEFAULT_INACTIVITY_TIMEOUT_MS,
    private val maxDurationMs: Long = TaskWatchdogState.DEFAULT_MAX_DURATION_MS,
) {
    /**
     * Typed event callback for in-app Task mode UI.
     * Called on the agent executor thread — UI must post to main thread.
     */
    var taskEventCallback: ((TaskEvent) -> Unit)? = null

    companion object {
        private const val TAG = "TaskOrchestrator"

        /** Where to return when the task did not record an origin surface. */
        private const val DEFAULT_RETURN_ACTIVITY =
            "io.agents.nailongclaw.ui.chat.ComposeChatActivity"

        /**
         * Resolve a recorded Activity class name to a Component.
         *
         * Returns null for anything that is not a real Activity class in this
         * app — a stale name from an older build, a typo, or a class that has
         * since been removed. The caller then falls back to
         * [DEFAULT_RETURN_ACTIVITY], so a bad name degrades to the historical
         * behaviour instead of throwing inside a completion callback (where the
         * task is already finished and an exception would only be logged).
         */
        private fun returnTargetComponent(className: String?): android.content.ComponentName? {
            if (className.isNullOrBlank()) return null
            return try {
                val cls = Class.forName(className)
                val isActivity = android.app.Activity::class.java.isAssignableFrom(cls)
                if (!isActivity) {
                    XLog.w(TAG, "originActivity is not an Activity: $className")
                    null
                } else {
                    android.content.ComponentName(ClawApplication.instance, cls)
                }
            } catch (e: Throwable) {
                // ClassNotFoundException on a class from a previous install, or
                // NoClassDefFoundError if the class failed to initialise.
                XLog.w(TAG, "originActivity could not be resolved: $className", e)
                null
            }
        }
    }

    private lateinit var agentService: AgentService
    private val pipelineRouter = PipelineRouter(ClawApplication.instance)
    private val skillExecutor = SkillExecutor()
    val taskSessionStore = TaskSessionStore()
    private var currentWatchdog: TaskWatchdog? = null

    /**
     * Ledger id of the task this orchestrator is driving, or null.
     *
     * Every task that passes through here gets a durable record in
     * [TaskRecordStore] — that is what closes the M1 boundary where channel
     * tasks (Telegram/Discord/WeChat/QQ/Douyin, which arrive through
     * [io.agents.nailongclaw.channel.ChannelSetup] with no UI callback at all)
     * ran to completion without leaving a trace on the task page. The id is
     * the session's messageID: one record per task, and a skill that falls
     * back to the agent loop keeps the record it already opened.
     */
    private var ledgerRecordId: String? = null

    private fun armWatchdog(channel: Channel, messageID: String): TaskWatchdog {
        currentWatchdog?.disarm()
        val watchdog = TaskWatchdog(
            inactivityTimeoutMs = inactivityTimeoutMs,
            maxDurationMs = maxDurationMs,
        ) { status ->
            val reason = when (status) {
                TaskWatchdogState.Status.INACTIVITY_TIMEOUT -> "Task stalled (inactivity timeout)"
                TaskWatchdogState.Status.MAX_DURATION_TIMEOUT -> "Task exceeded maximum duration limit"
                else -> "Task timed out"
            }
            XLog.w(TAG, "Watchdog timeout triggered: $reason")
            if (::agentService.isInitialized) {
                agentService.cancel()
            }
            val session = releaseTask()
            if (!session.isRunning) return@TaskWatchdog
            ForegroundService.resetToIdle(ClawApplication.instance)
            val targetChannel = session.channel ?: channel
            val targetMsgId = session.messageId.ifEmpty { messageID }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                emit(TaskEvent.Failed(reason))
                ChannelManager.sendMessage(targetChannel, "✗ $reason", targetMsgId)
                ChannelManager.flushMessages(targetChannel)
                FloatingCircleManager.setErrorState(reason)
                if (session.autoReturnToChat) {
                    try {
                        val service = ClawAccessibilityService.getInstance()
                        service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    } catch (e: Exception) {
                        XLog.w(TAG, "Watchdog: auto-return home failed", e)
                    }
                }
                onTaskFinished()
            }
        }
        currentWatchdog = watchdog
        return watchdog
    }

    val inProgressTaskMessageId: String
        get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel?
        get() = taskSessionStore.snapshot().channel

    // ==================== Agent Lifecycle ====================

    fun initAgent() {
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    // ==================== Task Lock ====================

    fun tryAcquireTask(
        messageId: String,
        channel: Channel,
        taskText: String = "",
        originActivity: String? = null,
    ): Boolean {
        return taskSessionStore.tryAcquire(
            messageId = messageId,
            channel = channel,
            taskText = taskText,
            originActivity = originActivity,
        )
    }

    private fun releaseTask(): TaskSessionState = taskSessionStore.release()

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    /**
     * One `TaskEvent.ToolResult` for the UI, from a tool result.
     *
     * The single formatter for both paths that execute tools — the agent
     * loop's `onToolResult` and the skill path (which used to emit nothing at
     * all, leaving skill steps invisible as tool cards on every surface).
     * Keeps the 300-char truncation and the display-name mapping in one
     * place: the truncation is what the chips parser's no-wrong-chip
     * guarantee is written against (MemoryRecallFormat).
     */
    private fun emitToolResultEvent(toolName: String, result: ToolResult) {
        val success = result.isSuccess
        var data = if (success) result.data else result.error
        if (data != null && data.length > 300) data = data.substring(0, 300) + "..."
        val displayName = io.agents.nailongclaw.tool.ToolRegistry.getInstance().getDisplayName(toolName)
        emit(TaskEvent.ToolResult(displayName, success, data ?: ""))
    }

    /**
     * One event, two ledgers.
     *
     * The UI callback the Activity registered renders it into the chat
     * transcript; the durable task record folds it into the task page. The
     * write lives here — in the orchestrator's event emission — because this
     * is the one place every task passes through, whichever door it came in
     * by. The old write point (TaskFlowController) only ever saw the app's
     * own UI tasks, so remote-channel tasks left no record at all.
     */
    private fun emit(event: TaskEvent) {
        ledgerOnEvent(event)
        taskEventCallback?.invoke(event)
    }

    /**
     * Fold [event] into the current task's ledger record, best-effort.
     *
     * A terminal record is closed: the orchestrator has paths that can emit
     * after a task ended (a tool result racing the completion callback), and
     * those are not part of the task's story. Once closed, the id is dropped
     * too — leaving it set would make the next task's `ledgerRecordId !=
     * messageID` check compare against this task's leftover.
     */
    private fun ledgerOnEvent(event: TaskEvent) {
        val id = ledgerRecordId ?: return
        try {
            if (TaskRecordStore.get(id)?.isTerminal == true) {
                ledgerRecordId = null
                return
            }
            val updated = TaskRecordStore.onEvent(id, event)
            if (updated?.isTerminal == true) ledgerRecordId = null
        } catch (e: Exception) {
            // The transcript stays the source of truth for the conversation;
            // a ledger hiccup must not eat the task's rendering.
            XLog.w(TAG, "ledger write failed for '$id'", e)
        }
    }

    /**
     * Adopt or open the ledger record for a task that is starting.
     *
     * Adopting is the normal case: [io.agents.nailongclaw.AppViewModel.startTask]
     * opens the record at its entry point, before any gate can refuse the
     * task, so by the time a task reaches here the record already exists.
     * The orchestrator only creates one itself for the paths that call
     * `startNewTask` directly and never pass through that entry point —
     * [io.agents.nailongclaw.channel.ChannelSetup] among them.
     *
     * [taskText] is what the task page calls the task, which is not always
     * the prompt: a scheduled task runs a built envelope (calendar anchor,
     * fence, delivery instructions) and passes its original request instead.
     */
    private fun startLedgerRecord(id: String, taskText: String, channel: Channel, originActivity: String?) {
        if (TaskRecordStore.get(id) != null) {
            // Already opened at the entry point. Adopting it keeps this
            // task's events on its own record.
            ledgerRecordId = id
            return
        }
        val record = try {
            TaskRecordStore.start(id = id, taskText = taskText, channel = channel, originActivity = originActivity)
        } catch (e: Exception) {
            XLog.w(TAG, "ledger start failed for '$id'", e)
            null
        }
        if (record == null) {
            // A genuine collision: some other task already owns this id, and
            // TaskRecordStore refused to overwrite it. Adopting anyway would
            // fold this task's whole event stream into that record — and if
            // it is terminal, every event would be silently dropped, leaving
            // this task invisible on the task page while it runs normally.
            // Better to record nothing than to corrupt someone else's.
            XLog.w(TAG, "ledger id '$id' already taken — this task will not appear on the task page")
            ledgerRecordId = null
            return
        }
        ledgerRecordId = record.id
    }

    // ==================== Task Execution ====================

    fun cancelCurrentTask() {
        currentWatchdog?.disarm()
        currentWatchdog = null
        if (!taskSessionStore.markStopping()) return
        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        if (ForegroundService.isRunning()) {
            ForegroundService.updateTaskStatus(ClawApplication.instance, "Stopping task...")
        }
        XLog.d(TAG, "Current task cancellation requested")
    }

    /**
     * Start a new task. Routes through the 3-tier pipeline.
     *
     * Whatever the route, the task is recorded in the ledger here — this is
     * the single write point, so a task that arrives on a channel with no UI
     * attached is as visible on the task page as one typed into the app.
     *
     * @param ledgerTaskText what the ledger record calls the task; defaults
     *   to [task]. A scheduled task's prompt is the built envelope, so it
     *   passes the user's original request instead.
     */
    fun startNewTask(
        channel: Channel,
        task: String,
        messageID: String,
        agentPromptOverride: String? = null,
        isFallback: Boolean = false,
        originActivity: String? = null,
        ledgerTaskText: String? = null,
    ) {
        currentWatchdog?.disarm()
        currentWatchdog = null

        // Acquire task lock if not already held
        if (!isTaskRunning()) {
            if (!tryAcquireTask(messageID, channel, task, originActivity)) {
                XLog.w(TAG, "Failed to acquire task lock for: $task")
                emit(TaskEvent.Failed("Another task is running"))
                return
            }
            startLedgerRecord(messageID, ledgerTaskText ?: task, channel, originActivity)
        } else {
            val current = taskSessionStore.snapshot()
            if (current.messageId == messageID && current.channel == channel) {
                taskSessionStore.updateTaskText(task)
                // ChannelSetup acquires the lock before calling in, so this
                // branch is where a channel task's record is born. A skill
                // falling back to the agent loop re-enters with the same
                // messageID and already has its record.
                if (ledgerRecordId != messageID) {
                    startLedgerRecord(messageID, ledgerTaskText ?: task, channel, originActivity)
                }
            } else {
                XLog.w(
                    TAG,
                    "Rejecting new task while another task is still active: current=${current.messageId}/${current.channel} new=$messageID/$channel"
                )
                emit(TaskEvent.Failed("Another task is still running. Stop it first."))
                ChannelManager.sendMessage(channel, "Another task is still running. Stop it first.", messageID)
                return
            }
        }

        ForegroundService.updateTaskStatus(ClawApplication.instance, "Preparing task...")

        // Tier 1: Deterministic routing
        val route = pipelineRouter.route(task)
        when (route) {
            is PipelineRouter.Route.DirectIntent -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectIntent — ${route.description}")
                pipelineRouter.executeIntent(route.intent)
                XLog.i(TAG, "onComplete: rounds=0, totalTokens=0, model=direct, answer=${route.description}")
                val session = releaseTask()
                if (session.isRunning) {
                    emit(TaskEvent.Completed(route.description))
                    ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                    ForegroundService.resetToIdle(ClawApplication.instance)
                    FloatingCircleManager.setSuccessState(route.description)
                    onTaskFinished()
                }
                return
            }
            is PipelineRouter.Route.DirectTool -> {
                XLog.i(TAG, "Pipeline Tier 1: DirectTool — ${route.toolName}")
                val watchdog = armWatchdog(channel, messageID)
                Thread({
                    var success = false
                    val answer = try {
                        val toolResult = pipelineRouter.executeTool(route.toolName, route.params)
                        if (!toolResult.isSuccess) {
                            val error = toolResult.error ?: "Unknown error"
                            XLog.w(TAG, "Tier 1 tool failed: $error")
                            emit(TaskEvent.Completed("Failed: ${route.description}"))
                            ChannelManager.sendMessage(channel, "✗ ${route.description}: $error", messageID)
                            "Failed: ${route.description}: $error"
                        } else {
                            success = true
                            emit(TaskEvent.Completed(route.description))
                            ChannelManager.sendMessage(channel, "✓ ${route.description}", messageID)
                            route.description
                        }
                    } catch (e: Exception) {
                        val message = e.message ?: "Unknown error"
                        XLog.e(TAG, "Tier 1 tool crashed: ${route.toolName}", e)
                        emit(TaskEvent.Failed(message))
                        ChannelManager.sendMessage(channel, "✗ ${route.description}: $message", messageID)
                        "Failed: ${route.description}: $message"
                    } finally {
                        watchdog.disarm()
                        val session = releaseTask()
                        if (session.isRunning) {
                            ForegroundService.resetToIdle(ClawApplication.instance)
                            if (success) {
                                FloatingCircleManager.setSuccessState(route.description)
                            } else {
                                FloatingCircleManager.setErrorState("Failed: ${route.description}")
                            }
                            onTaskFinished()
                        }
                    }
                    XLog.i(TAG, "onComplete: rounds=0, totalTokens=0, model=direct, answer=$answer")
                }, "direct-tool-${route.toolName}").start()
                return
            }
            is PipelineRouter.Route.Skill -> {
                if (isFallback) {
                    XLog.i(TAG, "Skipping skill route on fallback, going to agent loop: ${route.skillId}")
                } else {
                    XLog.i(TAG, "Pipeline Tier 2: Skill — ${route.skillId}")
                    val skill = SkillRegistry.findById(route.skillId)
                    if (skill != null) {
                        FloatingCircleManager.ensureShowing()
                        FloatingCircleManager.showTaskNotify(task, channel)
                        val watchdog = armWatchdog(channel, messageID)
                        Thread({
                            val skillResult = skillExecutor.execute(
                                skill,
                                route.params,
                                onProgress = { step, total, desc ->
                                    watchdog.recordProgress()
                                    emit(TaskEvent.Progress(step, "Step $step/$total: $desc"))
                                    ForegroundService.updateTaskStatus(ClawApplication.instance, desc)
                                },
                                onToolCall = { toolName ->
                                    watchdog.recordProgress()
                                    val displayName =
                                        io.agents.nailongclaw.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                                    emit(TaskEvent.ToolAction(displayName))
                                },
                                onToolResult = { toolName, result ->
                                    watchdog.recordProgress()
                                    emitToolResultEvent(toolName, result)
                                },
                            )
                            if (skillResult.success) {
                                watchdog.disarm()
                                val session = releaseTask()
                                if (session.isRunning) {
                                    ChannelManager.sendMessage(channel, skillResult.message, messageID)
                                    emit(TaskEvent.Completed(skillResult.message))
                                    FloatingCircleManager.setSuccessState(skillResult.message)
                                    ForegroundService.resetToIdle(ClawApplication.instance)
                                    onTaskFinished()
                                }
                            } else {
                                watchdog.disarm()
                                val fallbackGoal = skill.fallbackGoal
                                    .let { g -> route.params.entries.fold(g) { acc, (k, v) -> acc.replace("{$k}", v) } }
                                XLog.i(TAG, "Skill ${skill.id} failed, falling back to agent loop: $fallbackGoal")
                                emit(TaskEvent.ToolAction("Retrying with AI agent"))
                                startNewTask(channel, fallbackGoal, messageID, isFallback = true)
                            }
                        }, "skill-executor").start()
                        return
                    }
                    XLog.w(TAG, "Skill ${route.skillId} not found, falling through to agent loop")
                }
            }
            is PipelineRouter.Route.Chat, is PipelineRouter.Route.AgentLoop -> {
                // Fall through to agent loop
            }
        }

        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService not initialized, attempting to initialize")
            try {
                agentService = AgentServiceFactory.create()
                agentService.initialize(agentConfigProvider())
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to initialize AgentService", e)
                releaseTask()
                ForegroundService.resetToIdle(ClawApplication.instance)
                emit(TaskEvent.Failed("AI service not ready"))
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
                return
            }
        }

        // Per-round message buffer for channel messaging
        val roundBuffer = StringBuilder()
        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                roundBuffer.clear()
            }
        }

        var floatingShown = false

        val watchdog = armWatchdog(channel, messageID)
        val agentPrompt = agentPromptOverride?.takeIf { it.isNotBlank() } ?: task
        agentService.executeTask(agentPrompt, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                watchdog.recordProgress()
                flushRoundBuffer()
                XLog.d(TAG, "onLoopStart: round=$round")
                if (round > 1) {
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.setRunningState(round, channel)
                    emit(TaskEvent.LoopStart(round))
                    if (ForegroundService.isRunning()) {
                        ForegroundService.updateTaskStatus(ClawApplication.instance, "Step $round")
                    }
                }
            }

            override fun onTokenUpdate(status: io.agents.nailongclaw.agent.TokenMonitor.Status) {
                watchdog.recordProgress()
                FloatingCircleManager.updateTokenStatus(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                )
                emit(TaskEvent.TokenUpdate(
                    step = status.step,
                    formattedTokens = status.formattedTokens,
                    formattedCost = status.formattedCost,
                    tokenState = status.state
                ))
            }

            override fun onContent(round: Int, content: String) {
                watchdog.recordProgress()
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                    emit(TaskEvent.Thinking(content))
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                watchdog.recordProgress()
                XLog.d(TAG, "onToolCall: $toolId($toolName), $parameters")
                // Don't show floating circle for finish tool (it's just completion, not a real action)
                val isFinish = toolName == "finish" || toolId == "finish"
                if (!floatingShown && !isFinish) {
                    floatingShown = true
                    FloatingCircleManager.ensureShowing()
                    FloatingCircleManager.showTaskNotify(task, channel)
                    ForegroundService.updateTaskStatus(ClawApplication.instance, "Running task...")
                }
                if (toolName.isNotEmpty()) {
                    val displayName = io.agents.nailongclaw.tool.ToolRegistry.getInstance().getDisplayName(toolName)
                    emit(TaskEvent.ToolAction(displayName))
                    ForegroundService.updateTaskStatus(ClawApplication.instance, "$displayName...")
                    FloatingCircleManager.updateTaskAction(displayName)
                }
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                watchdog.recordProgress()
                val app = ClawApplication.instance
                val success = result.isSuccess
                if (!success) XLog.e(TAG, "Tool failed: $toolName ${result.error}")

                emitToolResultEvent(toolName, result)

                if (toolId == "finish" && result.data?.isNotEmpty() == true) {
                    flushRoundBuffer()
                    ChannelManager.sendMessage(channel, result.data, messageID)
                } else {
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(app.getString(R.string.channel_msg_tool_execution, toolName + parameters,
                        if (success) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)))
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                watchdog.disarm()
                currentWatchdog = null
                XLog.i(TAG, "onComplete: rounds=$round, totalTokens=$totalTokens, model=$modelName, answer=$finalAnswer")
                val cancelAnswers = setOf(
                    ClawApplication.instance.getString(R.string.agent_task_cancel),
                    ClawApplication.instance.getString(R.string.agent_task_cancelled),
                    ClawApplication.instance.getString(R.string.channel_msg_task_cancelled)
                )
                if (finalAnswer.trim() in cancelAnswers) {
                    val cancelledSession = releaseTask()
                    if (!cancelledSession.isRunning) {
                        XLog.d(TAG, "Task already released, ignoring cancel completion")
                        return
                    }
                    emit(TaskEvent.Cancelled)
                    ForegroundService.resetToIdle(ClawApplication.instance)
                    flushRoundBuffer()
                    if (cancelledSession.channel != null && cancelledSession.messageId.isNotEmpty()) {
                        ChannelManager.sendMessage(
                            cancelledSession.channel,
                            ClawApplication.instance.getString(R.string.channel_msg_task_cancelled),
                            cancelledSession.messageId
                        )
                        ChannelManager.flushMessages(cancelledSession.channel)
                    }
                    FloatingCircleManager.setErrorState()
                    onTaskFinished()
                    XLog.d(TAG, "Current task cancelled by user")
                    return
                }
                // Strip common LLM-added prefixes from the answer
                var answer = finalAnswer.ifEmpty { "Done." }
                answer = answer.removePrefix("Task completed:").removePrefix("Task completed").trim()
                if (answer.isEmpty()) answer = "Done."
                val completedSession = releaseTask()
                if (!completedSession.isRunning) {
                    XLog.d(TAG, "Task already released, ignoring completion")
                    return
                }
                emit(TaskEvent.Completed(answer, modelName))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                ChannelManager.flushMessages(completedSession.channel ?: channel)
                FloatingCircleManager.setSuccessState(answer)
                // Auto-return to the surface that started the task
                if (completedSession.autoReturnToChat) {
                    XLog.i(
                        TAG,
                        "onComplete: auto-returning to ${completedSession.originActivity ?: "chat (default)"}"
                    )
                    try {
                        val context = ClawApplication.instance
                        val target = returnTargetComponent(completedSession.originActivity)
                            ?: returnTargetComponent(DEFAULT_RETURN_ACTIVITY)
                        val intent = android.content.Intent().apply {
                            component = target
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        XLog.w(TAG, "onComplete: auto-return failed", e)
                    }
                }
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                watchdog.disarm()
                currentWatchdog = null
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                val failedSession = releaseTask()
                if (!failedSession.isRunning) {
                    XLog.d(TAG, "Task already released, ignoring error")
                    return
                }
                emit(TaskEvent.Failed(error.message ?: "Unknown error"))
                ForegroundService.resetToIdle(ClawApplication.instance)
                flushRoundBuffer()
                val failedChannel = failedSession.channel ?: channel
                val failedMessageId = failedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    failedChannel,
                    ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message),
                    failedMessageId
                )
                ChannelManager.flushMessages(failedChannel)
                FloatingCircleManager.setErrorState(error.message)
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                watchdog.disarm()
                currentWatchdog = null
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                val blockedSession = releaseTask()
                if (!blockedSession.isRunning) {
                    XLog.d(TAG, "Task already released, ignoring blocked event")
                    return
                }
                emit(TaskEvent.Blocked)
                flushRoundBuffer()
                val blockedChannel = blockedSession.channel ?: channel
                val blockedMessageId = blockedSession.messageId.ifEmpty { messageID }
                ChannelManager.sendMessage(
                    blockedChannel,
                    ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked),
                    blockedMessageId
                )
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        ChannelManager.sendImage(blockedChannel, stream.toByteArray(), blockedMessageId)
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }
        })
    }
}
