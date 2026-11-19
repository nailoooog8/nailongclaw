// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.agents.nailongclaw.AppCapabilityCoordinator
import io.agents.nailongclaw.R
import io.agents.nailongclaw.ServiceBindingState
import io.agents.nailongclaw.utils.XLog

/**
 * The one window in which a foreground-service start can still kill the
 * whole process.
 *
 * `startForegroundService()` registers an obligation: the service MUST
 * reach `startForeground()` within ~5s or the system throws
 * `ForegroundServiceDidNotStartInTimeException` at the app. Calling
 * `stopService()` in the gap between the dispatch and the service's
 * creation leaves that obligation unfulfillable — the service is never
 * brought up, so `onCreate`'s `startForeground` never runs. That is
 * exactly what a fast-failing task does: the task fails in ~26ms (a11y
 * missing), the terminal path calls `resetToIdle()` → `stop()`, and the
 * process dies 5s later (v068-fgs-race's "onCreate starts foreground"
 * fix only covers the case where the service was already created).
 *
 * So the stop is deferred instead: the service is allowed to come up,
 * fulfil the obligation, and then stop itself. Extracted from the service
 * companion (which is full of Android types) so the state machine is
 * testable on the JVM.
 */
internal class ForegroundStartGuard {

    @Volatile
    private var dispatchInFlight = false

    @Volatile
    private var stopRequestedWhileStarting = false

    /** A `startForegroundService` dispatch was accepted by the system. */
    fun onDispatched() {
        dispatchInFlight = true
        // A new start supersedes any stop that arrived while a previous
        // one was still coming up — that stop was about the old task.
        stopRequestedWhileStarting = false
    }

    /** The dispatch itself failed (e.g. background-start refusal). */
    fun onDispatchFailed() {
        dispatchInFlight = false
    }

    /** The service object exists; `onCreate` owns the obligation now. */
    fun onServiceCreated() {
        dispatchInFlight = false
    }

    /**
     * Whether [ForegroundService.stop] must NOT cancel the launch yet.
     * Side effect: remembers that a stop is waiting, so the service can
     * honour it once it has called `startForeground`.
     */
    fun deferStop(serviceRunning: Boolean): Boolean {
        if (serviceRunning || !dispatchInFlight) return false
        stopRequestedWhileStarting = true
        return true
    }

    /** Read-and-clear, from `onStartCommand` after `startForeground`. */
    fun consumeStopAfterStart(): Boolean {
        val pending = stopRequestedWhileStarting
        stopRequestedWhileStarting = false
        return pending
    }
}

/**
 * Foreground service for active task / monitor notifications only.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        private const val MONITOR_HEALTH_POLL_MS = 5_000L
        const val CHANNEL_ID = "NailongClaw_foreground_channel"
        const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val DEFAULT_TASK_TITLE = "NailongClaw · Task in progress"
        private const val DEFAULT_TASK_TEXT = "Running task..."
        private const val DEFAULT_MONITOR_TITLE = "NailongClaw · Monitoring"
        private const val DEGRADED_MONITOR_TITLE = "NailongClaw · Monitoring paused"
        private const val KEY_KEEPALIVE_PERSISTENT = "KEY_KEEPALIVE_PERSISTENT"

        private enum class ForegroundMode {
            IDLE,
            TASK,
            MONITOR,
        }

        private val startGuard = ForegroundStartGuard()

        @Volatile
        private var _isRunning = false

        @Volatile
        private var _mode = ForegroundMode.IDLE

        /**
         * Check whether the foreground service is running
         */
        fun isRunning(): Boolean = _isRunning

        /**
         * Update the foreground notification with task progress text.
         * Safe to call from any thread — posts to NotificationManager directly.
         */
        fun updateTaskStatus(context: Context, statusText: String) {
            _mode = ForegroundMode.TASK
            showNotification(context, DEFAULT_TASK_TITLE, statusText)
        }

        /**
         * Show monitor state if auto-reply is active, otherwise stop the foreground service.
         */
        fun resetToIdle(context: Context) {
            syncToBackgroundState(context)
        }

        fun showMonitorStatus(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            if (!manager.isEnabled || manager.monitoredContacts.isEmpty()) {
                _mode = ForegroundMode.IDLE
                stop(context)
                return false
            }
            _mode = ForegroundMode.MONITOR
            val capabilities = AppCapabilityCoordinator.snapshot(context)
            if (capabilities.notificationAccessState != ServiceBindingState.READY) {
                return showNotification(
                    context,
                    DEGRADED_MONITOR_TITLE,
                    if (capabilities.notificationAccessState == ServiceBindingState.CONNECTING) {
                        "Notification Access reconnecting…"
                    } else {
                        "Notification Access disconnected"
                    }
                )
            }
            if (capabilities.accessibilityState != ServiceBindingState.READY) {
                return showNotification(
                    context,
                    DEGRADED_MONITOR_TITLE,
                    if (capabilities.accessibilityState == ServiceBindingState.CONNECTING) {
                        "Accessibility reconnecting…"
                    } else {
                        "Accessibility disconnected"
                    }
                )
            }
            val contacts = manager.monitoredContacts.toList()
            val text = when (contacts.size) {
                0 -> "Monitoring in background"
                1 -> "Monitoring ${contacts.first()}"
                else -> "Monitoring ${contacts.size} chats"
            }
            return showNotification(context, DEFAULT_MONITOR_TITLE, text)
        }

        fun syncToBackgroundState(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            if (manager.isEnabled && manager.monitoredContacts.isNotEmpty()) {
                return showMonitorStatus(context)
            }
            val keepText = when {
                // User opted for a persistent foreground service ("常驻后台")
                isPersistentKeepAliveEnabled() -> "Keep-alive active"
                // Keep alive while any channel (Telegram/Discord/WeChat) holds a live
                // connection, otherwise vivo-style ROMs freeze the process and drop
                // the polling/socket.
                hasConnectedChannels() -> "Channels connected"
                // Accessibility is the auto-reply infrastructure: keep the process
                // alive whenever it's enabled so the system doesn't kill the binding.
                isAccessibilityEnabled(context) -> "Keep-alive active"
                else -> null
            }
            _mode = ForegroundMode.IDLE
            return if (keepText != null) {
                showNotification(context, DEFAULT_MONITOR_TITLE, keepText)
            } else {
                stop(context)
                false
            }
        }

        fun isPersistentKeepAliveEnabled(): Boolean {
            // Default off: this key is never written on existing installs, so a
            // default of true made every install look like it had opted in and
            // kept isKeepAliveNeeded() permanently true, so stop() never ran.
            return runCatching { io.agents.nailongclaw.utils.KVUtils.getBoolean(KEY_KEEPALIVE_PERSISTENT, false) }
                .getOrDefault(false)
        }

        fun setPersistentKeepAliveEnabled(enabled: Boolean) {
            io.agents.nailongclaw.utils.KVUtils.putBoolean(KEY_KEEPALIVE_PERSISTENT, enabled)
        }

        fun hasConnectedChannels(): Boolean {
            return runCatching { io.agents.nailongclaw.channel.ChannelManager.hasConnectedChannel() }
                .getOrDefault(false)
        }

        private fun isAccessibilityEnabled(context: Context): Boolean {
            return runCatching { ClawAccessibilityService.isEnabledInSettings(context) }
                .getOrDefault(false)
        }

        private fun isKeepAliveNeeded(context: Context): Boolean {
            return isPersistentKeepAliveEnabled() || hasConnectedChannels() ||
                isAccessibilityEnabled(context)
        }

        private fun showNotification(context: Context, title: String, text: String): Boolean {
            if (!hasNotificationPermission(context)) {
                return false
            }

            try {
                if (_isRunning) {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(context, title, text))
                    return true
                }
                start(context, title, text)
                return true
            } catch (e: Exception) {
                XLog.w(TAG, "Foreground notification update failed", e)
                return false
            }
        }

        /**
         * Start the foreground service
         * @param context Context
         * @return true if started successfully, false if notification permission is missing
         */
        fun start(
            context: Context,
            title: String = context.getString(R.string.notification_content_title),
            text: String = context.getString(R.string.notification_content_text)
        ): Boolean {
            // Android 13+ requires notification permission check
            if (!hasNotificationPermission(context)) {
                return false
            }

            return try {
                val intent = Intent(context, ForegroundService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                startGuard.onDispatched()
                true
            } catch (e: Exception) {
                // API 31+ throws ForegroundServiceStartNotAllowedException when the
                // app is in the background. Surface it instead of only logging,
                // but only when we still hold an Activity to show it on.
                XLog.w(TAG, "Foreground service start blocked or failed", e)
                startGuard.onDispatchFailed()
                if (context !is android.app.Application) {
                    runCatching {
                        Toast.makeText(
                            context,
                            "无法启动前台服务，任务保活未生效",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                false
            }
        }

        private fun hasNotificationPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        private const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        private const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        /**
         * Starts (or upgrades) this service to hold a MediaProjection for the
         * shadow display. Call from the Activity that just received the
         * screen-capture consent result — the fresh consent is what makes an
         * FGS start with the mediaProjection type legal, and Android 14+
         * refuses `MediaProjection.createVirtualDisplay` until a foreground
         * service of that type is running. Null = the display is up.
         */
        fun startWithProjection(context: Context, resultCode: Int, data: Intent): String? {
            return try {
                val intent = Intent(context, ForegroundService::class.java).apply {
                    putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                    putExtra(EXTRA_PROJECTION_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                startGuard.onDispatched()
                null
            } catch (e: Exception) {
                XLog.w(TAG, "Projection foreground-service start failed", e)
                startGuard.onDispatchFailed()
                "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        fun stop(context: Context) {
            _mode = ForegroundMode.IDLE
            if (startGuard.deferStop(_isRunning)) {
                // A startForegroundService() dispatch is still in the air and the
                // service has not been created yet. Cancelling the launch here
                // strands the 5s startForeground obligation — the service never
                // reaches onCreate, and the system kills the process with
                // ForegroundServiceDidNotStartInTimeException. Let it come up
                // (onCreate starts foreground unconditionally) and have
                // onStartCommand run this stop once the obligation is met.
                XLog.i(TAG, "stop() deferred — foreground service still starting")
                runCatching {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(NOTIFICATION_ID)
                }
                return
            }
            val intent = Intent(context, ForegroundService::class.java)
            context.stopService(intent)
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
            }
        }

        private fun buildNotification(context: Context, title: String, text: String): Notification {
            // Tap → the main screen (the Claude UI surface since 2026-10-24).
            // No extras involved, so the chat screen's task-extra contract is
            // not a reason to keep the old destination here.
            val intent = Intent(context, io.agents.nailongclaw.ui.web.ClaudeUiPreviewActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .build()
        }
    }

    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthRunnable = object : Runnable {
        override fun run() {
            if (!_isRunning) return
            if (_mode == ForegroundMode.MONITOR) {
                ForegroundService.syncToBackgroundState(applicationContext)
            } else if (_mode == ForegroundMode.IDLE && !isKeepAliveNeeded(applicationContext)) {
                // Keepalive no longer needed: persistent flag off, last channel
                // dropped and accessibility disabled
                _mode = ForegroundMode.IDLE
                stop(applicationContext)
            }
            healthHandler.postDelayed(this, MONITOR_HEALTH_POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning = true
        startGuard.onServiceCreated()
        createNotificationChannel()
        // Typed explicitly: with mediaProjection added to the manifest
        // types, the two-arg startForeground on API 34+ would claim EVERY
        // manifest type and demand mediaProjection eligibility on ordinary
        // restarts. Normal operation only ever claims specialUse.
        //
        // Unconditional: a foreground-service notification does NOT require
        // POST_NOTIFICATIONS — it is shown even when that runtime permission
        // is missing. The old stopSelf() branch here (fresh installs that had
        // never granted notifications) left the startForegroundService()
        // obligation unfulfilled and crashed the whole app with
        // ForegroundServiceDidNotStartInTimeException the moment the user
        // consented to screen capture (emulator QA 2026-11-15, API 35).
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(this, DEFAULT_TASK_TITLE, DEFAULT_TASK_TEXT),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        healthHandler.post(healthRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        healthHandler.removeCallbacksAndMessages(null)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Acquires the projection with the fresh consent token and creates the
     * shadow display through it (trusted → other packages can be launched
     * onto the display). Runs after the mediaProjection FGS type is live.
     */
    private fun handleProjection(resultCode: Int, data: Intent) {
        val message = try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data)
            if (projection == null) {
                "授权令牌无效（getMediaProjection 返回空）"
            } else {
                ShadowDisplayManager.attachProjection(projection)
                // recreate, not ensureCreated: a display created BEFORE the
                // consent (plain untrusted screen) must not short-circuit the
                // projection rung — that was the "创建后再授权仍被拒" trap.
                val id = ShadowDisplayManager.recreate(applicationContext)
                if (id != null) null
                else "投影已授权但影子屏创建失败（见日志）"
            }
        } catch (e: Exception) {
            XLog.w(TAG, "MediaProjection acquisition failed", e)
            "${e.javaClass.simpleName}: ${e.message}"
        }
        runCatching {
            Toast.makeText(
                this,
                message ?: "影子屏已就绪（投影授权成功）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification(intent)
        val projectionResultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Int.MIN_VALUE)
        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }
        if (projectionResultCode != null && projectionResultCode != Int.MIN_VALUE && projectionData != null) {
            // Escalate to the mediaProjection type FIRST — Android 14+ refuses
            // getMediaProjection/createVirtualDisplay until a foreground
            // service of that type is running.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            handleProjection(projectionResultCode, projectionData)
        } else {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }
        if (intent == null) {
            // System restarted the service after a process kill (START_STICKY).
            // Re-sync to actual state: keep running only if monitoring or a
            // channel still needs it, otherwise shut down cleanly.
            _mode = ForegroundMode.IDLE
            if (!syncToBackgroundState(applicationContext)) {
                stopSelf()
            }
        } else if (startGuard.consumeStopAfterStart()) {
            // stop() arrived while this service was still being created (a task
            // failed in the gap between startForegroundService() and onCreate).
            // The startForeground above has now met the obligation, so it is
            // finally safe to go down — re-running the idle sync keeps the
            // keep-alive reasons (monitor / channels / accessibility) winning,
            // exactly as the deferred stop() intended.
            _mode = ForegroundMode.IDLE
            XLog.i(TAG, "Honouring stop() deferred while the service was starting")
            if (!syncToBackgroundState(applicationContext)) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(intent: Intent?): Notification {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TASK_TITLE
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TASK_TEXT
        return buildNotification(this, title, text)
    }
}
