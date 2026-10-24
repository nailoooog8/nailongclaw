// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import io.agents.pokeclaw.R
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
        // Forward debug task extra
        taskText?.let { intent.putExtra("task", it) }
        startActivity(intent)
        finish()
    }
}
