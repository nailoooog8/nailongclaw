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
 * The flag ladder mirrors Mika's ShellService: Mika's base five
 * (PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES_WITH_CONTENT|
 * DESTROY_CONTENT_ON_REMOVAL), then — through the consented projection, the
 * only shape that can host other packages on the vivo QA ROM — Mika's hidden
 * focus-isolation bits (TRUSTED|OWN_DISPLAY_GROUP|ALWAYS_UNLOCKED on 33+,
 * OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED on 34+), degrading rung by rung the way
 * Mika degrades trusted → untrusted. Whichever rung the platform accepts is
 * logged with its label; a device that refuses the hidden bits keeps full
 * function, just without the shadow display's own focus.
 *
 * Platform split (measured on the h79 AVD, API 35, 2026-11-15): AOSP's
 * MediaProjection force-adds PRIVATE on top of a requested PUBLIC display
 * (DisplayDeviceInfo mFlags=467: PUBLIC|PRIVATE|PRESENTATION together), and
 * a PRIVATE display refuses activity launches even from its owner uid — so
 * [io.agents.nailongclaw.tool.impl.mobile.shadow.ShadowOpenAppTool] cannot
 * work there and shadow apps must be placed with shell `am start --display`.
 * The vivo ROM does not add PRIVATE, which is what makes the production
 * launch path viable. Details in BACKLOG (2026-11-15 platform-difference
 * entry).
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
     * Mika-parity display flags. The public SDK (checked against android-36's
     * android.jar) exposes only [DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC]
     * (1) and [DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY] (8);
     * every other rung bit is a hidden constant the platform enforces by
     * permission rather than by SDK visibility, so it is written as a shift —
     * the same technique Mika's ShellService uses. Bit values are AOSP's,
     * cross-checked against Mika's ShellService: the two public ones match
     * its shifts exactly.
     */
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_OWN_FOCUS = 1 shl 14
    private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    /**
     * Mika's base five. PUBLIC lets other packages be launched onto the
     * display; OWN_CONTENT_ONLY keeps it from mirroring the default display —
     * mirroring would duplicate every window-change event, which is exactly
     * the Mika-style loop trap. SUPPORTS_TOUCH makes the display report touch
     * support, ROTATES_WITH_CONTENT lets it follow its content's orientation
     * instead of letterboxing, and DESTROY_CONTENT_ON_REMOVAL closes the
     * activities on it when the display goes away instead of spilling them
     * onto the default display (one of the escape vectors).
     */
    private val BASE_FLAGS: Int = (
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            or FLAG_SUPPORTS_TOUCH
            or FLAG_ROTATES_WITH_CONTENT
            or FLAG_DESTROY_CONTENT_ON_REMOVAL
        )

    /**
     * Focus isolation — the point of Mika's hidden rung: TRUSTED +
     * OWN_DISPLAY_GROUP + ALWAYS_UNLOCKED (33+) and OWN_FOCUS +
     * STEAL_TOP_FOCUS_DISABLED (34+) give the shadow display its own focus,
     * so a foregrounded shadow app cannot steal the real screen's focus or
     * raise its IME. These are permission-checked, so a non-shell caller
     * (us) is normally refused — which is why they ride as their own rung
     * above [BASE_FLAGS] and degrade exactly like Mika's trusted → untrusted
     * retry. 0 when the platform is too old for any of them.
     */
    private fun focusIsolationFlags(): Int {
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }

    /** One rung of the creation ladder in [ensureCreated]. */
    private data class Rung(val label: String, val flags: Int, val viaProjection: Boolean)

    /**
     * Tries one rung: through the consented projection when [Rung.viaProjection],
     * else a plain app-created display. Returns null on refusal (or a null
     * display) so the caller degrades to the next rung — a rung that throws is
     * logged with its flags, because which rung wins is a device-QA question.
     */
    private fun createOnRung(dm: DisplayManager, rung: Rung, metrics: DisplayMetrics, surface: Surface): VirtualDisplay? {
        return try {
            val vd = if (rung.viaProjection) {
                projection?.createVirtualDisplay(
                    DISPLAY_NAME,
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    rung.flags, surface, null, null,
                )
            } else {
                dm.createVirtualDisplay(
                    DISPLAY_NAME,
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    surface, rung.flags, null, null,
                )
            }
            if (vd == null) {
                XLog.w(TAG, "createVirtualDisplay returned null for rung ${rung.label}")
            }
            vd
        } catch (e: Exception) {
            XLog.w(TAG, "createVirtualDisplay refused rung ${rung.label}", e)
            null
        }
    }

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    /**
     * The user-consented projection, when held. On the vivo ROM a display
     * created THROUGH a MediaProjection can host other packages' activities;
     * AOSP instead force-adds PRIVATE (see the class doc's platform split),
     * where even the owner uid is refused. A plainly-created display is
     * untrusted everywhere and can host only this app's own activities — the
     * SecurityException the shadow tools surfaced as「在其它显示器上启动
     * Activity 没权限」. The projection must be obtained while a
     * mediaProjection-type foreground service runs; see ForegroundService's
     * consent-start path.
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

        // Creation ladder, best rung first (Mika's ShellService order). The
        // projection rungs lead: on the vivo ROM a projection display is the
        // only shape that can host other packages' activities — a plain
        // display is untrusted everywhere and could host only our own
        // activities (the "在其它显示器上启动 Activity 没权限"
        // SecurityException); on AOSP the projection display is force-added
        // PRIVATE and hosts nothing but us either way (see the class doc's
        // platform split). Within the projection rungs the focus-isolation
        // bits come first and degrade on refusal, exactly like Mika's
        // trusted → untrusted retry.
        val focusFlags = focusIsolationFlags()
        val rungs = buildList {
            if (projection != null) {
                if (focusFlags != 0) {
                    add(Rung("projection+focus-isolation", BASE_FLAGS or focusFlags, true))
                }
                add(Rung("projection+extended", BASE_FLAGS, true))
            }
            add(Rung("plain+extended", BASE_FLAGS, false))
            add(Rung("plain+own-content-only", DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, false))
        }
        for ((index, rung) in rungs.withIndex()) {
            val vd = createOnRung(dm, rung, metrics, reader.surface) ?: continue
            virtualDisplay = vd
            imageReader = reader
            displayId = vd.display.displayId
            XLog.i(
                TAG, "Shadow display created: id=${vd.display.displayId} " +
                    "rung=$index(${rung.label}) ${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}"
            )
            return displayId
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
