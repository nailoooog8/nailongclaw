// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.ServiceBindingState
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.agent.SearchProvider
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.agent.mcp.McpConfirmationCoordinator
import io.agents.pokeclaw.automation.ExternalAutomationContract
import io.agents.pokeclaw.automation.ExternalAutomationEntrypoint
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.channel.Channel
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.ui.settings.LlmConfigActivity
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.ui.settings.WebSearchActivity
import io.agents.pokeclaw.ui.web.ClaudeUiPreviewActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.Executors

/**
 * PokeClaw Chat Activity — Compose shell for the chat screen.
 *
 * Chat runtime ownership lives in [ChatSessionController].
 * This activity keeps lifecycle wiring, task flows, and sidebar/history UI state.
 */
class ComposeChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ComposeChatActivity"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_CHAT = "chat"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val conversationStore by lazy { ConversationStore(this) }

    // Compose state — observed by ChatScreen
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _modelStatus = mutableStateOf("No model loaded")
    private val _isLocalModelActive = mutableStateOf(ModelConfigRepository.isLocalActive())
    private val _needsPermission = mutableStateOf(false)
    private val _isAwaitingReply = mutableStateOf(false)
    private val _isTaskRunning = mutableStateOf(false)
    private val _inputEnabled = mutableStateOf(true)    // False when model not ready (no task running)
    private val _conversations = mutableStateListOf<ChatHistoryManager.ConversationSummary>()
    private val _isDownloading = mutableStateOf(false)
    private val _downloadProgress = mutableStateOf(0)

    // Session-level token tracking for chat mode
    private val _sessionTokens = mutableStateOf(0)
    private val _sessionCost = mutableStateOf(0.0)
    // Smart-search card state — refreshed on resume so a key saved in the
    // web-search settings page shows up without leaving and re-entering chat.
    private val _webSearchProvider = mutableStateOf(SearchProvider.DEEPSEEK.name)
    private val _webSearchConfigured = mutableStateOf(false)
    private var deferLocalChatBootstrapForAutoTask = false
    private var pendingExternalRequestId: String? = null
    private var pendingExternalReturnAction: String? = null
    private var pendingExternalReturnPackage: String? = null

    private val chatSessionController by lazy {
        ChatSessionController(
            activity = this,
            executor = executor,
            sink = ChatSessionUiState(
                messages = _messages,
                modelStatusState = _modelStatus,
                awaitingReplyState = _isAwaitingReply,
                inputEnabledState = _inputEnabled,
                downloadingState = _isDownloading,
                downloadProgressState = _downloadProgress,
                sessionTokensState = _sessionTokens,
                sessionCostState = _sessionCost,
            ),
            onPersistConversation = { saveChat() },
            onRefreshSidebarHistory = { refreshSidebarHistory() },
            isTaskRunning = { appViewModel.isTaskRunning() },
        )
    }

    private val taskFlowController by lazy {
        TaskFlowController(
            activity = this,
            executor = executor,
            appViewModel = appViewModel,
            chatSessionController = chatSessionController,
            currentConversationId = { conversationStore.currentConversationId },
            sink = TaskFlowUiState(
                messages = _messages,
                modelStatusState = _modelStatus,
                awaitingReplyState = _isAwaitingReply,
                taskRunningState = _isTaskRunning,
            ),
            onPersistConversation = { saveChat() },
            onTaskSettled = { deferLocalChatBootstrapForAutoTask = false },
            onTaskTerminal = { sendExternalAutomationTerminalCallback(it) },
        )
    }

    private val activeTaskShellController by lazy {
        ActiveTaskShellController(appViewModel = appViewModel)
    }

    /**
     * Answers MCP confirmations while the chat screen is in the foreground.
     *
     * Registered with the process-wide coordinator rather than handed to the
     * task flow, because the thing that needs the answer is a tool call
     * blocked on the agent thread — which knows nothing about Activities.
     */
    private val mcpConfirmationSurface by lazy {
        McpConfirmationSurface(
            activity = this,
            onNote = { taskFlowController.noteSystem(it) },
        )
    }

    // Permission polling
    private val permHandler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            _needsPermission.value =
                AppCapabilityCoordinator.accessibilityState(this@ComposeChatActivity) != ServiceBindingState.READY
            permHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Hide floating circle only when no task is running
        // (task running = keep floating pill visible for step/token status)
        try {
            if (!appViewModel.isTaskRunning()) {
                FloatingCircleManager.hide()
            } else {
                XLog.d(TAG, "onCreate: task running, keeping floating circle visible")
            }
        } catch (_: Exception) {}

        // Check for updates
        io.agents.pokeclaw.utils.UpdateChecker.checkForUpdate(this)

        // Status bar color
        val themeColors = ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg

        // Build Compose colors from ThemeManager
        val composeColors = with(ThemeManager) { themeColors.toComposeColors() }

        setContent {
            val activeTasks by activeTaskShellController.activeTasks.collectAsState()

            ChatScreen(
                messages = _messages.toList(),
                modelStatus = _modelStatus.value,
                needsPermission = _needsPermission.value,
                isAwaitingReply = _isAwaitingReply.value,
                isTaskRunning = _isTaskRunning.value,
                inputEnabled = _inputEnabled.value,
                isDownloading = _isDownloading.value,
                downloadProgress = _downloadProgress.value,
                isLocalModel = _isLocalModelActive.value,
                sessionTokens = _sessionTokens.value,
                sessionCost = _sessionCost.value,
                onSendChat = { sendChat(it) },
                onSendTask = { taskFlowController.sendTask(it) },
                onStartMonitor = { target -> taskFlowController.startMonitor(target) },
                onSendDirectMessage = { contact, app, message ->
                    taskFlowController.sendTask("send \"$message\" to $contact on $app")
                },
                onNewChat = { newChat() },
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onOpenModels = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onFixPermissions = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onAttach = { Toast.makeText(this, "Image upload coming soon", Toast.LENGTH_SHORT).show() },
                conversations = _conversations.toList(),
                onSelectConversation = { loadConversation(it) },
                onDeleteConversation = { conv ->
                    val deleted = conversationStore.deleteConversation(conv)
                    XLog.i(TAG, "Delete conversation: ${conv.file.absolutePath} deleted=$deleted")
                    refreshSidebarHistory()
                },
                onRenameConversation = { conv, newName ->
                    val renamed = conversationStore.renameConversation(conv, newName)
                    XLog.i(TAG, "Rename conversation: '${conv.title}' → '$newName' renamed=$renamed")
                    refreshSidebarHistory()
                },
                activeTasks = activeTasks,
                onStopTask = { contact ->
                    _isTaskRunning.value = appViewModel.isTaskRunning()
                    Toast.makeText(
                        this,
                        activeTaskShellController.stopTask(contact),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onStopAllTasks = {
                    _isAwaitingReply.value = false
                    _isTaskRunning.value = false
                    Toast.makeText(
                        this,
                        activeTaskShellController.stopAllTasks(),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onOpenLedger = {
                    // 任务账本长在主界面（Claude UI 表面）上，带 extra 直接
                    // 翻开任务页而不是落在聊天流——这个入口的意图就是看账本。
                    startActivity(
                        Intent(this, ClaudeUiPreviewActivity::class.java)
                            .putExtra(ClaudeUiPreviewActivity.EXTRA_SHOW_TASKS, true)
                    )
                },
                onModelSwitch = { modelId, displayName -> switchModel(modelId, displayName) },
                colors = composeColors,
                webSearchProvider = _webSearchProvider.value,
                webSearchConfigured = _webSearchConfigured.value,
                onSelectSearchProvider = { provider ->
                    KVUtils.setWebSearchProvider(provider.name)
                    refreshWebSearchStatus()
                },
                onManageSearchKeys = {
                    startActivity(Intent(this, WebSearchActivity::class.java))
                },
            )
        }

        refreshWebSearchStatus()
        refreshSidebarHistory()

        // Restore last conversation if Activity was recreated (e.g., system killed it during a task)
        if (_messages.isEmpty()) {
            conversationStore.restoreLastConversation()?.let { restored ->
                syncSidebar(restored.conversations)
                if (restored.messages.isNotEmpty()) {
                    _messages.addAll(restored.messages)
                    XLog.i(
                        TAG,
                        "Restored ${restored.messages.size} messages from conversation ${restored.conversationId}"
                    )
                }
            }
        }

        deferLocalChatBootstrapForAutoTask = shouldDeferLocalChatBootstrap(intent)
        if (!deferLocalChatBootstrapForAutoTask) {
            chatSessionController.loadModelIfReady(
                conversationId = conversationStore.currentConversationId,
                visibleMessages = _messages.toList(),
            )
        }
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()

        // Release local LLM conversation before task starts so the agent can use the engine
        // (LiteRT-LM only supports 1 session at a time)
        appViewModel.onBeforeTask = {
            chatSessionController.releaseForTask()
        }

        // Debug: auto-trigger task from ADB intent
        // Usage: adb shell am start -n io.agents.pokeclaw/.ui.chat.ComposeChatActivity --es task "open my camera"
        handleIntentAutomation(intent, initialDelayMs = 2000)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deferLocalChatBootstrapForAutoTask = shouldDeferLocalChatBootstrap(intent)
        handleIntentAutomation(intent, initialDelayMs = 1000)
    }

    override fun onResume() {
        super.onResume()
        // The chat screen is the screen the user is looking at, so it is the
        // surface that answers MCP confirmations. The Claude UI preview
        // registers the same way; the coordinator keeps the last one attached.
        mcpConfirmationSurface.isForeground = true
        McpConfirmationCoordinator.attach(mcpConfirmationSurface)
        _needsPermission.value =
            AppCapabilityCoordinator.accessibilityState(this) != ServiceBindingState.READY
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()
        _isTaskRunning.value = appViewModel.isTaskRunning()
        refreshSidebarHistory()
        refreshWebSearchStatus()
        permHandler.removeCallbacks(permPoller)
        permHandler.postDelayed(permPoller, 1000)
        activeTaskShellController.onResume()
        if (!deferLocalChatBootstrapForAutoTask) {
            chatSessionController.onResume(
                conversationId = conversationStore.currentConversationId,
                visibleMessages = _messages.toList(),
            )
        }
    }

    override fun onPause() {
        super.onPause()
        // Stays attached — a task keeps running while the app is backgrounded —
        // but can no longer show a dialog, which the coordinator treats as
        // "refuse now" rather than "wait for nobody".
        mcpConfirmationSurface.isForeground = false
        saveChat()
        permHandler.removeCallbacks(permPoller)
        activeTaskShellController.onPause()
        chatSessionController.onPause(conversationStore.currentConversationId)
    }

    override fun onDestroy() {
        super.onDestroy()
        // A wait this screen cannot answer must be refused now, not left to
        // time out against a dialog that no longer exists.
        McpConfirmationCoordinator.detach(mcpConfirmationSurface)
        mcpConfirmationSurface.dismiss()
        chatSessionController.onDestroy()
        executor.shutdown()
    }

    // ==================== CHAT ====================

    private fun sendChat(text: String) {
        chatSessionController.sendChat(text)
    }

    private fun handleIntentAutomation(intent: Intent?, initialDelayMs: Long) {
        val taskText = intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() }
        val chatText = intent?.getStringExtra(EXTRA_CHAT)?.takeIf { it.isNotBlank() }
        val automationText = taskText ?: chatText ?: return
        val isTask = taskText != null
        captureExternalAutomationCallback(intent, isTask)

        XLog.i(
            TAG,
            if (isTask) "Auto-task from intent: $automationText" else "Auto-chat from intent: $automationText"
        )

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isTask) {
                    // The task came from another app's broadcast, not from this
                    // screen. Record it as EXTERNAL so ToolRiskPolicy treats it
                    // like any other unwatched source instead of as the local
                    // user (R3-01: it used to inherit Channel.LOCAL).
                    val channel = if (
                        intent?.getBooleanExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_SOURCE, false) == true
                    ) Channel.EXTERNAL else Channel.LOCAL
                    taskFlowController.sendTask(automationText, channel)
                    return
                }
                if (!KVUtils.hasLlmConfig()) {
                    _messages.add(ChatMessage(ChatMessage.Role.USER, automationText))
                    _messages.add(ChatMessage(ChatMessage.Role.SYSTEM, "Configure LLM in Settings first."))
                    saveChat()
                    return
                }
                if (chatSessionController.isModelReady()) {
                    sendChat(automationText)
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }, initialDelayMs)
    }

    private fun shouldDeferLocalChatBootstrap(intent: Intent?): Boolean {
        val taskText = intent?.getStringExtra(EXTRA_TASK)?.takeIf { it.isNotBlank() } ?: return false
        return taskText.isNotBlank() && ModelConfigRepository.isLocalActive()
    }

    private fun captureExternalAutomationCallback(intent: Intent?, isTask: Boolean) {
        pendingExternalRequestId = null
        pendingExternalReturnAction = null
        pendingExternalReturnPackage = null
        if (!isTask) return
        pendingExternalRequestId = intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_REQUEST_ID)
        pendingExternalReturnAction = intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_ACTION)
        pendingExternalReturnPackage = intent?.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_PACKAGE)
    }

    private fun sendExternalAutomationTerminalCallback(event: TaskEvent) {
        val returnAction = pendingExternalReturnAction ?: return
        val requestId = pendingExternalRequestId
        val returnPackage = pendingExternalReturnPackage
        val status: String
        var result: String? = null
        var error: String? = null
        when (event) {
            is TaskEvent.Completed -> {
                status = ExternalAutomationContract.STATUS_COMPLETED
                result = event.answer
            }
            is TaskEvent.Failed -> {
                status = ExternalAutomationContract.STATUS_FAILED
                error = event.error
            }
            is TaskEvent.Cancelled -> {
                status = ExternalAutomationContract.STATUS_CANCELLED
                error = "Task cancelled."
            }
            is TaskEvent.Blocked -> {
                status = ExternalAutomationContract.STATUS_BLOCKED
                error = "Task blocked by a system dialog."
            }
            else -> return
        }
        ExternalAutomationContract.sendCallback(
            context = this,
            returnAction = returnAction,
            requestId = requestId,
            status = status,
            result = result,
            error = error,
            returnPackage = returnPackage,
            mode = ExternalAutomationContract.Mode.TASK,
        )
        pendingExternalRequestId = null
        pendingExternalReturnAction = null
        pendingExternalReturnPackage = null
    }

    private fun syncTaskAgentConfig() {
        if (!appViewModel.updateAgentConfig()) {
            XLog.w(TAG, "syncTaskAgentConfig: failed to update task agent config")
        }
    }

    private fun switchModel(modelId: String, displayName: String) {
        chatSessionController.switchModel(modelId, displayName)
        _isLocalModelActive.value = ModelConfigRepository.isLocalActive()
        if (modelId != "NONE") {
            syncTaskAgentConfig()
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    private fun newChat() {
        val session = conversationStore.startNewConversation(_messages, currentConversationModelName())
        syncSidebar(session.conversations)
        _messages.clear()
        _sessionTokens.value = 0
        _sessionCost.value = 0.0
        _isAwaitingReply.value = false
        _isTaskRunning.value = false
        chatSessionController.startNewConversationRuntime()
    }

    private fun loadConversation(conv: ChatHistoryManager.ConversationSummary) {
        val session = conversationStore.openConversation(conv, _messages, currentConversationModelName())
        syncSidebar(session.conversations)
        _messages.clear()
        _messages.addAll(session.messages)
        _isAwaitingReply.value = false
        _isTaskRunning.value = false
        chatSessionController.restoreConversationRuntime(session.conversationId, session.messages)
    }

    private fun saveChat() {
        syncSidebar(conversationStore.saveCurrent(_messages, currentConversationModelName()))
    }

    private fun refreshSidebarHistory() {
        syncSidebar(conversationStore.refreshSidebar())
    }

    /** Reads the web-search card's truth from KVUtils — the card never caches. */
    private fun refreshWebSearchStatus() {
        _webSearchProvider.value = KVUtils.getWebSearchProvider()
        _webSearchConfigured.value = KVUtils.isWebSearchConfigured()
    }

    private fun syncSidebar(convos: List<ChatHistoryManager.ConversationSummary>) {
        _conversations.clear()
        _conversations.addAll(convos)
    }

    private fun currentConversationModelName(): String {
        val config = ModelConfigRepository.snapshot()
        return if (config.isLocalActive()) {
            config.local.displayName.ifBlank {
                KVUtils.getLocalModelPath().substringAfterLast('/').substringBeforeLast('.')
            }
        } else {
            config.activeCloud.modelName
        }
    }
}
