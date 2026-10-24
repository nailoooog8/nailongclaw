// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
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

    private var dialog: AlertDialog? = null

    override fun showConfirmation(request: McpConfirmationRequest) {
        // The coordinator calls this from the agent thread; a dialog may only
        // be built on the UI thread.
        main.post {
            if (activity.isFinishing) return@post
            dismissDialog()
            onNote(McpConfirmationGate.pendingNote(request))
            dialog = AlertDialog.Builder(activity)
                .setTitle("MCP 工具需要确认")
                .setMessage(buildMessage(request))
                // Cancelling answers "no" — see the class comment.
                .setOnCancelListener { answer(McpConfirmationGate.Decision.DENY) }
                .setPositiveButton("允许一次") { _, _ -> answer(McpConfirmationGate.Decision.ALLOW_ONCE) }
                .setNeutralButton("始终允许") { _, _ -> answer(McpConfirmationGate.Decision.ALLOW_ALWAYS) }
                .setNegativeButton("本次拒绝") { _, _ -> answer(McpConfirmationGate.Decision.DENY) }
                .show()
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
        // Null the dismiss message so the framework's own teardown cannot
        // re-enter and null the field twice.
        current.setDismissMessage(null)
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
        append("\n\n这是模型请求的第三方工具，执行前需要你确认。")
    }
}
