// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import io.agents.nailongclaw.R
import io.agents.nailongclaw.automation.ExternalAutomationEntrypoint
import io.agents.nailongclaw.automation.ExternalAutomationGuard
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.ui.chat.ComposeChatActivity
import io.agents.nailongclaw.ui.web.ClaudeUiPreviewActivity
import io.agents.nailongclaw.utils.KVUtils
import io.agents.nailongclaw.utils.XLog

/**
 * Splash screen — always navigates to the main (Claude UI) home; LLM config is
 * optional here and can be set up later in Settings.
 *
 * R4-R1: this screen is `exported` + LAUNCHER-declared, so any app can `am start`
 * a `task` extra at it. It is NOT an automation entry point — the sanctioned one
 * is [io.agents.nailongclaw.automation.ExternalAutomationActivity] /
 * [io.agents.nailongclaw.automation.ExternalAutomationReceiver] (token-gated). A
 * `task` extra arriving here is therefore honoured only with a valid pairing
 * token and the feature enabled; otherwise it is ignored and we route to the
 * home. (A bare `adb shell am start … --es task` used to drive a task with no
 * credentials; that ungated door is now closed — pass the token, or use the
 * ExternalAutomation entry.)
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Back press disabled on splash screen */ }
        })

        val rawTask = intent?.getStringExtra("task")?.takeIf { it.isNotBlank() }
        val taskText = if (rawTask != null && isLauncherTaskAllowed(intent)) rawTask else null
        if (rawTask != null && taskText == null) {
            XLog.w(TAG, "Ignored launcher task extra: no valid pairing token (R4-R1)")
        }

        val target = if (taskText.isNullOrBlank()) ClaudeUiPreviewActivity::class.java
        else ComposeChatActivity::class.java
        val forward = Intent(this, target)
        forward.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        taskText?.let {
            forward.putExtra("task", it)
            // Only reached with a valid token; still marked EXTERNAL so the chat
            // screen records Channel.EXTERNAL and ToolRiskPolicy applies the
            // READ_ONLY ceiling (R3-01).
            forward.putExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_SOURCE, true)
        }
        startActivity(forward)
        finish()
    }

    /**
     * A `task` extra on the exported launcher is honoured only for a caller that
     * presents the same pairing token the broadcast/Activity entries require,
     * with the feature enabled (R4-R1). Anything else — including a bare adb
     * `am start` with no token — is refused, so a random installed app can no
     * longer start a task by launching this screen.
     */
    private fun isLauncherTaskAllowed(intent: Intent?): Boolean {
        val token = intent?.getStringExtra(ExternalAutomationGuard.EXTRA_TOKEN)
        val allowed = ExternalAutomationGuard.verify(token) is ExternalAutomationGuard.Decision.Allow
        return allowed && KVUtils.isExternalAutomationEnabled()
    }

    companion object {
        private const val TAG = "SplashActivity"
    }
}
