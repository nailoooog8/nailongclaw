// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.AppRequirement
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.service.AutoReplyManager
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.ui.cds.CdsUi
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.MenuGroup

/**
 * 监听与权限 — the page that answers "what is PokeClaw watching, and what is
 * it allowed to do".
 *
 * Two groups, because those are two different questions:
 *
 *  1. 监听 — the live watchers. 无障碍服务 reads the screen to act on it,
 *     通知使用权 reads notifications, the foreground service keeps the
 *     process alive so a monitor survives the screen turning off, and
 *     自动回复 is the one watcher with an in-app switch (it arms per contact
 *     from chat, so here it only reports state).
 *  2. 权限 — the remaining grants the watchers and tasks depend on:
 *     notification popups, the floating pill, battery exemption, storage.
 *
 * Everything here used to be a flat "Permissions" block buried in the full
 * settings page. Monitoring is a product surface, not a checklist item: the
 * statuses below are polled every second (same cadence the old settings page
 * used) so a service killed by the ROM shows up here without a manual refresh.
 */
class MonitorActivity : BaseActivity() {

    private lateinit var monitorGroup: MenuGroup
    private lateinit var permissionGroup: MenuGroup

    private val cds by lazy { CdsUi.palette(this) }

    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            refreshStatuses()
            handler.postDelayed(this, 1000)
        }
    }

    /** Trailing labels refreshed by the poller. */
    private var accessibilityItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var notificationAccessItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var foregroundItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var autoReplyItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var notificationPermItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var overlayItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var batteryItem: io.agents.pokeclaw.widget.MenuItem? = null
    private var storageItem: io.agents.pokeclaw.widget.MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(cds.surface1)
        setContentView(R.layout.activity_monitor)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, "监听与权限")
        toolbar.showBackButton(true) { finish() }

        monitorGroup = findViewById(R.id.monitorGroup)
        permissionGroup = findViewById(R.id.permissionGroup)
        monitorGroup.applyCds(cds)
        permissionGroup.applyCds(cds)

        findViewById<TextView>(R.id.tvFooter).apply {
            CdsUi.styleChromeText(this, cds, 11f)
            text = "「监听」是 PokeClaw 替你看着屏幕与通知的能力：无障碍服务读屏幕才能点得了屏幕，" +
                "通知使用权让远程消息能触发任务。关掉任一监听，对应能力随之停用。\n" +
                "状态每秒刷新；被系统杀掉的服务这里会立刻显示断开。"
        }

        buildMonitorGroup()
        buildPermissionGroup()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
        handler.postDelayed(poller, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    private fun buildMonitorGroup() {
        monitorGroup.clearMenuItems()
        monitorGroup.setTitle("监听")

        accessibilityItem = monitorGroup.addMenuItem(
            leadingIcon = R.drawable.ic_accessibility,
            title = "无障碍服务 · 屏幕监听",
            onClick = { open(AppRequirement.ACCESSIBILITY) },
            showDivider = true
        )
        notificationAccessItem = monitorGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = "通知使用权 · 通知监听",
            onClick = { open(AppRequirement.NOTIFICATION_ACCESS) },
            showDivider = true
        )
        foregroundItem = monitorGroup.addMenuItem(
            leadingIcon = R.drawable.ic_battery,
            title = "常驻后台",
            onClick = { toggleKeepAlive() },
            showDivider = true
        )
        autoReplyItem = monitorGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_call,
            title = "自动回复监控",
            onClick = {
                Toast.makeText(
                    this,
                    if (AutoReplyManager.getInstance().isEnabled()) "自动回复监控中" else "未在监控",
                    Toast.LENGTH_SHORT
                ).show()
            },
            showDivider = false
        )
    }

    private fun buildPermissionGroup() {
        permissionGroup.clearMenuItems()
        permissionGroup.setTitle("权限")

        notificationPermItem = permissionGroup.addMenuItem(
            leadingIcon = R.drawable.ic_notification,
            title = "通知弹出",
            onClick = { requestNotificationPermission() },
            showDivider = true
        )
        overlayItem = permissionGroup.addMenuItem(
            leadingIcon = R.drawable.ic_window,
            title = "悬浮窗",
            onClick = { open(AppRequirement.OVERLAY) },
            showDivider = true
        )
        batteryItem = permissionGroup.addMenuItem(
            leadingIcon = R.drawable.ic_battery,
            title = "电池优化白名单",
            onClick = { open(AppRequirement.BATTERY_OPTIMIZATION) },
            showDivider = true
        )
        storageItem = permissionGroup.addMenuItem(
            leadingIcon = R.drawable.ic_storage,
            title = "存储访问",
            onClick = { open(AppRequirement.STORAGE) },
            showDivider = false
        )
    }

    private fun refreshStatuses() {
        val capabilities = AppCapabilityCoordinator.snapshot(this)
        accessibilityItem?.setTrailingText(capabilities.accessibilityStatusLabel)
        notificationAccessItem?.setTrailingText(capabilities.notificationAccessStatusLabel)

        val keepAlive = ForegroundService.isPersistentKeepAliveEnabled()
        foregroundItem?.setTrailingText(if (keepAlive) "开启" else "关闭")
        foregroundItem?.setTrailingTextColor(if (keepAlive) cds.accent else cds.textMuted)

        val autoReply = AutoReplyManager.getInstance().isEnabled()
        autoReplyItem?.setTrailingText(if (autoReply) "监控中" else "未启用")
        autoReplyItem?.setTrailingTextColor(if (autoReply) cds.accent else cds.textMuted)

        val notifGranted = AppCapabilityCoordinator.isNotificationPermissionGranted(this)
        notificationPermItem?.setTrailingText(if (notifGranted) "已允许" else "未允许")
        notificationPermItem?.setTrailingTextColor(if (notifGranted) cds.accent else cds.textMuted)

        overlayItem?.setTrailingText(
            if (capabilities.overlayGranted) "已允许" else "未允许"
        )
        batteryItem?.setTrailingText(
            if (capabilities.batteryOptimizationIgnored) "不受限制" else "受限制"
        )
        storageItem?.setTrailingText(
            if (capabilities.storageAccessGranted) "已允许" else "未允许"
        )
        listOf(overlayItem, batteryItem, storageItem).forEach {
            it?.setTrailingTextColor(cds.textSecondary)
        }
    }

    private fun toggleKeepAlive() {
        val enabled = !ForegroundService.isPersistentKeepAliveEnabled()
        ForegroundService.setPersistentKeepAliveEnabled(enabled)
        ForegroundService.syncToBackgroundState(this)
        Toast.makeText(this, if (enabled) "常驻后台已开启" else "常驻后台已关闭", Toast.LENGTH_SHORT).show()
        refreshStatuses()
    }

    private fun requestNotificationPermission() {
        if (AppCapabilityCoordinator.isNotificationPermissionGranted(this)) {
            Toast.makeText(this, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun open(requirement: AppRequirement) {
        val snapshot = AppCapabilityCoordinator.snapshot(this)
        val alreadyGranted = when (requirement) {
            AppRequirement.ACCESSIBILITY ->
                snapshot.accessibilityState == io.agents.pokeclaw.ServiceBindingState.READY

            AppRequirement.NOTIFICATION_ACCESS ->
                snapshot.notificationAccessState == io.agents.pokeclaw.ServiceBindingState.READY

            AppRequirement.OVERLAY -> snapshot.overlayGranted
            AppRequirement.BATTERY_OPTIMIZATION -> snapshot.batteryOptimizationIgnored
            AppRequirement.STORAGE -> snapshot.storageAccessGranted
            else -> false
        }
        if (alreadyGranted) {
            Toast.makeText(this, "已开启", Toast.LENGTH_SHORT).show()
            return
        }
        AppCapabilityCoordinator.openSystemSettings(this, requirement)
    }
}
