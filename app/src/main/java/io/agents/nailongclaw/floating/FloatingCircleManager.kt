// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.floating

import android.app.Application
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.blankj.utilcode.util.ThreadUtils
import io.agents.nailongclaw.ClawApplication
import io.agents.nailongclaw.R
import io.agents.nailongclaw.channel.Channel
import io.agents.nailongclaw.utils.XLog
import io.agents.nailongclaw.utils.KVUtils
import com.blankj.utilcode.util.BarUtils
import com.lzf.easyfloat.EasyFloat
import com.lzf.easyfloat.enums.ShowPattern
import com.lzf.easyfloat.enums.SidePattern
import com.lzf.easyfloat.interfaces.OnFloatCallbacks
import com.lzf.easyfloat.utils.DisplayUtils

/**
 * Circular floating window manager
 * Uses EasyFloat to implement a draggable floating window that remembers its position
 * Supports multiple states: waiting for task (IDLE), task running (RUNNING), task succeeded (SUCCESS), task failed (ERROR)
 */
object FloatingCircleManager {

    private const val FLOAT_TAG = "circle_float"
    private const val KEY_FLOAT_X = "floating_circle_x"
    private const val KEY_FLOAT_Y = "floating_circle_y"
    private const val AUTO_RESET_DELAY_MS = 5000L // auto-reset after 5 seconds

    /**
     * Floating window state
     */
    enum class State {
        IDLE,           // waiting for task (default)
        TASK_NOTIFY,    // task notification received (pill expanded)
        RUNNING,        // task running
        SUCCESS,        // task completed
        ERROR           // task failed
    }

    private var isShowing = false
    private var currentState: State = State.IDLE
    private var currentRound: Int = 0
    private var currentChannel: Channel? = null
    private var currentTokenState: io.agents.nailongclaw.agent.TokenMonitor.State = io.agents.nailongclaw.agent.TokenMonitor.State.NORMAL
    /** What the agent is doing right now (tool display name), shown on the running pill. */
    private var currentAction: String = ""
    /** Result summary surfaced on the SUCCESS/ERROR pill; blank keeps the old circle. */
    private var resultSummary: String = ""
    private var resultSuccess: Boolean = true

    // ==================== 秒表 ====================
    // RUNNING/TASK_NOTIFY 期间每秒刷新一次 tvTokenStatus（Step N · 0:19）。
    // TokenUpdate 本来就会改这行字，秒表只是把「每秒都变」变成常态——任务
    // 长时间不动时用户才有「它还活着吗」的信号。

    /** 本轮任务起点（showTaskNotify 时刻），0 = 没有正在计时的任务。 */
    private var taskStartedAtMs = 0L
    /** updateTokenStatus 带来的尾部（" | 33K | $0.01"），秒表刷新时拼回去。 */
    private var tokenSuffix: String = ""
    private var elapsedTicker: Runnable? = null

    private fun startElapsedTicker() {
        if (taskStartedAtMs == 0L) taskStartedAtMs = System.currentTimeMillis()
        if (elapsedTicker != null) return
        val ticker = object : Runnable {
            override fun run() {
                if (currentState != State.RUNNING && currentState != State.TASK_NOTIFY) {
                    elapsedTicker = null
                    return
                }
                EasyFloat.getFloatView(FLOAT_TAG)
                    ?.findViewById<TextView>(R.id.tvTokenStatus)
                    ?.text = runningStatusText()
                // 秒表文字每秒都在变宽（0:09 → 0:59 → 10:00），pill 窗口要跟着长。
                EasyFloat.getFloatView(FLOAT_TAG)?.let { syncFloatWindowSize(it) }
                mainHandler.postDelayed(this, 1000)
            }
        }
        elapsedTicker = ticker
        mainHandler.post(ticker)
    }

    private fun stopElapsedTicker() {
        elapsedTicker?.let { mainHandler.removeCallbacks(it) }
        elapsedTicker = null
    }

    private fun runningStatusText(): String {
        val elapsedSec = ((System.currentTimeMillis() - taskStartedAtMs) / 1000).coerceAtLeast(0)
        val elapsed = String.format(java.util.Locale.US, "%d:%02d", elapsedSec / 60, elapsedSec % 60)
        return "Step $currentRound · $elapsed$tokenSuffix"
    }

    private const val TASK_NOTIFY_DURATION_MS = 3000L // task notification shown for 3 seconds before collapsing

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoResetRunnable: Runnable? = null
    private var notifyCollapseRunnable: Runnable? = null
    private var pendingTaskText: String = ""

    private var appRef: Application? = null

    /**
     * Show the floating window
     * @param application Application instance
     * @param x initial X position (optional, defaults to right-center of screen)
     * @param y initial Y position (optional, defaults to screen center)
     */
    fun show(
        application: Application,
        x: Int? = null,
        y: Int? = null
    ) {
        XLog.i("FloatingCircle", "show() called, isShowing=$isShowing")
        if (isShowing || EasyFloat.getFloatView(FLOAT_TAG) != null) {
            isShowing = true
            XLog.i("FloatingCircle", "show() skipped — already exists")
            return
        }
        appRef = application

        // Calculate default position: right side of screen center
        val screenWidth = DisplayUtils.getScreenWidth(application)
        val screenHeight = DisplayUtils.getScreenHeight(application)
        val defaultX = 0
        val defaultY = screenHeight / 2

        // Read saved position from local storage
        val savedX = getSavedX() ?: x ?: defaultX
        val savedY = getSavedY() ?: y ?: defaultY

        EasyFloat.with(application)
            .setLayout(R.layout.layout_floating_circle)
            .setShowPattern(ShowPattern.ALL_TIME)
            .setSidePattern(SidePattern.DEFAULT)
            .setGravity(android.view.Gravity.START or android.view.Gravity.TOP, savedX, savedY)
            .setDragEnable(true)
            .hasEditText(false)
            .setTag(FLOAT_TAG)
            .registerCallbacks(object : OnFloatCallbacks {

                override fun createdResult(
                    isCreated: Boolean,
                    msg: String?,
                    view: View?
                ) {
                    XLog.i("FloatingCircle", "createdResult: isCreated=$isCreated, msg=$msg, view=${view != null}")
                    // Cache the original circle width (must be before any setFloatRootWidth call)
                    view?.findViewById<View>(R.id.floatRoot)?.let { root ->
                        if (circleWidthPx <= 0) {
                            circleWidthPx = root.layoutParams?.width ?: -1
                        }
                    }
                    // Click events: tap to stop if running, otherwise bring app to foreground
                    view?.setOnClickListener {
                        if (currentState == State.RUNNING || currentState == State.TASK_NOTIFY) {
                            onStopTask()
                        } else {
                            onFloatClick()
                        }
                    }
                    // Initialize state
                    updateStateView(view, currentState)
                    // 首建竞态（H84）：createdResult 是 ParentFrameLayout.onLayout
                    // 调出来的，此刻这次 measure/layout 已经跑完——在这里把
                    // floatRoot 宽度改成 WRAP_CONTENT 的 requestLayout 会被当次
                    // 布局吞掉，窗口停在创建时的 56dp，pill 内容被裁成圆球，第一
                    // 个任务的四态截图全同。等首帧过去再重放一次状态，宽度变更
                    // 才会落到一次真正的遍历里。
                    view?.post {
                        updateStateView(view, currentState)
                        syncFloatWindowSize(view)
                    }
                    // Detect position after layout to prevent the bubble from getting stuck off-screen
                    view?.post {
                        ensureFloatInBounds(view)
                    }
                }

                override fun dismiss() {
                    isShowing = false
                }

                override fun drag(view: View, event: MotionEvent) {
                }

                override fun dragEnd(view: View) {
                    // Drag ended; correct position and save
                    ensureFloatInBounds(view)
                }

                override fun hide(view: View) {
                    isShowing = false
                }

                override fun show(view: View) {
                    isShowing = true
                    // Restore UI state when overlay reappears after app switch
                    updateStateView(view, currentState)
                    syncFloatWindowSize(view)
                }

                override fun touchEvent(view: View, event: MotionEvent) {

                }
            })
            .show()
    }

    /**
     * Hide the floating window
     */
    fun hide() {
        if (isShowing) {
            EasyFloat.dismiss(FLOAT_TAG)
            isShowing = false
        }
    }

    /**
     * Check whether the floating window is currently visible
     */
    fun isShowing(): Boolean = isShowing

    /**
     * Ensure the floating window is showing. If it was dismissed (e.g. by
     * ComposeChatActivity.onCreate), re-show it so task status is visible.
     *
     * This is the only entry the task path uses — [io.agents.nailongclaw.TaskOrchestrator]
     * calls it when a task starts, on whatever thread the task arrived on (a
     * channel poller, the alarm, the UI). So it is self-sufficient: it resolves
     * its own Application reference, refuses without SYSTEM_ALERT_WINDOW
     * instead of letting EasyFloat throw, and marshals onto the main thread,
     * where window operations belong.
     */
    fun ensureShowing() {
        if (isShowing || EasyFloat.getFloatView(FLOAT_TAG) != null) return
        val app = appRef ?: ClawApplication.instance
        if (!Settings.canDrawOverlays(app)) {
            XLog.w("FloatingCircle", "ensureShowing: SYSTEM_ALERT_WINDOW not granted — no floating pill")
            return
        }
        mainHandler.post {
            if (!isShowing && EasyFloat.getFloatView(FLOAT_TAG) == null) {
                XLog.i("FloatingCircle", "ensureShowing: showing float")
                show(app)
            }
        }
    }

    /**
     * Switch to idle state (waiting for task)
     */
    fun setIdleState() {
        ThreadUtils.runOnUiThread {
            resultSummary = ""
            currentAction = ""
            stopElapsedTicker()
            taskStartedAtMs = 0L
            tokenSuffix = ""
            io.agents.nailongclaw.task.TaskRuntimeState.stop()
            setState(State.IDLE)
            hide()
        }
    }

    /**
     * Show task notification: expand the floating window to pill shape, display task content, auto-collapse to RUNNING after 3 seconds.
     * @param taskText task text (will be truncated for display)
     * @param channel source channel of the message
     */
    fun showTaskNotify(taskText: String, channel: Channel) {
        ThreadUtils.runOnUiThread {
            pendingTaskText = taskText
            currentChannel = channel
            // A new task clears the previous run's action label and result summary
            currentAction = ""
            resultSummary = ""
            // Publish to the in-app capsule feed before the view work — the
            // capsule renders from this even when the overlay is refused
            io.agents.nailongclaw.task.TaskRuntimeState.start(taskText)
            taskStartedAtMs = System.currentTimeMillis()
            tokenSuffix = ""
            startElapsedTicker()
            cancelNotifyCollapse()
            setState(State.TASK_NOTIFY)
            // Auto-collapse to RUNNING after 3 seconds (call setState directly, bypassing TASK_NOTIFY guard)
            notifyCollapseRunnable = Runnable {
                setState(State.RUNNING)
            }
            mainHandler.postDelayed(notifyCollapseRunnable!!, TASK_NOTIFY_DURATION_MS)
        }
    }

    private fun cancelNotifyCollapse() {
        notifyCollapseRunnable?.let {
            mainHandler.removeCallbacks(it)
            notifyCollapseRunnable = null
        }
    }

    /**
     * Switch to task running state
     * @param round current round number
     * @param channel source channel of the message
     */
    fun setRunningState(round: Int, channel: Channel) {
        ThreadUtils.runOnUiThread {
            currentRound = round
            currentChannel = channel
            io.agents.nailongclaw.task.TaskRuntimeState.update(currentAction, round)
            startElapsedTicker()
            // If the task notification pill is still showing, only update data; do not switch UI (wait for timer to expire)
            if (currentState == State.TASK_NOTIFY) {
                return@runOnUiThread
            }
            setState(State.RUNNING)
        }
    }

    /**
     * Update token monitor display on the running pill.
     * Call this after each agent loop iteration.
     *
     * @param step current step number
     * @param formattedTokens e.g. "33K"
     * @param formattedCost e.g. "$0.01"
     * @param tokenState NORMAL/CAUTION/WARNING/CRITICAL
     */
    fun updateTokenStatus(step: Int, formattedTokens: String, formattedCost: String, tokenState: io.agents.nailongclaw.agent.TokenMonitor.State) {
        ThreadUtils.runOnUiThread {
            currentRound = step
            currentTokenState = tokenState
            io.agents.nailongclaw.task.TaskRuntimeState.update(currentAction, step)
            val view = EasyFloat.getFloatView(FLOAT_TAG) ?: return@runOnUiThread
            val tvStatus = view.findViewById<TextView>(R.id.tvTokenStatus) ?: return@runOnUiThread
            tokenSuffix = " | $formattedTokens | $formattedCost"
            tvStatus.text = runningStatusText()
            syncFloatWindowSize(view)

            // Update pill color based on token state
            val cardRunning = view.findViewById<androidx.cardview.widget.CardView>(R.id.cardRunning) ?: return@runOnUiThread
            val colorRes = when (tokenState) {
                io.agents.nailongclaw.agent.TokenMonitor.State.NORMAL -> R.color.colorBrandPrimary
                io.agents.nailongclaw.agent.TokenMonitor.State.CAUTION -> R.color.colorWarningPrimary
                io.agents.nailongclaw.agent.TokenMonitor.State.WARNING -> R.color.colorWarningPrimary
                io.agents.nailongclaw.agent.TokenMonitor.State.CRITICAL -> R.color.colorErrorPrimary
            }
            try {
                val app = appRef ?: return@runOnUiThread
                cardRunning.setCardBackgroundColor(app.getColor(colorRes))
            } catch (_: Exception) {}
        }
    }

    /**
     * Update what the agent is doing right now, shown as a second line on the
     * running pill. No-op when the float is not showing, so callers never
     * have to check the overlay permission state first.
     */
    fun updateTaskAction(action: String) {
        ThreadUtils.runOnUiThread {
            currentAction = action
            io.agents.nailongclaw.task.TaskRuntimeState.update(action, currentRound)
            val view = EasyFloat.getFloatView(FLOAT_TAG) ?: return@runOnUiThread
            val tvAction = view.findViewById<TextView>(R.id.tvTaskAction) ?: return@runOnUiThread
            tvAction.text = if (action.length > 26) action.substring(0, 26) + "…" else action
            syncFloatWindowSize(view)
        }
    }

    /**
     * Switch to task completed state (auto-resets to IDLE after 5 seconds).
     * With a summary the float expands to a result pill so the user sees the
     * outcome wherever they are; tapping it opens the chat.
     */
    fun setSuccessState(summary: String? = null) {
        ThreadUtils.runOnUiThread {
            resultSummary = summary?.trim().orEmpty()
            resultSuccess = true
            io.agents.nailongclaw.task.TaskRuntimeState.stop()
            setState(State.SUCCESS)
            scheduleAutoReset()
        }
    }

    /**
     * Switch to task failed state (auto-resets to IDLE after 5 seconds).
     * With a summary the float expands to a result pill showing the failure.
     */
    fun setErrorState(summary: String? = null) {
        ThreadUtils.runOnUiThread {
            resultSummary = summary?.trim().orEmpty()
            resultSuccess = false
            io.agents.nailongclaw.task.TaskRuntimeState.stop()
            setState(State.ERROR)
            scheduleAutoReset()
        }

    }

    /**
     * Set state
     */
    private fun setState(state: State) {
        currentState = state
        val view = EasyFloat.getFloatView(FLOAT_TAG)
        XLog.i("FloatingCircle", "setState: $state, view=${view != null}, isShowing=$isShowing")
        view?.let {
            updateStateView(it, state)
            // 见 syncFloatWindowSize：窗口长肉不能只靠 root view 的
            // layoutParams，显式把实测尺寸写进 EasyFloat 窗口 LayoutParams。
            it.post { syncFloatWindowSize(it) }
        }
    }

    /**
     * 把 EasyFloat 窗口尺寸显式同步成 floatRoot 的目标尺寸。
     *
     * 窗口 LayoutParams 从创建起就是 WRAP_CONTENT，长肉全靠「内容
     * requestLayout → 重新 measure」这条路；而 setFloatRootWidth 只改 root
     * view 自己的 layoutParams、从不告诉窗口。首建那次状态又活在
     * ParentFrameLayout.onLayout 里（见 createdResult 的注释），requestLayout
     * 被当次布局吞掉，窗口就按创建时的 56dp 定死，pill 加宽的内容整段裁掉
     * （H84：`dumpsys window` 实测 RUNNING 期间窗口仍是 147×147）。
     *
     * 这里用 EasyFloat.updateFloat 的 width/height 把尺寸写进窗口
     * LayoutParams（-1 才是「保持不变」，其余值覆盖并 updateViewLayout），
     * 窗口大小从此不依赖 measure 管道有没有被吞。
     *
     * 尺寸取值规则：layoutParams 里写死的值优先（圆形态的 56dp），只有
     * WRAP_CONTENT 的 pill 态才用手动量出来的自然宽度。不能无脑用 wrap 测量
     * 值——cardIdle/cardSuccess 是 match_parent，父容器按 UNSPECIFIED 量时会
     * 塌到里面的 32dp 图标大小，圆球会被裁成图标角（2026-11-19 首轮修复的
     * 回归，已在此更正）。
     */
    private fun syncFloatWindowSize(view: View) {
        val root = view.findViewById<View>(R.id.floatRoot) ?: return
        val lp = root.layoutParams
        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(widthSpec, heightSpec)
        val width = if (lp != null && lp.width > 0) lp.width else root.measuredWidth
        val height = if (lp != null && lp.height > 0) lp.height else root.measuredHeight
        if (width <= 0 || height <= 0) return
        EasyFloat.updateFloat(FLOAT_TAG, -1, -1, width, height)
    }

    /**
     * Update view state
     */
    private fun updateStateView(view: View?, state: State) {
        if (view == null) return

        val cardIdle = view.findViewById<View>(R.id.cardIdle)
        val cardTaskNotify = view.findViewById<View>(R.id.cardTaskNotify)
        val cardRunning = view.findViewById<View>(R.id.cardRunning)
        val cardSuccess = view.findViewById<View>(R.id.cardSuccess)
        val cardError = view.findViewById<View>(R.id.cardError)
        val cardResult = view.findViewById<View>(R.id.cardResult)

        // Hide all state views
        cardIdle?.visibility = View.GONE
        cardTaskNotify?.visibility = View.GONE
        cardRunning?.visibility = View.GONE
        cardSuccess?.visibility = View.GONE
        cardError?.visibility = View.GONE
        cardResult?.visibility = View.GONE

        // Cancel any previous auto-reset
        cancelAutoReset()

        // Show corresponding state
        when (state) {
            State.IDLE -> {
                stopElapsedTicker()
                taskStartedAtMs = 0L
                tokenSuffix = ""
                cardIdle?.visibility = View.VISIBLE
                setFloatRootWidth(view, getCircleWidth(view))
            }
            State.TASK_NOTIFY -> {
                cardTaskNotify?.visibility = View.VISIBLE
                val tvNotify = view.findViewById<TextView>(R.id.tvTaskNotify)
                val app = appRef ?: return
                val displayText = if (pendingTaskText.length > 40) {
                    pendingTaskText.substring(0, 40) + "…"
                } else {
                    pendingTaskText
                }
                tvNotify?.text = app.getString(R.string.floating_task_received, displayText)
                val ivLogo = view.findViewById<ImageView>(R.id.ivNotifyChannelLogo)
                ivLogo?.setImageResource(getChannelIcon(currentChannel))
                // Expand to wrap_content
                setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            State.RUNNING -> {
                cancelNotifyCollapse()
                startElapsedTicker()
                // Expand to pill shape for token display
                setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
                cardRunning?.visibility = View.VISIBLE
                // Update token status text + current action label
                val tvStatus = view.findViewById<TextView>(R.id.tvTokenStatus)
                tvStatus?.text = runningStatusText()
                val tvAction = view.findViewById<TextView>(R.id.tvTaskAction)
                tvAction?.text = if (currentAction.length > 26) currentAction.substring(0, 26) + "…" else currentAction
            }
            State.SUCCESS, State.ERROR -> {
                cancelNotifyCollapse()
                stopElapsedTicker()
                val hasSummary = resultSummary.isNotEmpty()
                if (hasSummary) {
                    // Result pill: ✓/✗ + summary, tap opens the chat
                    cardResult?.visibility = View.VISIBLE
                    setFloatRootWidth(view, WindowManager.LayoutParams.WRAP_CONTENT)
                    val card = cardResult as? androidx.cardview.widget.CardView
                    if (card != null) {
                        val colorRes = if (resultSuccess) R.color.colorSuccessPrimary else R.color.colorErrorPrimary
                        val app = appRef
                        if (app != null) {
                            try { card.setCardBackgroundColor(app.getColor(colorRes)) } catch (_: Exception) {}
                        }
                    }
                    view.findViewById<ImageView>(R.id.ivResultIcon)?.setImageResource(
                        if (resultSuccess) R.drawable.ic_check else R.drawable.ic_close
                    )
                    view.findViewById<TextView>(R.id.tvResultSummary)?.text =
                        if (resultSummary.length > 60) resultSummary.substring(0, 60) + "…" else resultSummary
                } else {
                    if (state == State.SUCCESS) cardSuccess?.visibility = View.VISIBLE
                    else cardError?.visibility = View.VISIBLE
                    setFloatRootWidth(view, getCircleWidth(view))
                }
            }
        }
    }

    /**
     * Get the icon for the corresponding channel
     */
    @DrawableRes
    private fun getChannelIcon(channel: Channel?): Int {
        return when (channel) {
            Channel.DISCORD -> R.drawable.ic_channel_discord
            Channel.TELEGRAM -> R.drawable.ic_channel_telegram
            Channel.WECHAT -> R.drawable.ic_channel_wechat
            else -> R.drawable.ic_launcher
        }
    }

    /**
     * Auto-reset to IDLE state after 5 seconds
     */
    private fun scheduleAutoReset() {
        cancelAutoReset()
        autoResetRunnable = Runnable {
            setIdleState()
        }
        mainHandler.postDelayed(autoResetRunnable!!, AUTO_RESET_DELAY_MS)
    }

    /**
     * Cancel the auto-reset
     */
    private fun cancelAutoReset() {
        autoResetRunnable?.let {
            mainHandler.removeCallbacks(it)
            autoResetRunnable = null
        }
    }

    /**
     * Ensure the floating window stays within the visible screen area; correct if out of bounds
     */
    private fun ensureFloatInBounds(view: View) {
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels
        // Get navigation bar height to ensure the bubble is not covered by the nav bar
        val navBarHeight = getNavigationBarHeight()

        // Method 1: try to find WindowManager.LayoutParams from the view hierarchy
        var wmParams: WindowManager.LayoutParams? = null
        var wmView: View? = view
        while (wmView != null) {
            val lp = wmView.layoutParams
            if (lp is WindowManager.LayoutParams) {
                wmParams = lp
                break
            }
            wmView = wmView.parent as? View
        }

        if (wmParams != null) {
            val floatHeight = (wmView ?: view).height
            val floatWidth = (wmView ?: view).width
            val maxX = (screenWidth - floatWidth).coerceAtLeast(0)
            // Subtract navigation bar height and extra safety margin
            val maxY = (screenHeight - floatHeight - navBarHeight - 50).coerceAtLeast(0)
            val clampedX = wmParams.x.coerceIn(0, maxX)
            val clampedY = wmParams.y.coerceIn(0, maxY)
            if (clampedX != wmParams.x || clampedY != wmParams.y) {
                EasyFloat.updateFloat(FLOAT_TAG, clampedX, clampedY)
            }
            savePosition(clampedX, clampedY)
            return
        }

        // Fallback: detect with getLocationOnScreen, correct with updateFloat
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewBottom = location[1] + view.height
        if (viewBottom > screenHeight - navBarHeight || location[1] < 0) {
            val safeY = screenHeight / 3
            EasyFloat.updateFloat(FLOAT_TAG, location[0].coerceIn(0, screenWidth), safeY)
            savePosition(location[0].coerceIn(0, screenWidth), safeY)
        } else {
            savePosition(location[0], location[1])
        }
    }

    private fun getNavigationBarHeight(): Int = BarUtils.getNavBarHeight()

    /** Original width of the circle state (read from layout on first use and cached) */
    private var circleWidthPx: Int = -1

    /** Dynamically change the floating window root layout width (expand to pill / collapse to circle) */
    private fun setFloatRootWidth(view: View, widthPx: Int) {
        val root = view.findViewById<View>(R.id.floatRoot) ?: return
        val lp = root.layoutParams
        if (lp != null && lp.width != widthPx) {
            lp.width = widthPx
            root.layoutParams = lp
        }
    }

    /** Get the width of the circle state (cached from createdResult to match the XML definition) */
    private fun getCircleWidth(@Suppress("UNUSED_PARAMETER") view: View): Int {
        return if (circleWidthPx > 0) circleWidthPx else WindowManager.LayoutParams.WRAP_CONTENT
    }


    /**
     * Save position
     */
    private fun savePosition(x: Int, y: Int) {
        KVUtils.putInt(KEY_FLOAT_X, x)
        KVUtils.putInt(KEY_FLOAT_Y, y)
    }

    /**
     * Get saved X coordinate
     */
    private fun getSavedX(): Int? {
        val x = KVUtils.getInt(KEY_FLOAT_X, -1)
        return if (x == -1) null else x
    }

    /**
     * Get saved Y coordinate
     */
    private fun getSavedY(): Int? {
        val y = KVUtils.getInt(KEY_FLOAT_Y, -1)
        return if (y == -1) null else y
    }

    /**
     * Click callback, can be set externally
     */
    var onFloatClick: () -> Unit = {}
    var onStopTask: () -> Unit = {}
}
