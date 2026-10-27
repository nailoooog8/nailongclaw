// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw

import io.agents.nailongclaw.agent.DefaultAgentService
import io.agents.nailongclaw.agent.llm.LocalBackendHealth
import io.agents.nailongclaw.base.BaseApp
import io.agents.nailongclaw.channel.ChannelManager
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
    }

    override fun onCreate() {
        super.onCreate()
        AppCapabilityCoordinator.markProcessStart()
        instance = this
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
