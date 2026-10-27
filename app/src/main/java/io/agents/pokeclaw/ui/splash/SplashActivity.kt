// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import io.agents.pokeclaw.R
import io.agents.pokeclaw.automation.ExternalAutomationEntrypoint
import io.agents.pokeclaw.automation.ExternalAutomationGuard
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.chat.ComposeChatActivity
import io.agents.pokeclaw.ui.web.ClaudeUiPreviewActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

/**
 * Splash screen - always navigates to the main screen; LLM does not need to be configured first, it can be set up in Settings
 *
 * The main screen is the Claude UI surface since 2026-10-24. [ComposeChatActivity]
 * is no longer the landing screen, but it stays the receiver of the external
 * `task` extra contract (ExternalAutomationEntrypoint, TaskTriggerReceiver,
 * `adb shell am start --es task …`): those callers consume the extra on the chat
 * screen and none of it exists on the WebView surface, so a task extra keeps
 * routing there instead of being silently dropped on the new home.
 *
 * Because this screen is exported and LAUNCHER-declared, it is itself one of
 * those callers' entry points: anything arriving with a `task` extra is marked
 * [ExternalAutomationEntrypoint.EXTRA_EXTERNAL_SOURCE] so the chat screen
 * records it as Channel.EXTERNAL rather than assuming a local user (R3-01).
 *
 * R4-R1: being exported + LAUNCHER also means ANY app can `am start` a `task`
 * at it with no credentials. Tagging EXTERNAL (above) bounds what such a task
 * may do, but not who may start one — so a `task` extra is now gated like the
 * broadcast/Activity automation entries: adb shell (referrer) or a valid pairing
 * token. An ungated third-party app is dropped and routed to the normal home.
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Back press disabled on splash screen */ }
        })

        val incoming = intent
        val rawTask = incoming?.getStringExtra("task")?.takeIf { it.isNotBlank() }
        val taskText = if (rawTask != null && isLauncherTaskAllowed(referrer?.toString(), incoming)) rawTask else null
        if (rawTask != null && taskText == null) {
            XLog.w(TAG, "Dropped launcher task from an untrusted caller (no shell referrer, no valid token)")
        }

        val target = if (taskText.isNullOrBlank()) ClaudeUiPreviewActivity::class.java
        else ComposeChatActivity::class.java
        val forward = Intent(this, target)
        forward.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // Forward the task extra only for a trusted caller. A task extra reaching
        // this screen did not come from a tap here — it came from another app or
        // adb — so it is marked EXTERNAL. Without the marker the chat screen
        // records it as Channel.LOCAL, which hands it the unrestricted tier
        // (R3-01); this activity is exported and LAUNCHER-declared, so that door
        // is open to any app on the device — hence the gate above (R4-R1).
        taskText?.let {
            forward.putExtra("task", it)
            forward.putExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_SOURCE, true)
        }
        startActivity(forward)
        finish()
    }

    /**
     * True when the caller driving a task through the exported launcher is
     * trusted: adb shell (for QA) or an automation app presenting the same
     * pairing token the broadcast/Activity entries require, with the feature
     * enabled. Anything else is refused, so a random installed app can no
     * longer start a task by launching this screen (R4-R1).
     */
    private fun isLauncherTaskAllowed(referrer: String?, intent: Intent?): Boolean {
        if (isShellReferrer(referrer)) return true
        val token = intent?.getStringExtra(ExternalAutomationGuard.EXTRA_TOKEN)
        val allowed = ExternalAutomationGuard.verify(token) is ExternalAutomationGuard.Decision.Allow
        return allowed && KVUtils.isExternalAutomationEnabled()
    }

    companion object {
        private const val TAG = "SplashActivity"

        /**
         * True when [referrer] is the automatic referrer Android attaches for an
         * `adb shell am start` (uid shell = com.android.shell). Matched on the
         * authority exactly so a look-alike package or a path/query suffix does
         * not pass.
         *
         * A determined app can forge EXTRA_REFERRER to this exact value; the
         * pairing token is the robust control and this carve-out only keeps QA
         * working without one. Fully attributing the caller needs the F3
         * signature allowlist.
         */
        internal fun isShellReferrer(referrer: String?): Boolean {
            val r = referrer?.trim()?.lowercase() ?: return false
            if (r.isEmpty()) return false
            val authority = r
                .removePrefix("android-app://")
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore('?')
            return authority == "com.android.shell"
        }
    }
}
