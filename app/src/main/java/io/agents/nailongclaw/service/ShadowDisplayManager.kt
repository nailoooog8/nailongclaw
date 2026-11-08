// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import io.agents.nailongclaw.utils.XLog

/**
 * Owns the shadow display (虚拟屏): a virtual display the app creates so target
 * apps can be launched onto it and automated off the real screen, with the loop
 * hazards fenced at birth. The trusted end-state matches Mika's, but the path
 * does not: Mika builds its display from a shell-uid helper over wireless
 * debugging (hidden TRUSTED/OWN_FOCUS flag ladder), ours from the
 * user-consented MediaProjection below.
 *
 * The display itself is NOT what causes automation loops; unscoped events and
 * gestures are (an event from the shadow display bumping the real display's
 * content-version, a shadow node's coordinates tapped on the real display).
 * Everything created here must therefore be consumed only through the
 * display-scoped surface in [ClawAccessibilityService] — never through
 * `getRootInActiveWindow()` or plain `performTap`.
 *
 * No MediaProjection is needed: an app-owned virtual display requires no
 * special permission. The flag ladder below starts at PUBLIC|OWN_CONTENT_ONLY
 * (other packages can be launched onto a public display) and falls back to a
 * private own-content display; whichever rung the platform accepts is logged.
 *
 * Gated on API 33+: perception of shadow windows needs
 * [android.view.accessibility.AccessibilityWindowInfo.getDisplayId], which
 * only exists from Tiramisu. On older builds [ensureCreated] returns null and
 * every shadow tool degrades to a clear error.
 */
object ShadowDisplayManager {

    private const val TAG = "ShadowDisplay"
    private const val DISPLAY_NAME = "NailongClawShadow"

    /**
     * Flag rungs in preference order. PUBLIC|OWN_CONTENT_ONLY first: a public
     * display can host other packages' activities, and OWN_CONTENT_ONLY keeps
     * it from mirroring the default display — mirroring would duplicate every
     * window-change event, which is exactly the Mika-style loop trap.
     */
    private val FLAG_RUNGS = intArrayOf(
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    )

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    /**
     * The user-consented projection, when held. A display created THROUGH a
     * MediaProjection is trusted (API 34+): other packages' activities may be
     * launched onto it. A plainly-created display is untrusted and can host
     * only this app's own activities — the SecurityException the shadow tools
     * surfaced as「在其它显示器上启动 Activity 没权限」. The projection must be
     * obtained while a mediaProjection-type foreground service runs; see
     * ForegroundService's consent-start path.
     */
    @Volatile
    private var projection: MediaProjection? = null

    private var drainThread: HandlerThread? = null

    @Volatile
    var displayId: Int? = null
        private set

    @JvmStatic
    fun isCreated(): Boolean = displayId != null

    /**
     * Hands the freshly-consented projection over (called by the foreground
     * service right after `getMediaProjection`, while its mediaProjection FGS
     * type is live). Takes ownership: [release] stops it.
     */
    @Synchronized
    @JvmStatic
    fun attachProjection(newProjection: MediaProjection) {
        projection?.stop()
        projection = newProjection
        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // User revoked capture (cast icon / settings). The display is
                // dead with it — forget both so a later ensureCreated reports
                // honestly instead of handing out a stale id. Identity check:
                // onStop is dispatched async, so a REPLACED projection's stop
                // can land after a newer attach — tearing down the fresh setup
                // here would kill the re-authorization itself.
                if (projection !== newProjection) {
                    XLog.i(TAG, "Stale MediaProjection onStop ignored (a newer one is attached)")
                    return
                }
                XLog.i(TAG, "MediaProjection stopped by user; releasing shadow display")
                release()
            }
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * Creates the shadow display if absent and returns its id, or null when
     * the platform refuses every rung (or the API gate trips). Safe to call
     * repeatedly — an existing display is reused.
     */
    @Synchronized
    @JvmStatic
    fun ensureCreated(context: Context): Int? {
        displayId?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            XLog.w(TAG, "Shadow display needs API 33+ (window displayId), this is ${Build.VERSION.SDK_INT}")
            return null
        }
        val app = context.applicationContext
        val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = defaultDisplayMetrics(dm)

        // Without a surface the display has nothing to composite into and
        // takeScreenshot(displayId) comes back empty. The ImageReader is
        // drained eagerly (every frame closed) so the buffer queue never
        // stalls a producer app's rendering — an unconsumed queue would freeze
        // shadow apps and look exactly like a hang.
        val reader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2
        )
        val thread = HandlerThread("shadow-display-drain").apply { start() }
        drainThread = thread
        reader.setOnImageAvailableListener({ reader ->
            try {
                reader.acquireLatestImage()?.close()
            } catch (e: Exception) {
                XLog.w(TAG, "Shadow frame drain failed", e)
            }
        }, Handler(thread.looper))

        // Rung 0: through the user-consented projection. The projection must
        // carry PUBLIC: with OWN_CONTENT_ONLY alone MediaProjection wraps the
        // display into PRIVATE|PRESENTATION with canHostTasks=false — no task
        // can root there, not even from shell (V2502DA measured). A public
        // projection display is the cast-to-external-screen shape, and it is
        // the only projection shape that can host other packages' activities.
        projection?.let { proj ->
            try {
                val vd = proj.createVirtualDisplay(
                    DISPLAY_NAME,
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                        or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    reader.surface, null, null,
                )
                if (vd != null) {
                    virtualDisplay = vd
                    imageReader = reader
                    displayId = vd.display.displayId
                    XLog.i(TAG, "Shadow display created via MediaProjection: id=${vd.display.displayId}")
                    return displayId
                }
                XLog.w(TAG, "projection.createVirtualDisplay returned null")
            } catch (e: Exception) {
                XLog.w(TAG, "projection.createVirtualDisplay refused", e)
            }
        }

        for ((index, flags) in FLAG_RUNGS.withIndex()) {
            try {
                val vd = dm.createVirtualDisplay(
                    DISPLAY_NAME,
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    reader.surface, flags,
                    null, null,
                )
                if (vd != null) {
                    virtualDisplay = vd
                    imageReader = reader
                    displayId = vd.display.displayId
                    XLog.i(
                        TAG, "Shadow display created: id=${vd.display.displayId} " +
                            "rung=$index ${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}"
                    )
                    return displayId
                }
                XLog.w(TAG, "createVirtualDisplay returned null for flag rung $index")
            } catch (e: Exception) {
                XLog.w(TAG, "createVirtualDisplay refused flag rung $index", e)
            }
        }

        // Every rung refused — clean up so a retry starts fresh.
        reader.close()
        thread.quitSafely()
        drainThread = null
        imageReader = null
        XLog.e(TAG, "Shadow display could not be created (all flag rungs refused)")
        return null
    }

    /** Tears the shadow display down. Safe to call when nothing exists. */
    @Synchronized
    @JvmStatic
    fun release() {
        releaseDisplayOnly()
        try {
            // Stops capture and fires our own callback, which calls release()
            // again — guarded by the null-out below, so it terminates.
            projection?.stop()
        } catch (e: Exception) {
            XLog.w(TAG, "MediaProjection stop failed", e)
        }
        projection = null
        XLog.i(TAG, "Shadow display released")
    }

    /**
     * Tears down only the display (and its reader/drain thread), keeping the
     * consented projection. Caller holds the monitor via the @Synchronized
     * wrappers; the projection callback's identity check makes any async
     * onStop from a dead display harmless.
     */
    private fun releaseDisplayOnly() {
        if (displayId == null && virtualDisplay == null && imageReader == null) return
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            XLog.w(TAG, "Shadow display release failed", e)
        }
        try {
            imageReader?.close()
        } catch (e: Exception) {
            XLog.w(TAG, "Shadow ImageReader close failed", e)
        }
        drainThread?.quitSafely()
        virtualDisplay = null
        imageReader = null
        drainThread = null
        displayId = null
        XLog.i(TAG, "Shadow display released")
    }

    /**
     * Rebuilds the display through the consented projection, discarding any
     * display that pre-dates the consent. ensureCreated alone would
     * short-circuit on the existing id and the user would stay on a plain
     * untrusted display forever — the exact order trap of "创建后再授权".
     */
    @Synchronized
    @JvmStatic
    fun recreate(context: Context): Int? {
        releaseDisplayOnly()
        return ensureCreated(context)
    }

    /** The shadow display id for Java callers, or null when none exists. */
    @JvmStatic
    fun getDisplayIdOrNull(): Int? = displayId

    private fun defaultDisplayMetrics(dm: DisplayManager): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display: Display? = dm.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            display.getRealMetrics(metrics)
            return metrics
        }
        // No default display visible (should not happen on a phone) — a
        // phone-shaped fallback keeps creation from crashing.
        metrics.widthPixels = 1080
        metrics.heightPixels = 2400
        metrics.densityDpi = 440
        return metrics
    }
}
