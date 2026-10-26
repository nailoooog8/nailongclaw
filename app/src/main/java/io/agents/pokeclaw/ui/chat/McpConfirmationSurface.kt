// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import io.agents.pokeclaw.agent.mcp.ConfirmationButtons
import io.agents.pokeclaw.agent.mcp.McpConfirmationCoordinator
import io.agents.pokeclaw.agent.mcp.McpConfirmationGate
import io.agents.pokeclaw.agent.mcp.McpConfirmationRequest

/**
 * [McpConfirmationCoordinator.Surface] for the Compose chat screen.
 *
 * ## Why a dialog and not a card in the feed
 *
 * The confirmation parks the agent thread inside `ToolRegistry.executeTool`, so
 * the screen has to hold the user's attention until it is answered — a card in
 * the scrollback can be scrolled past and forgotten, and a task left parked is
 * a task the user thinks is running. A modal dialog with exactly the three
 * choices the card offers is the honest shape: nothing else in the app is
 * usable until the question is answered, which is also what the timeout is for.
 *
 * The dialog *is* cancelable, and cancelling means "no". That is deliberate: a
 * gate that cannot be dismissed traps the user with a task they cannot stop,
 * while a dismissal that denies can only ever refuse something the model can
 * simply ask for again next turn.
 *
 * Everything the user sees is recorded in the transcript through [onNote], so a
 * task that dies mid-wait still shows what it was waiting for.
 */
class McpConfirmationSurface(
    private val activity: ComponentActivity,
    private val onNote: (String) -> Unit,
) : McpConfirmationCoordinator.Surface {

    @Volatile
    override var isForeground: Boolean = false

    private val main = Handler(Looper.getMainLooper())

    private var dialog: io.agents.pokeclaw.widget.CdsDialog.Handle? = null

    override fun showConfirmation(request: McpConfirmationRequest) {
        // The coordinator calls this from the agent thread; a dialog may only
        // be built on the UI thread.
        main.post {
            if (activity.isFinishing) return@post
            dismissDialog()
            onNote(McpConfirmationGate.pendingNote(request))
            // 破坏性档没有「始终允许」：卡面不给 gate 会静默降级的出口
            // （ConfirmationButtons 与 gate 同规则，H62）。
            val buttons = ConfirmationButtons.forRisk(request.risk)
            // CDS frame: the same panel/serif/accent treatment the settings
            // editors got, so a confirmation that parks the agent thread
            // doesn't drop the user into a stock system dialog.
            dialog = io.agents.pokeclaw.widget.CdsDialog.show(
                context = activity,
                title = "MCP 工具需要确认",
                content = io.agents.pokeclaw.widget.CdsDialog.message(
                    activity,
                    buildMessage(request)
                ),
                confirmText = "允许一次",
                cancelText = "本次拒绝",
                neutralText = "始终允许".takeIf { buttons.offersRememberedYes },
                onConfirm = { answer(McpConfirmationGate.Decision.ALLOW_ONCE) },
                onNeutral = { answer(McpConfirmationGate.Decision.ALLOW_ALWAYS) }
                    .takeIf { buttons.offersRememberedYes },
                // Cancelling answers "no" — see the class comment. The cancel
                // button and BACK/outside-touch both land here (CdsDialog
                // routes the button through onCancel on purpose).
                onCancel = { answer(McpConfirmationGate.Decision.DENY) },
            )
        }
    }

    override fun hideConfirmation(verdict: McpConfirmationGate.Verdict) {
        main.post {
            dismissDialog()
            onNote(McpConfirmationGate.transcriptNote(verdict))
        }
    }

    /** Take the dialog down without treating it as an answer. */
    fun dismiss() {
        main.post { dismissDialog() }
    }

    private fun answer(decision: McpConfirmationGate.Decision) {
        McpConfirmationCoordinator.submit(decision)
        // The coordinator calls hideConfirmation back, which dismisses; this
        // is the belt to that braces for a submit that found nothing waiting.
        dismissDialog()
    }

    private fun dismissDialog() {
        val current = dialog ?: return
        dialog = null
        current.dismiss()
    }

    private fun buildMessage(request: McpConfirmationRequest): String = buildString {
        append("工具：").append(request.displayName)
        append("\n服务器：").append(request.serverName)
        if (request.description.isNotBlank()) {
            append("\n\n").append(request.description)
        }
        if (request.paramsPreview.isNotBlank()) {
            append("\n\n参数：\n").append(request.paramsPreview)
        }
        if (!ConfirmationButtons.forRisk(request.risk).offersRememberedYes) {
            // 破坏性档：解释为什么没有「始终允许」。缺了这句话，两个按钮
            // 看起来像少了一个，而不是像它本来就不该有。
            append("\n\n⚠ 破坏性操作，不可撤销：只能允许这一次。")
        }
        append("\n\n这是模型请求的第三方工具，执行前需要你确认。")
    }
}
