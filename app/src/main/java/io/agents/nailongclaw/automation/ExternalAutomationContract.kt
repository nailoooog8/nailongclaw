// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.automation

import android.content.Context
import android.content.Intent
import io.agents.nailongclaw.utils.XLog
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Public contract for automation apps such as Tasker and MacroDroid.
 *
 * The receiver still requires the user to enable External Automation in
 * Settings before any request is executed.
 */
object ExternalAutomationContract {
    const val ACTION_RUN_TASK = "io.agents.nailongclaw.RUN_TASK"
    const val ACTION_RUN_CHAT = "io.agents.nailongclaw.RUN_CHAT"

    const val EXTRA_TASK = "task"
    const val EXTRA_CHAT = "chat"
    const val EXTRA_TASK_B64 = "task_b64"
    const val EXTRA_CHAT_B64 = "chat_b64"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_RETURN_ACTION = "return_action"
    const val EXTRA_RETURN_PACKAGE = "return_package"

    const val EXTRA_STATUS = "status"
    const val EXTRA_RESULT = "result"
    const val EXTRA_ERROR = "error"
    const val EXTRA_MODE = "mode"

    const val STATUS_ACCEPTED = "accepted"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_BLOCKED = "blocked"
    const val STATUS_REJECTED = "rejected"

    private const val TAG = "ExternalAutomation"

    enum class Mode {
        TASK,
        CHAT,
    }

    data class Request(
        val mode: Mode,
        val text: String,
        val requestId: String?,
        val returnAction: String?,
        val returnPackage: String?,
    )

    fun parse(action: String?, extra: (String) -> String?): Request? {
        val mode = when (action) {
            ACTION_RUN_TASK -> Mode.TASK
            ACTION_RUN_CHAT -> Mode.CHAT
            else -> return null
        }

        val task = firstNonBlank(decodeBase64(extra(EXTRA_TASK_B64)), extra(EXTRA_TASK))
        val chat = firstNonBlank(decodeBase64(extra(EXTRA_CHAT_B64)), extra(EXTRA_CHAT))
        val text = when (mode) {
            Mode.TASK -> task ?: chat
            Mode.CHAT -> chat ?: task
        } ?: return null

        return Request(
            mode = mode,
            text = text,
            requestId = extra(EXTRA_REQUEST_ID)?.trim()?.takeIf { it.isNotEmpty() },
            returnAction = extra(EXTRA_RETURN_ACTION)?.trim()?.takeIf { it.isNotEmpty() },
            returnPackage = extra(EXTRA_RETURN_PACKAGE)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Decides where a callback may be sent.
     *
     * The caller chooses `return_action`, and PokeClaw then broadcasts an
     * intent with it. Without a named, installed target that makes PokeClaw a
     * broadcast proxy for whichever app happens to listen for that action
     * (confused deputy, docs/SECURITY_AUDIT_R2.md F3) — so an action with no
     * `return_package`, or one naming a package that is not on the device, is
     * refused. Returns the package to send to, or null to send nothing.
     */
    fun callbackTarget(returnPackage: String?, isInstalled: (String) -> Boolean): String? {
        val target = returnPackage?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!isInstalled(target)) return null
        return target
    }

    fun sendCallback(
        context: Context,
        returnAction: String?,
        requestId: String?,
        status: String,
        result: String? = null,
        error: String? = null,
        returnPackage: String? = null,
        mode: Mode? = null,
    ) {
        if (returnAction.isNullOrBlank()) return
        val target = try {
            callbackTarget(returnPackage) { pkg ->
                context.packageManager.getPackageInfo(pkg, 0) != null
            }
        } catch (e: Exception) {
            XLog.w(TAG, "Package lookup failed for automation callback target", e)
            null
        }
        if (target == null) {
            XLog.w(
                TAG,
                "Refused automation callback to '$returnAction': return_package " +
                    "'$returnPackage' is missing or not installed on this device"
            )
            return
        }
        try {
            val callback = Intent(returnAction).apply {
                setPackage(target)
                requestId?.let { putExtra(EXTRA_REQUEST_ID, it) }
                putExtra(EXTRA_STATUS, status)
                mode?.let { putExtra(EXTRA_MODE, it.name.lowercase()) }
                result?.let { putExtra(EXTRA_RESULT, it) }
                error?.let { putExtra(EXTRA_ERROR, it) }
            }
            context.sendBroadcast(callback)
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to send automation callback", e)
        }
    }

    private fun decodeBase64(value: String?): String? {
        val encoded = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).trim()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun firstNonBlank(first: String?, second: String?): String? {
        if (!first.isNullOrBlank()) return first.trim()
        if (!second.isNullOrBlank()) return second.trim()
        return null
    }
}
