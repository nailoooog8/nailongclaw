// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.base

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.blankj.utilcode.util.AdaptScreenUtils
import com.blankj.utilcode.util.BarUtils
import io.agents.nailongclaw.R
import kotlin.math.max

/**
 *
 * Screen adaptation uses pt; using dp on some devices causes toast line breaks at incorrect positions
 */
open class BaseActivity : AppCompatActivity() {

    override fun getResources(): Resources {
        val resources = super.getResources()
        return AdaptScreenUtils.adaptWidth(resources, getDesignWidth())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyStatusBarMode()

        // Handle status bar height uniformly - applied after layout is loaded
        applyStatusBarPadding()
    }

    /**
     * Auto-callback on theme switch to update status bar text color
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBarMode()
    }

    /**
     * Set status bar text color based on current theme mode
     * Light theme → dark text (light mode)
     * Dark theme → light text (dark mode)
     */
    private fun applyStatusBarMode() {
        val isNightMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        BarUtils.setStatusBarLightMode(this, !isNightMode)
    }

    /**
     * Add top padding equal to the status bar height to the root view
     * Subclasses can disable this by overriding isApplyStatusBarPadding()
     */
    private fun applyStatusBarPadding() {
        window.decorView.post {
            val rootView = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
            rootView?.let { applyPaddingToRootView(it) }
        }
    }

    /**
     * Apply status bar height padding to the root view
     * If the layout already handles insets, override this method to customize behavior
     */
    protected open fun applyPaddingToRootView(rootView: View) {
        if (!isApplyStatusBarPadding()) return

        val statusBarHeight = BarUtils.getStatusBarHeight()
        val existingPaddingTop = rootView.paddingTop

        // Only add padding if there is none or it is smaller than the status bar height
        if (existingPaddingTop < statusBarHeight) {
            rootView.updatePadding(top = statusBarHeight)
        }
    }

    /**
     * Subclasses can override this method to disable automatic status bar height padding
     */
    protected open fun isApplyStatusBarPadding(): Boolean = true

    /**
     * Keep focused fields visible above the IME on edge-to-edge layouts.
     * `adjustResize` alone is not reliable here because the ScrollView content
     * still needs extra bottom inset + explicit scroll after the keyboard opens.
     *
     * Shared by the credential editors that hold their fields inside a
     * ScrollView (LlmConfigActivity's cloud form, ChannelConfigActivity's
     * channel credentials). Pass an empty [fields] list for screens whose only
     * input sits above the list — the padding alone is what those need, and
     * scrolling a non-descendant into view is meaningless.
     */
    protected fun installKeyboardAwareScrolling(scrollView: ScrollView, fields: List<EditText>) {
        val baseBottomPadding = scrollView.paddingBottom

        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                scrollView.postDelayed({ scrollFieldIntoView(scrollView, view) }, 180)
            }
        }
        fields.forEach { it.onFocusChangeListener = focusListener }

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = baseBottomPadding + max(imeInsets.bottom, systemInsets.bottom))

            if (imeInsets.bottom > 0) {
                val focused = currentFocus
                if (focused is EditText && fields.contains(focused)) {
                    view.post { scrollFieldIntoView(scrollView, focused) }
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(scrollView)
    }

    private fun scrollFieldIntoView(scrollView: ScrollView, field: View) {
        val rect = Rect()
        field.getDrawingRect(rect)
        scrollView.offsetDescendantRectToMyCoords(field, rect)

        val margin = (16 * resources.displayMetrics.density).toInt()
        val visibleTop = scrollView.scrollY + margin
        val visibleBottom = scrollView.scrollY + scrollView.height - scrollView.paddingBottom - margin

        when {
            rect.top < visibleTop -> {
                scrollView.smoothScrollTo(0, (rect.top - margin).coerceAtLeast(0))
            }
            rect.bottom > visibleBottom -> {
                val targetScroll = scrollView.scrollY + (rect.bottom - visibleBottom) + margin
                scrollView.smoothScrollTo(0, targetScroll)
            }
        }
    }

    /**
     * Screen adaptation - design spec size
     */
    open fun getDesignWidth(): Int {
        return 402
    }
}