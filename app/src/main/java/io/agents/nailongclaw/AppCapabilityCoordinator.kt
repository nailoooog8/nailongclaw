// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.agents.nailongclaw.service.ClawAccessibilityService
import io.agents.nailongclaw.service.ClawNotificationListener
import io.agents.nailongclaw.service.ForegroundService
import io.agents.nailongclaw.utils.KVUtils

enum class ServiceBindingState {
    DISABLED,
    CONNECTING,
    READY,
    DEGRADED,
}

enum class AppRequirement(val label: String) {
    ACCESSIBILITY("Accessibility"),
    NOTIFICATION_PERMISSION("Notifications"),
    NOTIFICATION_ACCESS("Notification Access"),
    OVERLAY("Overlay"),
    BATTERY_OPTIMIZATION("Battery"),
    STORAGE("Storage"),
}

data class AppCapabilitySnapshot(
    val accessibilityState: ServiceBindingState,
    /**
     * True when the service is switched on in system settings but the OS never
     * (re)bound it — the "enabled yet dead" zombie the user experiences as
     * "accessibility keeps failing to start". Only a manual off/on toggle in
     * system settings revives it; reopening the app cannot.
     */
    val accessibilityNeedsManualRebind: Boolean,
    val notificationAccessState: ServiceBindingState,
    val notificationPermissionGranted: Boolean,
    val foregroundServiceRunning: Boolean,
    val overlayGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val storageAccessGranted: Boolean,
    /**
     * 影子屏（MediaProjection 虚拟屏）当前是否已建。投影授权是一次性
     * token，没有「系统层永久已授权」可查——这一位只回答「现在有没有屏」，
     * 未就绪是正常状态（下次任务会弹系统确认框），不是错误。
     */
    val shadowDisplayReady: Boolean,
) {
    val canRunInteractiveTask: Boolean
        get() = accessibilityState == ServiceBindingState.READY

    val canRunMonitor: Boolean
        get() = accessibilityState == ServiceBindingState.READY &&
            notificationAccessState == ServiceBindingState.READY

    val accessibilityStatusLabel: String
        get() = when {
            accessibilityNeedsManualRebind -> "Enabled (system not bound)"
            else -> when (accessibilityState) {
                ServiceBindingState.READY -> "Enabled"
                ServiceBindingState.CONNECTING -> "Connecting"
                ServiceBindingState.DEGRADED -> "Disconnected"
                ServiceBindingState.DISABLED -> "Disabled"
            }
        }

    val notificationAccessStatusLabel: String
        get() = when (notificationAccessState) {
            ServiceBindingState.READY -> "Connected"
            ServiceBindingState.CONNECTING -> "Connecting"
            ServiceBindingState.DEGRADED -> "Disconnected"
            ServiceBindingState.DISABLED -> "Disabled"
        }

    val notificationPermissionStatusLabel: String
        get() = if (notificationPermissionGranted) "Enabled" else "Disabled"
}

object AppCapabilityCoordinator {
    private const val SERVICE_REBIND_GRACE_MS = 15_000L
    private const val PROCESS_START_REBIND_GRACE_MS = 30_000L
    private const val ACCESSIBILITY_INTERRUPT_GRACE_MS = 4_000L

    @Volatile
    private var processStartTimestamp: Long = System.currentTimeMillis()

    fun markProcessStart() {
        processStartTimestamp = System.currentTimeMillis()
    }

    fun snapshot(context: Context): AppCapabilitySnapshot {
        val accessibility = accessibilityBinding(context)
        return AppCapabilitySnapshot(
            accessibilityState = accessibility.state,
            accessibilityNeedsManualRebind = accessibility.needsManualRebind,
            notificationAccessState = notificationAccessState(context),
            notificationPermissionGranted = isNotificationPermissionGranted(context),
            foregroundServiceRunning = ForegroundService.isRunning(),
            overlayGranted = Settings.canDrawOverlays(context),
            batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context),
            storageAccessGranted = hasStorageAccess(context),
            shadowDisplayReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                io.agents.nailongclaw.service.ShadowDisplayManager.isCreated(),
        )
    }

    /** Binding verdict plus the OEM "zombie" marker — see [BindingEval.needsManualRebind]. */
    data class BindingEval(
        val state: ServiceBindingState,
        /**
         * The service's goodbye (onDestroy/onInterrupt) never arrived after its last
         * healthy moment, so the process was killed outright — and aggressive OEMs
         * (Vivo in QA) often never rebind after that. Only a manual off/on toggle in
         * system settings revives the service; reopening the app cannot.
         */
        val needsManualRebind: Boolean,
    )

    fun accessibilityBinding(context: Context): BindingEval {
        return bindingEval(
            enabled = ClawAccessibilityService.isEnabledInSettings(context),
            running = ClawAccessibilityService.isRunning(),
            pendingRepair = KVUtils.hasPendingAccessibilityReturn(),
            lastConnectedAt = KVUtils.getAccessibilityLastConnectedAt(),
            lastHeartbeatAt = KVUtils.getAccessibilityLastHeartbeatAt(),
            lastInterruptedAt = KVUtils.getAccessibilityLastInterruptedAt(),
            lastDisconnectedAt = KVUtils.getAccessibilityLastDisconnectedAt(),
        )
    }

    fun accessibilityState(context: Context): ServiceBindingState =
        accessibilityBinding(context).state

    fun accessibilityNeedsManualRebind(context: Context): Boolean =
        accessibilityBinding(context).needsManualRebind

    fun notificationAccessState(context: Context): ServiceBindingState {
        return bindingEval(
            enabled = ClawNotificationListener.isEnabledInSettings(context),
            running = ClawNotificationListener.isConnected(),
            pendingRepair = KVUtils.hasPendingNotificationAccessReturn(),
            lastConnectedAt = KVUtils.getNotificationListenerLastConnectedAt(),
            lastDisconnectedAt = KVUtils.getNotificationListenerLastDisconnectedAt(),
        ).state
    }

    private fun bindingEval(
        enabled: Boolean,
        running: Boolean,
        pendingRepair: Boolean,
        lastConnectedAt: Long,
        lastHeartbeatAt: Long = 0L,
        lastInterruptedAt: Long = 0L,
        lastDisconnectedAt: Long,
    ): BindingEval {
        if (!enabled) return BindingEval(ServiceBindingState.DISABLED, needsManualRebind = false)
        if (pendingRepair) return BindingEval(ServiceBindingState.CONNECTING, needsManualRebind = false)

        val now = System.currentTimeMillis()
        val lastHealthyAt = maxOf(lastConnectedAt, lastHeartbeatAt)

        if (running) {
            if (lastInterruptedAt > lastHealthyAt) {
                val state = if (now - lastInterruptedAt <= ACCESSIBILITY_INTERRUPT_GRACE_MS) {
                    ServiceBindingState.CONNECTING
                } else {
                    ServiceBindingState.DEGRADED
                }
                return BindingEval(state, needsManualRebind = false)
            }
            return BindingEval(ServiceBindingState.READY, needsManualRebind = false)
        }

        // Process-young grace: after any process restart (manual force-stop, OS task-kill on
        // aggressive OEMs like Xiaomi/Samsung), the AccessibilityService singleton is null and
        // onServiceConnected hasn't fired yet for the new process. The OS is *expected* to
        // rebind within seconds — but on some OEM builds (Vivo in QA) it often never does,
        // which is why the stale fall-through below carries the needsManualRebind marker.
        // Anchor the grace to PROCESS start time so a fresh process always gets a fair window
        // regardless of how stale lastHealthyAt is in MMKV.
        if (now - processStartTimestamp <= PROCESS_START_REBIND_GRACE_MS) {
            return BindingEval(ServiceBindingState.CONNECTING, needsManualRebind = false)
        }

        if (lastHealthyAt <= 0L) {
            return BindingEval(ServiceBindingState.CONNECTING, needsManualRebind = false)
        }

        if (now - lastHealthyAt <= SERVICE_REBIND_GRACE_MS) {
            return BindingEval(ServiceBindingState.CONNECTING, needsManualRebind = false)
        }

        // Stale and unbound. The two branches above catch a clean goodbye (onDestroy or
        // onInterrupt recorded after the last healthy moment — service stopped or crashed
        // in place). Reaching here means neither goodbye ever arrived: the process was
        // killed outright — the OEM zombie. needsManualRebind = true.
        if (lastDisconnectedAt > 0L && lastDisconnectedAt >= lastHealthyAt) {
            return BindingEval(ServiceBindingState.DEGRADED, needsManualRebind = false)
        }
        if (lastInterruptedAt > 0L && lastInterruptedAt >= lastHealthyAt) {
            return BindingEval(ServiceBindingState.DEGRADED, needsManualRebind = false)
        }
        return BindingEval(ServiceBindingState.DEGRADED, needsManualRebind = true)
    }

    fun missingMonitorRequirements(context: Context): List<AppRequirement> {
        val capabilities = snapshot(context)
        val missing = mutableListOf<AppRequirement>()
        if (capabilities.accessibilityState != ServiceBindingState.READY) {
            missing.add(AppRequirement.ACCESSIBILITY)
        }
        if (capabilities.notificationAccessState != ServiceBindingState.READY) {
            missing.add(AppRequirement.NOTIFICATION_ACCESS)
        }
        return missing
    }

    fun isNotificationPermissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun openSystemSettings(context: Context, requirement: AppRequirement) {
        when (requirement) {
            AppRequirement.ACCESSIBILITY -> {
                if (accessibilityState(context) == ServiceBindingState.READY) {
                    KVUtils.clearPendingAccessibilityReturn()
                } else {
                    KVUtils.markPendingAccessibilityReturn()
                }
                launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            AppRequirement.NOTIFICATION_ACCESS -> {
                if (notificationAccessState(context) == ServiceBindingState.READY) {
                    KVUtils.clearPendingNotificationAccessReturn()
                } else {
                    KVUtils.markPendingNotificationAccessReturn()
                }
                launch(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            AppRequirement.OVERLAY -> {
                launch(
                    context,
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                )
            }
            AppRequirement.BATTERY_OPTIMIZATION -> {
                launch(
                    context,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                )
            }
            AppRequirement.STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    launch(
                        context,
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                    )
                }
            }
            AppRequirement.NOTIFICATION_PERMISSION -> Unit
        }
    }

    private fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun launch(context: Context, intent: Intent) {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
