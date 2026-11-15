// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw

import io.agents.nailongclaw.agent.DefaultAgentService
import io.agents.nailongclaw.agent.llm.LocalBackendHealth
import io.agents.nailongclaw.base.BaseApp
import io.agents.nailongclaw.channel.ChannelManager
import io.agents.nailongclaw.service.ClawAccessibilityService
import io.agents.nailongclaw.tool.ToolRegistry
import io.agents.nailongclaw.utils.AppLogStore
import io.agents.nailongclaw.utils.KVUtils
import io.agents.nailongclaw.utils.XLog
import com.blankj.utilcode.util.NetworkUtils

/**
 * Application entry point
 */

val appViewModel: AppViewModel by lazy { ClawApplication.appViewModelInstance }
class ClawApplication : BaseApp() {

    companion object {
        private const val TAG = "ClawApplication"
        lateinit var instance: ClawApplication
            private set
        lateinit var appViewModelInstance: AppViewModel

        /**
         * Whether any Activity of this app is currently in the foreground.
         *
         * Counting started/destroyed (not resumed/paused) keeps the flag true
         * across the brief hole when one activity starts another — a
         * paused/started pair would otherwise flicker "background" between
         * screens. Consumed by [io.agents.nailongclaw.notify.TaskOutcomeNotifier]
         * to tell "the user typed this and walked away" from "the user is
         * watching the transcript right now".
         */
        @Volatile
        var isAppInForeground: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        AppCapabilityCoordinator.markProcessStart()
        instance = this
        // The a11y component must never sit disabled: some ROMs then strip the
        // service from the enabled-services list on every write, and only the
        // app itself can flip the component back (shell gets a
        // SecurityException). See ClawAccessibilityService.ensureComponentEnabled.
        ClawAccessibilityService.ensureComponentEnabled(this)
        AppLogStore.init(this)
        XLog.setDEBUG(BuildConfig.DEBUG)
        registerNetworkCallback()
        appViewModelInstance = getAppViewModelProvider()[AppViewModel::class.java]
        KVUtils.init(this)
        LocalBackendHealth.recoverPendingGpuCrashIfNeeded()
        ToolRegistry.getInstance().registerAllTools(ToolRegistry.DeviceType.MOBILE)
        io.agents.nailongclaw.agent.skill.SkillRegistry.loadBuiltInSkills()
        io.agents.nailongclaw.agent.skill.UserSkillLoader.loadAndRegister(this)
        io.agents.nailongclaw.agent.PlaybookManager.loadAll(this)
        // Memory bank: parse memories.json so the first task already has the bank warm
        io.agents.nailongclaw.agent.memory.MemoryStore.init(this)
        // Task ledger: durable record of accepted tasks — the task page reads
        // only from here, never from the volatile session store
        io.agents.nailongclaw.task.TaskRecordStore.init(this)
        // Task outcome notifications: observe terminal ledger transitions once,
        // process-wide — every entry path is covered because the ledger is
        // written by every entry path
        io.agents.nailongclaw.notify.TaskOutcomeNotifier.register()
        registerActivityLifecycleCallbacks(foregroundTracker)
        // Scheduled tasks: the durable timer half; alarms themselves are
        // re-armed by BootReceiver after a reboot
        io.agents.nailongclaw.task.ScheduledTaskStore.init(this)
        // Watchdog: periodic job (persisted across reboots) that re-syncs the
        // foreground service and revives dropped channels if the process was killed
        io.agents.nailongclaw.service.KeepAliveJobService.schedule(this)
        // MCP: load remote tool configs and register remote tools (async, network)
        io.agents.nailongclaw.agent.mcp.McpConnectionManager.loadAndRegister(this)
        // The composer's 允许操作 gear: when the user picks 自动允许, MCP tool
        // calls run without the per-call confirmation card. Wired here because
        // KVUtils must be initialized first; the default (provider unset) is
        // the fail-closed ask-every-time policy.
        io.agents.nailongclaw.agent.mcp.McpConfirmationCoordinator.autoAllowProvider = {
            KVUtils.isAutoAllowOperations()
        }
        XLog.e(TAG, "ClawApplication initialized, tools registered: ${ToolRegistry.getInstance().getAllTools().size}")

        // Write network logs to file (set to true when debugging)
        DefaultAgentService.FILE_LOGGING_ENABLED = BuildConfig.DEBUG
        DefaultAgentService.FILE_LOGGING_CACHE_DIR = cacheDir

        // Lightweight initialization (main thread)
        appViewModelInstance.initCommon()
        Thread({
            try {
                android.util.Log.e("NAILONGCLAW_INIT", "app-async-init thread STARTED")
                val hasConfig = KVUtils.hasLlmConfig()
                android.util.Log.e("NAILONGCLAW_INIT", "app-async-init: hasLlmConfig=$hasConfig, canDrawOverlays=${android.provider.Settings.canDrawOverlays(instance)}")
                if (hasConfig) {
                    appViewModelInstance.initAgent()
                    appViewModelInstance.afterInit()
                }
            } catch (e: Exception) {
                android.util.Log.e("NAILONGCLAW_INIT", "app-async-init CRASHED: ${e.message}", e)
            }
        }, "app-async-init").start()
    }

    private var networkListener: NetworkUtils.OnNetworkStatusChangedListener? = null

    private val foregroundTracker = object : android.app.Application.ActivityLifecycleCallbacks {
        private var startedCount = 0

        override fun onActivityStarted(activity: android.app.Activity) {
            startedCount++
            isAppInForeground = true
        }

        override fun onActivityStopped(activity: android.app.Activity) {
            startedCount = (startedCount - 1).coerceAtLeast(0)
            if (startedCount == 0) isAppInForeground = false
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityResumed(activity: android.app.Activity) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }

    /**
     * Listen for network recovery and automatically re-initialize channels.
     * Fixes channel initialization failures when booting with no network, and reconnects channels after network outages.
     */
    private fun registerNetworkCallback() {
        networkListener = object : NetworkUtils.OnNetworkStatusChangedListener {
            override fun onConnected(networkType: NetworkUtils.NetworkType?) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (KVUtils.hasLlmConfig()) {
                        XLog.i(TAG, "Network recovered (${networkType?.name}), checking and reconnecting dropped channels")
                        ChannelManager.reconnectIfNeeded()
                    }
                }, 2000)
            }

            override fun onDisconnected() {
                XLog.w(TAG, "Network disconnected")
            }
        }
        NetworkUtils.registerNetworkStatusChangedListener(networkListener)
    }

}
