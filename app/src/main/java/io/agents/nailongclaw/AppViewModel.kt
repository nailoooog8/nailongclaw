// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw

import android.os.PowerManager
import androidx.lifecycle.ViewModel
import io.agents.nailongclaw.ClawApplication.Companion.appViewModelInstance
import io.agents.nailongclaw.TaskEvent
import io.agents.nailongclaw.agent.AgentConfig
import io.agents.nailongclaw.agent.EffortLevel
import io.agents.nailongclaw.agent.llm.ModelConfigRepository
import io.agents.nailongclaw.channel.Channel
import io.agents.nailongclaw.channel.ChannelManager
import io.agents.nailongclaw.channel.ChannelSetup
import io.agents.nailongclaw.service.ForegroundService
import io.agents.nailongclaw.floating.FloatingCircleManager
import io.agents.nailongclaw.server.ConfigServerManager
import io.agents.nailongclaw.task.TaskRecordStore
import io.agents.nailongclaw.utils.KVUtils
import io.agents.nailongclaw.utils.XLog

class AppViewModel : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private var _commonInitialized = false

    val taskOrchestrator = TaskOrchestrator(
        agentConfigProvider = { getAgentConfig() },
        onTaskFinished = {
            // Any task ending is the queue's chance to drain: a scheduled
            // task that fired while this one ran has been waiting visibly.
            io.agents.nailongclaw.task.ScheduleDispatcher.onAnyTaskFinished()
        }
    )

    private val channelSetup = ChannelSetup(taskOrchestrator = taskOrchestrator)

    val taskSessionStore: TaskSessionStore
        get() = taskOrchestrator.taskSessionStore
    val inProgressTaskMessageId: String get() = taskSessionStore.snapshot().messageId
    val inProgressTaskChannel: Channel? get() = taskSessionStore.snapshot().channel

    // ==================== Task API (clean interface for Activity) ====================

    /**
     * Called before a task starts — allows the chat UI to release its local LLM conversation
     * so the task agent can use the same LiteRT-LM engine (only 1 session supported).
     */
    var onBeforeTask: (() -> Unit)? = null

    fun startTask(
        task: String,
        taskId: String,
        agentPromptOverride: String? = null,
        originActivity: String? = null,
        /**
         * Where the task came from. Defaults to [Channel.LOCAL] — the app's own
         * UI. An external-automation task passes [Channel.EXTERNAL] so
         * ToolRiskPolicy gates it like any other unwatched source instead of
         * silently inheriting the local tier (R3-01). A task fired by the
         * alarm passes [Channel.SCHEDULED] — unwatched for the same reason, and
         * therefore held to the READ_ONLY ceiling.
         */
        channel: Channel = Channel.LOCAL,
        /**
         * What the ledger record should call this task, when the prompt and
         * the user-facing request differ. Defaults to [task] — a task typed
         * in the app is its own display text. A scheduled task passes its
         * original request, because the prompt it runs with is the built
         * envelope (calendar anchor + fence + delivery instructions).
         */
        ledgerTaskText: String? = null,
        onEvent: (TaskEvent) -> Unit,
    ) {
        // Open the ledger record before any gate. The user is told the task
        // started the moment this is called, so the record has to exist
        // before anything can refuse it — otherwise a task rejected by the
        // LLM-config check, an onBeforeTask throw, or a task-lock race runs
        // to completion with no trace on the task page. The orchestrator
        // adopts this record rather than creating a second one.
        try {
            TaskRecordStore.start(
                id = taskId,
                taskText = ledgerTaskText ?: task,
                channel = channel,
                originActivity = originActivity,
            )
        } catch (e: Exception) {
            XLog.w(TAG, "ledger start failed for '$taskId'", e)
        }
        onBeforeTask?.invoke()
        taskOrchestrator.taskEventCallback = onEvent
        if (!updateAgentConfig()) {
            onEvent(TaskEvent.Failed("AI service not ready"))
            // Close the record the entry point opened, so it reads FAILED
            // instead of sitting RUNNING forever.
            TaskRecordStore.onEvent(taskId, TaskEvent.Failed("AI service not ready"))
            return
        }
        taskOrchestrator.startNewTask(
            channel,
            task,
            taskId,
            agentPromptOverride = agentPromptOverride,
            originActivity = originActivity,
            ledgerTaskText = ledgerTaskText,
        )
    }

    fun stopTask() {
        taskOrchestrator.cancelCurrentTask()
    }

    fun isTaskRunning(): Boolean = taskSessionStore.isTaskRunning()

    fun clearTaskCallback() {
        taskOrchestrator.taskEventCallback = null
    }

    fun init() {
        initCommon()
        initAgent()
    }

    fun initCommon() {
        if (_commonInitialized) return
        _commonInitialized = true
    }

    fun initAgent() {
        if (!KVUtils.hasLlmConfig()) return
        taskOrchestrator.initAgent()
    }

    fun getAgentConfig(): AgentConfig =
        ModelConfigRepository.snapshot().toAgentConfig(
            temperature = 0.1,
            // The composer's effort gear picks the agent loop's round budget.
            maxIterations = EffortLevel.maxIterationsOf(EffortLevel.current())
        )

    fun updateAgentConfig(): Boolean = taskOrchestrator.updateAgentConfig()

    fun afterInit() {
        acquireScreenWakeLock()
        ForegroundService.syncToBackgroundState(ClawApplication.instance)
        ConfigServerManager.autoStartIfNeeded(ClawApplication.instance)
        channelSetup.setup()
    }


    /**
     * Acquire a wake lock to prevent the screen from turning off during accessibility operations
     */
    private fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = ClawApplication.instance.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "NailongClaw::ScreenWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minute timeout to prevent battery drain
        }
        XLog.i(TAG, "Wake lock acquired")
    }

    /**
     * Release the wake lock
     */
    private fun releaseScreenWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                XLog.i(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    init {
        // The pill's tap behaviour is intrinsic to the pill, not to whoever
        // happens to create the window: the task path brings the float up on
        // its own (FloatingCircleManager.ensureShowing), so the callbacks are
        // wired once here, at construction, and stay live for the process.
        // Wiring them inside a show entry point instead is what left the pill
        // unclickable once that entry point had no callers.
        FloatingCircleManager.onFloatClick = {
            XLog.d(TAG, "Floating circle clicked")
            bringAppToForeground()
        }
        FloatingCircleManager.onStopTask = {
            XLog.i(TAG, "Stop task requested from floating pill")
            stopTask()
            bringAppToForeground()
        }
    }

    /**
     * Bring the app to the foreground
     *
     * Opens the main screen (the Claude UI surface since 2026-10-24), not the
     * old chat screen — the floating pill's click has no extras, it is a plain
     * "open the app" gesture.
     */
    private fun bringAppToForeground() {
        val context = ClawApplication.instance
        val intent = android.content.Intent(context, io.agents.nailongclaw.ui.web.ClaudeUiPreviewActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    // Old pass-through methods removed — use startTask/stopTask/isTaskRunning/clearTaskCallback instead

    private fun trySendScreenshot(channel: Channel, filePath: String, messageID: String) {
        try {
            val file = java.io.File(filePath)
            if (!file.exists()) {
                XLog.w(TAG, "Screenshot file does not exist: $filePath")
                return
            }
            val imageBytes = file.readBytes()
            ChannelManager.sendImage(channel, imageBytes, messageID)
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to send screenshot", e)
        }
    }
}
