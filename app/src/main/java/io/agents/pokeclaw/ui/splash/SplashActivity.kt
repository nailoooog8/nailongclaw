// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import io.agents.pokeclaw.R
import io.agents.pokeclaw.automation.ExternalAutomationEntrypoint
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.chat.ComposeChatActivity
import io.agents.pokeclaw.ui.web.ClaudeUiPreviewActivity

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
 */
class SplashActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Back press disabled on splash screen */ }
        })

        val taskText = getIntent()?.getStringExtra("task")
        val target = if (taskText.isNullOrBlank()) ClaudeUiPreviewActivity::class.java
        else ComposeChatActivity::class.java
        val intent = Intent(this, target)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        // Forward debug task extra. A task extra reaching this screen did not
        // come from a tap here — it came from another app or adb — so it is
        // marked EXTERNAL. Without the marker the chat screen records it as
        // Channel.LOCAL, which hands it the unrestricted tier (R3-01); this
        // activity is exported and LAUNCHER-declared, so that door is open to
        // any app on the device.
        taskText?.let {
            intent.putExtra("task", it)
            intent.putExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_SOURCE, true)
        }
        startActivity(intent)
        finish()
    }
}
