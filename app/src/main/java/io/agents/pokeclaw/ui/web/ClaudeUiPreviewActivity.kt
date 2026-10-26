// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebViewAssetLoader
import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.agent.EffortLevel
import io.agents.pokeclaw.agent.mcp.McpConfirmationCoordinator
import io.agents.pokeclaw.agent.mcp.McpConfirmationGate
import io.agents.pokeclaw.agent.mcp.McpConfirmationRequest
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.chat.ChatHistoryManager
import io.agents.pokeclaw.ui.chat.ChatSessionController
import io.agents.pokeclaw.ui.chat.ChatSessionUiState
import io.agents.pokeclaw.ui.chat.TaskFlowController
import io.agents.pokeclaw.ui.settings.AboutActivity
import io.agents.pokeclaw.ui.settings.ChannelsActivity
import io.agents.pokeclaw.ui.settings.McpServersActivity
import io.agents.pokeclaw.ui.settings.MemoryActivity
import io.agents.pokeclaw.ui.settings.ModelSettingsActivity
import io.agents.pokeclaw.ui.settings.MonitorActivity
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.ui.settings.ThemeActivity
import io.agents.pokeclaw.ui.settings.ToolsActivity
import io.agents.pokeclaw.ui.settings.UserSkillsActivity
import io.agents.pokeclaw.task.ScheduledTaskStore
import io.agents.pokeclaw.task.TaskRecordStore
import io.agents.pokeclaw.task.TaskScheduler
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.Executors

/**
 * Claude UI chat surface, backed by the real task flow.
 *
 * The app's main screen since 2026-10-24: SplashActivity (the launcher)
 * routes here, as do the floating-pill click and the foreground-service
 * notification tap. The Compose chat screen ([io.agents.pokeclaw.ui.chat.ComposeChatActivity])
 * keeps only the external `task` extra contract and is otherwise demoted.
 *
 * Renders `assets/claude-ui/index.html` from the packaged APK and drives it
 * from [io.agents.pokeclaw.ui.chat.TaskFlowController] through a
 * [WebChatSink]. The orchestration is *not* reimplemented here — the same
 * controller backs the Compose chat screen; this is the second sink.
 *
 * Also a design-review surface, so the static sample turns (MCP confirm
 * card, memory chips) stay in `index.html` as markup samples — cleared on
 * entry here, kept for the pure-browser preview. The memory chips have
 * carried live recall data since 2026-10-24: a `memory_recall` tool result is
 * parsed by `MemoryRecallFormat` into chips and rides the tool step's
 * `memories` field. The MCP card is not a static sample any more either —
 * since 2026-10-23 it carries a real decision loop: `PokeClawChat.decideMcp`
 * answers `McpConfirmationCoordinator`, which is what parks the agent thread
 * that asked (QA H27). The tool card has carried real tool calls since
 * 2026-10-19, and since 2026-10-22 it survives a transcript restore too
 * (QA H23 / H26).
 *
 * Not exported, so no other app can reach it.
 */
class ClaudeUiPreviewActivity : BaseActivity() {

    private lateinit var webView: WebView
    private var qaReceiver: BroadcastReceiver? = null
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Whether the page's in-WebView settings sheet is open. The page is the
     * source of truth and reports every transition through the
     * `notifySettingsView` bridge method; this flag only drives BACK
     * handling (close the sheet first, exit the activity second).
     */
    private var settingsViewOpen = false

    /**
     * Task-page level mirrored from the page via `notifyTasksView`.
     *
     * `@Volatile` because the write lands on the WebView's JavaScript
     * thread while BACK reads it on MAIN. A stale read degrades to "BACK
     * leaves the page instead of closing the sheet" — the same failure
     * class as the settings flag, one annotation cheaper to be correct.
     */
    @Volatile
    private var tasksLevel: String = TASKS_LEVEL_CLOSED

    /**
     * Schedule-page level mirrored from the page via `notifySchedulesView`.
     * Same `@Volatile` discipline as [tasksLevel] — the write lands on the
     * WebView's JavaScript thread while BACK reads it on MAIN.
     */
    @Volatile
    private var schedulesLevel: String = SCHEDULES_LEVEL_CLOSED

    private val sink by lazy {
        WebChatSink(
            onRender = { messages -> publish(messages) },
            onConfirmation = { request -> publishConfirmation(request) },
        )
    }

    /**
     * Live ledger deltas → the task page. The sink's callback may arrive on
     * any thread (the ledger is written from the UI thread and the agent
     * thread), and the WebView push is a UI-thread operation — hence the
     * hop through [runOnUiThread] here rather than inside the sink.
     */
    private val taskLedgerSink = WebTaskLedgerSink {
        runOnUiThread { publishTaskLedger() }
    }

    /**
     * Live queue deltas → the schedule page. Same shape and reasoning as
     * [taskLedgerSink]: the sink is a doorbell (fires on the writer's
     * thread), the WebView push is a UI-thread operation — hence the hop
     * through [runOnUiThread] here rather than inside the sink.
     */
    private val scheduleLedgerSink = WebScheduleLedgerSink {
        runOnUiThread { publishScheduleLedger() }
    }

    private val taskFlowController by lazy {
        TaskFlowController(
            activity = this,
            executor = executor,
            appViewModel = appViewModel,
            chatSessionController = chatSessionController,
            currentConversationId = { PREVIEW_CONVERSATION_ID },
            sink = sink,
            onPersistConversation = { saveChat() },
        )
    }

    private val chatSessionController by lazy {
        ChatSessionController(
            activity = this,
            executor = executor,
            sink = ChatSessionUiState(
                messages = mutableStateListOf(),
                modelStatusState = mutableStateOf("No model loaded"),
                awaitingReplyState = mutableStateOf(false),
                inputEnabledState = mutableStateOf(true),
                downloadingState = mutableStateOf(false),
                downloadProgressState = mutableStateOf(0),
                sessionTokensState = mutableStateOf(0),
                sessionCostState = mutableStateOf(0.0),
            ),
            onPersistConversation = { saveChat() },
            onRefreshSidebarHistory = {},
            isTaskRunning = { appViewModel.isTaskRunning() },
        )
    }

    /**
     * Persist this surface's history.
     *
     * Deliberately does NOT go through [io.agents.pokeclaw.ui.chat.ConversationStore].
     * Its `currentConversationId` lives in a **global** KV key, so saving here
     * would overwrite the chat screen's current conversation — and the chat
     * screen would overwrite this one on its next save. Two surfaces silently
     * clobbering each other's history is worse than either one not persisting.
     *
     * [io.agents.pokeclaw.ui.chat.ChatHistoryManager] is stateless, so it can be
     * called directly with a fixed id. The consequence is that this surface's
     * history does not appear in the chat screen's sidebar, which is the right
     * default: an experimental surface should not bury real conversations.
     */
    private fun saveChat() {
        runCatching {
            ChatHistoryManager.save(
                context = this,
                conversationId = PREVIEW_CONVERSATION_ID,
                messages = sink.snapshotMessages(),
                model = sink.modelStatus,
            )
        }.onFailure {
            XLog.e(TAG, "saveChat failed: ${it.message}", it)
        }
    }

    /**
     * Reload history written by a previous visit.
     *
     * A task that was killed mid-flight leaves a half-finished transcript on
     * disk; without this, reopening the page starts blank and the user cannot
     * tell whether the work happened.
     *
     * Lookup goes through [ChatHistoryManager.listConversations] rather than a
     * computed path: save() names the file after the date and the first user
     * message, so the id only exists inside the file's frontmatter. Filtering by
     * id — and taking the newest match — is what makes this correct when the
     * first message differs between saves.
     */
    private fun restoreChat() {
        runCatching {
            val match = ChatHistoryManager.listConversations(this)
                .firstOrNull { it.id == PREVIEW_CONVERSATION_ID }
                ?: return
            sink.restore(ChatHistoryManager.load(match.file))
            XLog.i(TAG, "restoreChat: reloaded '${match.title}'")
        }.onFailure {
            XLog.e(TAG, "restoreChat failed: ${it.message}", it)
        }
    }

    /**
     * JS → Kotlin: the composer hands us the text and we run a real task.
     *
     * Called on the WebView's JavaScript thread, so everything the task flow
     * touches is marshalled to the main thread first.
     */
    private val chatBridge = object {
        @JavascriptInterface
        fun send(text: String?) {
            val payload = text?.trim().orEmpty()
            if (payload.isEmpty()) return
            XLog.e(TAG, "[chat] send(${payload.length} chars)")
            runOnUiThread { taskFlowController.sendTask(payload) }
        }

        /**
         * JS → Kotlin: the user answered the MCP confirmation card.
         *
         * The whole point of the loop: this is the half that unparks the agent
         * thread blocked inside `ToolRegistry.executeTool`. Safe to call when
         * nothing is waiting — a stale tap is logged and dropped by the
         * coordinator rather than answering a later request.
         */
        @JavascriptInterface
        fun decideMcp(decision: String?) {
            val parsed = when (decision?.trim()?.lowercase()) {
                "allow_once" -> McpConfirmationGate.Decision.ALLOW_ONCE
                "allow_always" -> McpConfirmationGate.Decision.ALLOW_ALWAYS
                "deny" -> McpConfirmationGate.Decision.DENY
                else -> {
                    XLog.e(TAG, "[chat] decideMcp('$decision') ignored — not a decision")
                    return
                }
            }
            XLog.e(TAG, "[chat] decideMcp($parsed)")
            McpConfirmationCoordinator.submit(parsed)
        }

        /**
         * JS → Kotlin: open one of the native settings editors.
         *
         * The page's settings sheet is presentation only — every row lands in
         * the native activity that owns the real behaviour, so the config logic
         * stays in exactly one place. The sheet is organized in the same
         * sections as the native settings hub (配置 / 功能 / 外观 / 系统);
         * there is no catch-all row any more — settings nested inside settings
         * was pure noise. An unknown section is logged and dropped rather than
         * guessed at.
         */
        @JavascriptInterface
        fun openSettingsPage(section: String?) {
            val target = when (section) {
                "model" -> ModelSettingsActivity::class.java
                "channels" -> ChannelsActivity::class.java
                "monitor" -> MonitorActivity::class.java
                "tools" -> ToolsActivity::class.java
                "mcp" -> McpServersActivity::class.java
                "skills" -> UserSkillsActivity::class.java
                "memory" -> MemoryActivity::class.java
                "theme" -> ThemeActivity::class.java
                "about" -> AboutActivity::class.java
                // Legacy mapping kept for older cached pages: the hub is the
                // native full-settings door the Compose chat screen also uses.
                "all" -> SettingsActivity::class.java
                else -> {
                    XLog.e(TAG, "[chat] openSettingsPage('$section') ignored — unknown section")
                    return
                }
            }
            XLog.e(TAG, "[chat] openSettingsPage($section)")
            runOnUiThread {
                // `this` inside the bridge object is the bridge, not the
                // activity — the Intent constructor needs the qualified label.
                startActivity(Intent(this@ClaudeUiPreviewActivity, target))
            }
        }

        /**
         * JS → Kotlin: current theme preferences as JSON
         * (`{"mode":"dark","base":"clay","accent":"clay"}`).
         *
         * KVUtils is the single source of truth: the native 主题 page writes
         * it, the sidebar popover and this page read it. An empty string means
         * "nothing stored yet" — cds.js then falls back to its localStorage
         * copy (pure-browser preview) or the system color scheme.
         */
        @JavascriptInterface
        fun readThemePrefs(): String {
            val mode = KVUtils.getString("THEME_MODE", "")
            val base = KVUtils.getString("THEME_BASE", "")
            val accent = KVUtils.getString("THEME_ACCENT", "")
            if (mode.isBlank() && base.isBlank() && accent.isBlank()) return ""
            return buildString {
                append('{')
                if (mode.isNotBlank()) append("\"mode\":\"").append(mode).append('"')
                if (base.isNotBlank()) {
                    if (length > 1) append(',')
                    append("\"base\":\"").append(base).append('"')
                }
                if (accent.isNotBlank()) {
                    if (length > 1) append(',')
                    append("\"accent\":\"").append(accent).append('"')
                }
                append('}')
            }
        }

        /**
         * JS → Kotlin: the sidebar popover changed one or more theme axes.
         *
         * Writes straight through to the same KVUtils keys the native 主题
         * page owns, so the two doors cannot disagree. A malformed payload is
         * logged and dropped — never partially applied.
         */
        @JavascriptInterface
        fun writeThemePrefs(json: String?) {
            if (json.isNullOrBlank()) return
            try {
                val obj = org.json.JSONObject(json)
                if (obj.has("mode")) {
                    val mode = obj.getString("mode")
                    if (mode == "light" || mode == "dark") KVUtils.putString("THEME_MODE", mode)
                }
                if (obj.has("base")) KVUtils.putString("THEME_BASE", obj.getString("base"))
                if (obj.has("accent")) KVUtils.putString("THEME_ACCENT", obj.getString("accent"))
                lastPushedPrefs = currentPrefsFingerprint()
                XLog.e(TAG, "[chat] writeThemePrefs($json)")
            } catch (e: Exception) {
                XLog.e(TAG, "[chat] writeThemePrefs('$json') failed: ${e.message}")
            }
        }

        /**
         * JS → Kotlin: current task-gear preferences as JSON
         * (`{"effort":"HIGH","autoAllow":false}`).
         */
        @JavascriptInterface
        fun readTaskPrefs(): String =
            "{\"effort\":\"${KVUtils.getEffortLevel()}\"," +
                "\"autoAllow\":${KVUtils.isAutoAllowOperations()}}"

        /** JS → Kotlin: the composer's effort gear. Maps to maxIterations. */
        @JavascriptInterface
        fun setEffort(level: String?) {
            val normalized = level?.trim()?.uppercase()
            if (normalized.isNullOrBlank() ||
                !EffortLevel.entries.any { it.key == normalized }
            ) {
                XLog.e(TAG, "[chat] setEffort('$level') ignored — unknown level")
                return
            }
            KVUtils.setEffortLevel(normalized)
            lastPushedPrefs = currentPrefsFingerprint()
            XLog.e(TAG, "[chat] setEffort($normalized)")
        }

        /** JS → Kotlin: the composer's sensitive-operation policy. */
        @JavascriptInterface
        fun setAutoAllow(enabled: Boolean) {
            KVUtils.setAutoAllowOperations(enabled)
            lastPushedPrefs = currentPrefsFingerprint()
            XLog.e(TAG, "[chat] setAutoAllow($enabled)")
        }

        /**
         * JS → Kotlin: the task ledger page's list, as JSON.
         *
         * Read path of the 部二/部一 seam: the page never touches a store
         * directly, it asks this bridge. [TaskLedgerBridge] maps the ledger
         * to the page's contract and degrades to an honest empty array when
         * the read fails — never to fabricated records.
         */
        @JavascriptInterface
        fun readTaskList(): String = TaskLedgerBridge.readList()

        /** JS → Kotlin: one task's detail (step ledger included), or `null`. */
        @JavascriptInterface
        fun readTaskDetail(id: String?): String = TaskLedgerBridge.readDetail(id)

        /**
         * JS → Kotlin: the schedule queue's list, as JSON.
         *
         * The schedule page's read path, same seam shape as [readTaskList]:
         * [ScheduleLedgerBridge] maps the queue and degrades to an honest
         * empty array on a read failure — never to fabricated tasks.
         */
        @JavascriptInterface
        fun readScheduleList(): String = ScheduleLedgerBridge.readList()

        /** JS → Kotlin: one scheduled task's full intent, or `null`. */
        @JavascriptInterface
        fun readScheduleDetail(id: String?): String = ScheduleLedgerBridge.readDetail(id)

        /**
         * JS → Kotlin: cancel a live scheduled task — the two-step confirm's
         * second step.
         *
         * Marks CANCELLED in the store *and* disarms the alarm: a cancel that
         * left the alarm armed would fire a task the user was just told was
         * cancelled. Returns false for an unknown or already-terminal id,
         * which the page reports rather than hides.
         */
        @JavascriptInterface
        fun cancelSchedule(id: String?): Boolean {
            val wanted = id?.trim()
            if (wanted.isNullOrEmpty()) {
                XLog.e(TAG, "[chat] cancelSchedule('$id') ignored — no id")
                return false
            }
            val cancelled = ScheduledTaskStore.cancel(wanted)
            if (cancelled) TaskScheduler.disarm(this@ClaudeUiPreviewActivity, wanted)
            XLog.e(TAG, "[chat] cancelSchedule($wanted) -> $cancelled")
            return cancelled
        }

        /**
         * JS → Kotlin: device-level schedule facts as JSON
         * (`{"exactAlarms":true}`).
         *
         * When the OS refuses exact alarms the queue still arms — inexact —
         * and the page must say so instead of letting a 07:30 brief silently
         * drift (BACKLOG P3). The same helper arms the alarms, so the badge
         * cannot disagree with the behaviour.
         */
        @JavascriptInterface
        fun readSchedulePrefs(): String =
            "{\"exactAlarms\":${TaskScheduler.canScheduleExactAlarms(this@ClaudeUiPreviewActivity)}}"

        /**
         * JS → Kotlin: the task page's level — "closed" / "list" / "detail".
         *
         * Mirrored here so BACK can be answered synchronously off [tasksLevel];
         * an evaluateJavascript round-trip cannot join the synchronous BACK
         * chain. Every transition reports, so the mirror cannot drift.
         */
        @JavascriptInterface
        fun notifyTasksView(level: String?) {
            // 显式判空：`normalized !in TASKS_LEVELS` 对 null 也成立（null 不在
            // 集合里），编译器不会据此智能转型——少了这个 == null，赋值处就是
            // String? → String 的编译错误。
            val normalized = level?.trim()?.lowercase()
            if (normalized == null || normalized !in TASKS_LEVELS) {
                XLog.e(TAG, "[chat] notifyTasksView('$level') ignored — unknown level")
                return
            }
            runOnUiThread { tasksLevel = normalized }
            XLog.e(TAG, "[chat] notifyTasksView($normalized)")
        }

        /**
         * JS → Kotlin: the schedule page's level — "closed" / "list" / "detail".
         *
         * Mirror of [notifyTasksView] for the schedule sheet: BACK reads it
         * off [schedulesLevel], so every transition must report.
         */
        @JavascriptInterface
        fun notifySchedulesView(level: String?) {
            val normalized = level?.trim()?.lowercase()
            if (normalized == null || normalized !in SCHEDULES_LEVELS) {
                XLog.e(TAG, "[chat] notifySchedulesView('$level') ignored — unknown level")
                return
            }
            runOnUiThread { schedulesLevel = normalized }
            XLog.e(TAG, "[chat] notifySchedulesView($normalized)")
        }

        /**
         * JS → Kotlin: the page's settings sheet opened or closed.
         *
         * Only used for BACK handling: while the sheet is up, BACK should
         * close it instead of leaving the app. The page notifies on every
         * transition (open, X button, scrim tap, row navigation never closes
         * it) so the flag cannot drift from what the user sees.
         */
        @JavascriptInterface
        fun notifySettingsView(open: Boolean) {
            XLog.e(TAG, "[chat] notifySettingsView($open)")
            runOnUiThread { settingsViewOpen = open }
        }
    }

    /**
     * Answers MCP confirmations while this page is what the user is looking at.
     *
     * Both this activity and `ComposeChatActivity` register a surface; the
     * coordinator keeps whichever attached last, so the card lands on the
     * screen the user is actually on. [isForeground] is what turns "attached
     * but backgrounded" into a refusal instead of a 60-second wait nobody can
     * see.
     */
    private val confirmationSurface = object : McpConfirmationCoordinator.Surface {

        @Volatile
        override var isForeground: Boolean = false

        override fun showConfirmation(request: McpConfirmationRequest) {
            // The coordinator calls this from the agent thread.
            runOnUiThread {
                taskFlowController.noteSystem(McpConfirmationGate.pendingNote(request))
                sink.showMcpConfirmation(request)
            }
        }

        override fun hideConfirmation(verdict: McpConfirmationGate.Verdict) {
            runOnUiThread {
                sink.hideMcpConfirmation()
                taskFlowController.noteSystem(McpConfirmationGate.transcriptNote(verdict))
            }
        }
    }

    /**
     * Kotlin → JS: hand the page a full snapshot to draw.
     *
     * A snapshot rather than a diff. The message count per task is small, and a
     * missed delta desyncs the page with no way to recover — a full redraw can
     * always be re-derived from [WebChatSink]'s list.
     */
    private fun publish(messages: List<WebChatSink.WebMessage>) {
        if (!::webView.isInitialized) return
        val json = buildString {
            append('[')
            messages.forEachIndexed { i, m ->
                if (i > 0) append(',')
                appendJsonObject(m)
            }
            append(']')
        }
        // The snapshot is spliced in as a literal rather than handed over as a
        // string: evaluateJavascript takes source text, so a quoted string
        // would have to be unquoted and re-parsed on the far side anyway.
        // No result callback — renderLiveRaw returns undefined; if the page
        // throws, the error surfaces via the console bridge instead.
        webView.post {
            webView.evaluateJavascript("window.__cds && window.__cds.renderLiveRaw($json);", null)
        }
    }

    /**
     * Kotlin → JS: put the MCP confirmation card on screen, or take it down
     * with null.
     *
     * Deliberately a separate call from [publish]: the feed redraw starts by
     * emptying `#feed`, so drawing the card in the same `evaluateJavascript`
     * would delete it. The page therefore renders the card *after* the feed,
     * and this method is the only thing that touches it.
     */
    private fun publishConfirmation(request: McpConfirmationRequest?) {
        if (!::webView.isInitialized) return
        val json = if (request == null) {
            "null"
        } else {
            buildString {
                append("{\"toolName\":"); appendJsonString(request.toolName)
                append(",\"displayName\":"); appendJsonString(request.displayName)
                append(",\"serverName\":"); appendJsonString(request.serverName)
                append(",\"description\":"); appendJsonString(request.description)
                append(",\"paramsPreview\":"); appendJsonString(request.paramsPreview)
                append("}")
            }
        }
        webView.post {
            webView.evaluateJavascript(
                "window.__cds && window.__cds.renderConfirmation($json);", null
            )
        }
    }

    /**
     * Kotlin → JS: push the current ledger to the task page.
     *
     * Full snapshot, not a delta — the same reasoning as [publish]: the page
     * rebuilds its list from whatever it is handed, and a missed delta has
     * no recovery path while a snapshot can always be re-derived from the
     * store. Called on every ledger change (a step landing, a status flip);
     * the page ignores it while closed and re-reads on its next open, so a
     * backgrounded surface costs one small evaluateJavascript per event.
     */
    private fun publishTaskLedger() {
        if (!::webView.isInitialized) return
        // Gson 不转义 U+2028/U+2029，而它们在 JS 源码里是（ES2019 前仍是）
        // 断行符——任务文本里出现一个，整段 evaluateJavascript 就是语法错误，
        // 推送静默全丢。转义成 \uXXXX 序列：JSON 值不变，JS 源码安全。
        // 既有的 appendJsonString 对消息流也做同样两行，同理。
        val json = TaskLedgerBridge.readList()
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        webView.post {
            webView.evaluateJavascript("window.__cds && window.__cds.renderTasks($json);", null)
        }
    }

    /**
     * Kotlin → JS: push the current queue to the schedule page.
     *
     * Full snapshot for the same reason as [publishTaskLedger] — the page
     * rebuilds its list from what it is handed, and a missed delta has no
     * recovery path. Fired on every queue change (armed / cancelled /
     * queued / fired / outcome landed); the page ignores it while closed
     * and re-reads on its next open.
     */
    private fun publishScheduleLedger() {
        if (!::webView.isInitialized) return
        // 同样的 U+2028/U+2029 纪律，理由见 publishTaskLedger：任务文本是
        // 用户原话，一个断行符就能让整段 evaluateJavascript 静默变语法错误。
        val json = ScheduleLedgerBridge.readList()
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        webView.post {
            webView.evaluateJavascript("window.__cds && window.__cds.renderSchedules($json);", null)
        }
    }

    /** Minimal JSON writer — messages are plain strings, never user-supplied HTML. */
    private fun StringBuilder.appendJsonObject(m: WebChatSink.WebMessage) {
        append("{\"role\":"); appendJsonString(m.role)
        append(",\"content\":"); appendJsonString(m.content)
        append(",\"isTypingIndicator\":").append(m.isTypingIndicator)
        append(",\"modelName\":"); if (m.modelName == null) append("null") else appendJsonString(m.modelName)
        append(",\"toolSteps\":[")
        m.toolSteps.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"name\":"); appendJsonString(s.name)
            append(",\"summary\":"); appendJsonString(s.summary)
            append(",\"success\":").append(s.success)
            if (s.memories.isNotEmpty()) {
                append(",\"memories\":[")
                s.memories.forEachIndexed { j, chip ->
                    if (j > 0) append(',')
                    append("{\"id\":"); appendJsonString(chip.id)
                    append(",\"kind\":"); appendJsonString(chip.kindLabel)
                    append(",\"content\":"); appendJsonString(chip.content)
                    append('}')
                }
                append(']')
            }
            append('}')
        }
        append("]}")
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    append("\\u%04x".format(c.code))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    /**
     * JS 桥，唯一目的是给 localStorage 配一个**已知为真**的对照组。
     *
     * SharedPreferences 一定持久，localStorage 不一定。当两者在同一张截图里
     * 并排显示时，「上次选了什么」就不再依赖被测系统自己的记忆：
     *
     *   原生记住=sage  而  localStorage=clay  → WebView 存储被清，写入丢失
     *   原生记住=clay  而  localStorage=sage  → 写进去了，读回来却用错值
     *   两者都=clay                            → 写入路径根本没走到
     *
     * 没有对照组就分不清「没写」和「写丢了」——两者在页面外观上完全一样。
     */
    private val qaBridge = object {
        @JavascriptInterface
        fun report(json: String?, accent: String?, mode: String?) {
            XLog.e(TAG, "[cdsQA] $json")
            getSharedPreferences(QA_PREFS, MODE_PRIVATE).edit()
                .putString(QA_PREFS_JSON, json)
                .putString(QA_PREFS_ACCENT, accent)
                .putString(QA_PREFS_MODE, mode)
                .putLong(QA_PREFS_AT, System.currentTimeMillis())
                .apply()
        }

        /** 上次离开时页面实际生效的配色，`accent|mode`。读在写之前，见 cds.js 的注释。 */
        @JavascriptInterface
        fun lastState(): String? {
            val p = getSharedPreferences(QA_PREFS, MODE_PRIVATE)
            if (!p.contains(QA_PREFS_ACCENT)) return null
            return "${p.getString(QA_PREFS_ACCENT, "?")}|${p.getString(QA_PREFS_MODE, "?")}"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // adjustResize is declared in the manifest; re-assert it here because
        // BaseActivity may set its own soft-input mode.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        )

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            // Without this the whole mockup is inert: cds.js drives theme
            // persistence, scroll pinning, rate-limit backoff and the model
            // switcher. The HTML shell still rendered, so this looked fine.
            settings.javaScriptEnabled = true
            // localStorage-backed theme persistence needs DOM storage enabled
            settings.domStorageEnabled = true
            addJavascriptInterface(qaBridge, "PokeClawQA")
            addJavascriptInterface(chatBridge, "PokeClawChat")
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            // The mockup is entirely local; never let it navigate off-device.
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ) = assetLoader.shouldInterceptRequest(request.url)

                @Suppress("OverridingDeprecatedMember")
                override fun shouldInterceptRequest(
                    view: WebView,
                    url: String
                ) = assetLoader.shouldInterceptRequest(Uri.parse(url))

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = true
            }
            // Without a WebChromeClient the page's console output never leaves
            // the renderer — `adb logcat` shows nothing, which is NOT evidence
            // that localStorage worked. cds.js signals storage failures with
            // console.warn, so forward it and the question becomes answerable.
            //
            // XLog.e, not XLog.i/w: ClawApplication.onCreate calls
            // XLog.setDEBUG(BuildConfig.DEBUG), so in a release build every
            // level except e/wtf is stripped from logcat. QA runs release
            // packages (a debug build cannot overwrite-install, see
            // CLAUDE.local.md §13.2), so anything else here is invisible.
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    XLog.e(TAG, "[web] ${msg.messageLevel()} ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                    return true
                }
            }
            // A desktop-width layout in a narrow WebView: without this the page
            // auto-zooms and the composer drifts off the bottom.
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            // A design-review surface that serves a *stale* page is worse than
            // one that fails to load: the asset URL never changes, so the
            // WebView resource cache can hand back the previous build's page
            // and the reviewer ends up judging a build that is not installed.
            //
            // clearCache(true) evicts the cache; the version query makes the
            // cache key unique per build, so even a page that survives the
            // clear is fetched fresh. The origin is unchanged, so the
            // localStorage-backed theme state still survives.
            //
            // This does NOT explain the `设备截图` / `正在截取屏幕…` strings
            // read off a device screenshot (QA H24): those appear in no
            // commit and in no packaged asset, so no cached page could have
            // carried them either. That observation is settled as a misread
            // of 11 px CJK — see QA_CHECKLIST H24.
            // `this` is the WebView being built. The activity's own `webView`
            // property is not assigned until this expression returns, so
            // touching it here throws UninitializedPropertyAccessException —
            // and a crashed preview activity drops the user back to the chat
            // screen, which looks like "the entry does nothing".
            this.clearCache(true)
            loadUrl("$MOCKUP_URL?v=${BuildConfig.VERSION_CODE}")
        }
        // Pad the web surface down by the system-bar/cutout inset. Two traps,
        // both measured on the V2502DA: (1) BaseActivity's status-bar pass
        // (BarUtils at decorView.post) never landed here, so the page rendered
        // edge-to-edge with its topbar under the status bar (StatusBar
        // touchableRegion [0,0][1260,123] vs hamburger bounds y=30..128) —
        // harmless while the topbar held no live controls, fatal once the
        // hamburger became the only nav. (2) A WebView IGNORES its own padding
        // when rendering content (build 44 set it and the page did not move),
        // so the padding goes on a wrapper container instead. Insets are the
        // authoritative source, and BaseActivity's later pass no-ops: its
        // guard only raises padding smaller than the status bar.
        val chrome = FrameLayout(this)
        chrome.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(chrome) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            if (bars.top > v.paddingTop) v.updatePadding(top = bars.top)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(chrome)

        // BACK while the page's settings sheet is open closes the sheet
        // instead of leaving the app. The flag is maintained by the page
        // itself (notifySettingsView); the JS call lands asynchronously, so
        // the sheet's own close handler reports the transition.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 三个全屏 sheet 互为互斥（开一个就关其余两个），但 BACK 的
                // 判据仍是各自的层级镜像，谁在上谁先应答。定时页与任务页
                // 同代 z-index，顺序只影响可读性；「详情回列表」还是「关页」
                // 由页面自己的 *Back() 决定，Kotlin 只负责转交。
                if (schedulesLevel != SCHEDULES_LEVEL_CLOSED) {
                    webView.evaluateJavascript(
                        "window.__cds && window.__cds.schedulesBack();", null
                    )
                } else if (tasksLevel != TASKS_LEVEL_CLOSED) {
                    webView.evaluateJavascript(
                        "window.__cds && window.__cds.tasksBack();", null
                    )
                } else if (settingsViewOpen) {
                    webView.evaluateJavascript(
                        "window.__cds && window.__cds.closeSettingsView();", null
                    )
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        // Scheduled AFTER setContentView, not inside the apply{} above: a
        // View that is not yet attached has no handler to drain its message
        // queue, so postDelayed there silently never runs. That produced a
        // probe which fired on the first launch and never again — the exact
        // absence-of-evidence failure mode this diagnostic exists to avoid.
        webView.postDelayed({ dumpStorage() }, PROBE_DELAY_MS)

        // Restoring has to wait for the page too, for the same reason as the
        // probe above: sink.restore() emits immediately, and that emit calls
        // window.__cds.renderLiveRaw — which does not exist until cds.js has
        // finished loading. Restore after, never before.
        webView.postDelayed({ restoreChat() }, RESTORE_DELAY_MS)
        // 从 Compose 聊天页的 ActiveTaskBar 进来时，直接翻开任务页。
        // 同样的延迟纪律：__cds 要等 cds.js 加载完才存在。
        if (intent.getBooleanExtra(EXTRA_SHOW_TASKS, false)) {
            webView.postDelayed({
                webView.evaluateJavascript(
                    "window.__cds && window.__cds.openTasksView();", null
                )
            }, RESTORE_DELAY_MS)
            XLog.e(TAG, "onCreate: opened with the task ledger requested")
        }
        // 同样的深链纪律给定时队列：外部（或 QA）可以直接翻开定时页。
        if (intent.getBooleanExtra(EXTRA_SHOW_SCHEDULES, false)) {
            webView.postDelayed({
                webView.evaluateJavascript(
                    "window.__cds && window.__cds.openSchedulesView();", null
                )
            }, RESTORE_DELAY_MS)
            XLog.e(TAG, "onCreate: opened with the schedule queue requested")
        }
        // e, not i — see the XLog.setDEBUG note on the chrome client below. In a
        // release build XLog.i never reaches logcat, and QA runs release
        // packages, so an `i` here would make the checklist's "the page loaded
        // this URL" assertion unobservable on the only build QA can install.
        XLog.e(TAG, "onCreate: loaded $MOCKUP_URL?v=${BuildConfig.VERSION_CODE}")

        // 账本变化 → 任务页推送。整个 Activity 生命周期注册：页面关着时
        // 推送只更新侧栏徽标（renderTasks 的 closed 分支），开着时刷新列表
        // 或详情——两条路都由页面自己决定，这里只负责把变化送到。
        TaskRecordStore.addSink(taskLedgerSink)

        // 队列变化 → 定时页推送。与账本 sink 同生命周期注册：页面关着时
        // 推送只更新侧栏徽标（renderSchedules 的 closed 分支），开着时刷新
        // 列表或详情。
        ScheduledTaskStore.addSink(scheduleLedgerSink)

        // Registered at runtime rather than declared in the manifest: a
        // manifest receiver here would have to be exported to be reachable by
        // adb, which would hand any app on the device a way to drive this page.
        qaReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_SET_ACCENT -> setAccentForQa(intent.getStringExtra("key") ?: return)
                    ACTION_DUMP -> dumpDom()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SET_ACCENT)
            addAction(ACTION_DUMP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(qaReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(qaReceiver, filter)
        }
    }

    /**
     * QA hook: `adb shell am broadcast -a <PKG>.SET_ACCENT --es key sage`.
     *
     * Tapping the swatch by coordinate proved unreliable (uiautomator cannot
     * see inside the WebView, and a missed tap silently leaves the picker
     * closed — indistinguishable from "the theme did not change"). Driving
     * cds.js's own `__cds.setAccent` exercises the identical write path the UI
     * does, so a failure here is a storage failure, not a tap failure.
     */
    @Suppress("unused")
    fun setAccentForQa(key: String) {
        val js = "window.__cds && window.__cds.setAccent('$key');" +
            "'dispatched';"
        webView.evaluateJavascript(js) { value ->
            XLog.e(TAG, "setAccent($key) -> $value")
            dumpStorage()
        }
    }

    /**
     * One-shot DOM probe: `adb shell am broadcast -a <PKG>.DUMP_DOM`.
     *
     * Screenshots showed cds.js source text painted at the top of the page while
     * cds.js demonstrably executed (its console output reached logcat). Those two
     * facts are contradictory, and guessing between them wastes a build each
     * time — so read the document itself: script count, each script's src, and
     * whether the raw source appears as a text node.
     */
    private fun dumpDom() {
        val js = (
            "var o={};" +
                "o.url=String(location.href);" +
                "o.scripts=document.scripts.length;" +
                "var srcs=[];for(var i=0;i<document.scripts.length;i++){" +
                "srcs.push(String(document.scripts[i].src||'(inline)'));}" +
                "o.srcs=srcs.join(',');" +
                "var panel=document.getElementById('cdsQaPanel');" +
                "o.panelExists=!!panel;" +
                "o.panelChildNodes=panel?panel.childNodes.length:-1;" +
                "o.panelSpans=panel?panel.querySelectorAll('span').length:-1;" +
                "o.panelText=panel?String(panel.textContent).slice(0,120):'';" +
                "o.bodyStart=String(document.body.innerHTML).slice(0,160);" +
                "o.asideExists=!!document.querySelector('aside');" +
                "o.feedExists=!!document.getElementById('feed');" +
                "o.cdsExists=!!window.__cds;" +
                "o.effort=String(window.__cds&&window.__cds.state?window.__cds.state().effort:'');" +
                "o.policy=String(window.__cds&&window.__cds.state?window.__cds.state().policy:'');" +
                // 任务页状态：uiautomator 看不见 WebView 内部，任务页的
                // 开合/层级/卡数只能从这里取证（QA AD 组的 ground truth）。
                "o.tasksOpen=String(!!document.querySelector('#tasksView[data-open=\"true\"]'));" +
                "o.tasksLevel=String(window.__cds&&window.__cds.tasksLevel?window.__cds.tasksLevel():'');" +
                "o.taskCards=document.querySelectorAll('.task-card').length;" +
                // 定时页取证，与任务页同一套路（QA H67 的 ground truth）。
                "o.schedulesOpen=String(!!document.querySelector('#schedulesView[data-open=\"true\"]'));" +
                "o.schedulesLevel=String(window.__cds&&window.__cds.schedulesLevel?window.__cds.schedulesLevel():'');" +
                "o.scheduleCards=document.querySelectorAll('.schedule-card').length;" +
                "JSON.stringify(o);"
            )
        webView.evaluateJavascript(js) { value ->
            XLog.e(TAG, "domDump: $value")
        }
    }

    override fun onResume() {
        super.onResume()
        // This page is the screen the user is looking at, so it is the surface
        // that answers MCP confirmations. Two activities register; the
        // coordinator keeps the last one attached.
        confirmationSurface.isForeground = true
        McpConfirmationCoordinator.attach(confirmationSurface)
        // The user may have changed theme or the two task gears on a native
        // page (主题 / 模型与任务) while this activity was stopped. The page
        // itself is not reloaded on resume, so push whatever changed — KVUtils
        // is the source of truth, the page's localStorage is only a cache.
        syncPrefsToPage()
    }

    /**
     * Fingerprint of every preference the page mirrors. Compared on resume;
     * a difference means a native page changed something and the page must be
     * told. The bridge's write methods refresh it too, so a change made *on
     * the page* never counts as "changed elsewhere".
     */
    private fun currentPrefsFingerprint(): String =
        listOf(
            KVUtils.getString("THEME_MODE", ""),
            KVUtils.getString("THEME_BASE", ""),
            KVUtils.getString("THEME_ACCENT", ""),
            KVUtils.getEffortLevel(),
            KVUtils.isAutoAllowOperations().toString(),
        ).joinToString("|")

    private var lastPushedPrefs: String = ""

    private fun syncPrefsToPage() {
        if (!::webView.isInitialized) return
        val current = currentPrefsFingerprint()
        if (current == lastPushedPrefs) return
        lastPushedPrefs = current
        val prefs = chatBridge.readThemePrefs()
        val effort = KVUtils.getEffortLevel()
        val auto = KVUtils.isAutoAllowOperations()
        webView.post {
            webView.evaluateJavascript(
                "window.__cds && window.__cds.applyPrefs($prefs);" +
                    "window.__cds && window.__cds.setEffort('$effort');" +
                    "window.__cds && window.__cds.setAutoAllow($auto);",
                null
            )
        }
        XLog.e(TAG, "syncPrefsToPage: pushed prefs=$prefs effort=$effort autoAllow=$auto")
    }

    override fun onPause() {
        super.onPause()
        // Stays attached — a task keeps running with the page backgrounded —
        // but can no longer show a card, which the coordinator treats as
        // "refuse now", not "wait 60 seconds for nobody".
        confirmationSurface.isForeground = false
    }

    override fun onDestroy() {
        // A wait this surface cannot answer must be refused now, not left to
        // time out against a screen that no longer exists.
        McpConfirmationCoordinator.detach(confirmationSurface)
        // 与 addSink 成对：sink 持的是 Activity 的 lambda（经 runOnUiThread
        // 碰 WebView），漏掉这一行会把一个已销毁的 Activity 泄漏给账本。
        TaskRecordStore.removeSink(taskLedgerSink)
        ScheduledTaskStore.removeSink(scheduleLedgerSink)
        qaReceiver?.let {
            runCatching { unregisterReceiver(it) }
            qaReceiver = null
        }
        // Mirrors WebActivity: a WebView that is never destroyed leaks the whole
        // Activity context along with it.
        if (::webView.isInitialized) {
            webView.destroy()
        }
        // The executor is created eagerly in a field initialiser, so every
        // open/close cycle would leave one idle thread behind. ComposeChatActivity
        // shuts its own down in onDestroy; this surface has to as well.
        executor.shutdown()
        super.onDestroy()
    }

    private fun dumpStorage() {
        // One-shot diagnostic. Reads the three keys cds.js owns AND the
        // attributes it actually applied to <html>. Comparing the two is what
        // separates the two candidate causes: if localStorage holds the new
        // accent but data-theme is still the old one, the read-back is broken;
        // if localStorage still holds the default, the write never happened.
        // Both look identical from the outside — the theme just resets.
        val js = (
            "var o={};" +
                "['cds-mockup-theme','cds-mockup-accent','cds-mockup-base'].forEach(function(k){" +
                "try{o[k]=String(localStorage.getItem(k));}catch(e){o[k]='THREW:'+e;}});" +
                "try{o['applied-mode']=String(document.documentElement.getAttribute('data-mode'));" +
                "o['applied-theme']=String(document.documentElement.getAttribute('data-theme'));" +
                "o['applied-base']=String(document.documentElement.getAttribute('data-base'));" +
                "o['href']=String(location.href);o['origin']=String(location.origin);" +
                "o['keys']=String(localStorage.length);}catch(e){o['attr']='THREW:'+e;}" +
                "JSON.stringify(o);"
            )
        webView.evaluateJavascript(js) { value ->
            // e, not i — see the XLog.setDEBUG note on the chrome client above.
            XLog.e(TAG, "storageProbe: $value")
        }
    }

    companion object {
        private const val TAG = "ClaudeUiPreview"

        /**
         * Fixed id for this surface's transcript.
         *
         * Not shared with the chat screen: see [saveChat] for why going through
         * ConversationStore would make the two surfaces overwrite each other.
         */
        private const val PREVIEW_CONVERSATION_ID = "preview_claude_ui"
        private const val ASSET_DOMAIN = "appassets.androidplatform.net"
        private const val PROBE_DELAY_MS = 2500L

        /**
         * Set by the Compose chat's ActiveTaskBar entry: open this surface
         * straight into the task ledger page. Without it the page opens on
         * the chat feed, which is the right default for every other entry.
         */
        const val EXTRA_SHOW_TASKS = "show_tasks"

        /**
         * Set by an external caller (or QA): open this surface straight into
         * the schedule queue page. Same deep-link discipline as
         * [EXTRA_SHOW_TASKS].
         */
        const val EXTRA_SHOW_SCHEDULES = "show_schedules"

        /** Task-page levels the page reports through `notifyTasksView`. */
        private val TASKS_LEVELS = setOf("closed", "list", "detail")
        private const val TASKS_LEVEL_CLOSED = "closed"

        /** Schedule-page levels the page reports through `notifySchedulesView`. */
        private val SCHEDULES_LEVELS = setOf("closed", "list", "detail")
        private const val SCHEDULES_LEVEL_CLOSED = "closed"

        /**
         * Later than [PROBE_DELAY_MS] on purpose: the probe proves the page is
         * alive at 2.5s, and the restore has to land strictly after it so a
         * slow first load cannot race. Ordering the two delays (rather than
         * using one callback) keeps the probe's diagnostic value intact.
         */
        private const val RESTORE_DELAY_MS = 3000L
        private const val ACTION_SET_ACCENT = "io.agents.pokeclaw.SET_ACCENT"
        private const val ACTION_DUMP = "io.agents.pokeclaw.DUMP_DOM"
        private const val QA_PREFS = "claude_ui_qa"
        private const val QA_PREFS_JSON = "last_report"
        private const val QA_PREFS_ACCENT = "last_accent"
        private const val QA_PREFS_MODE = "last_mode"
        private const val QA_PREFS_AT = "last_seen_at"

        /**
         * Served over HTTPS, NOT `file:///android_asset/...`.
         *
         * `file://` is an **opaque origin** in Chromium, so `localStorage` is
         * unavailable there. `cds.js` wraps every access in try/catch and falls
         * back to the default theme, so the symptom was a mockup that switches
         * theme perfectly but silently forgets it on every reopen — and because
         * the fallback is silent, it looks like a rendering bug rather than a
         * storage one.
         *
         * WebViewAssetLoader serves the same packaged assets over
         * `https://appassets.androidplatform.net/`, which is a real, persistent
         * origin, so DOM storage works as the page expects. It also never touches
         * the network — the loader intercepts before any socket is opened.
         */
        private const val MOCKUP_URL =
            "https://appassets.androidplatform.net/assets/claude-ui/index.html"
    }

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }
}
