// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.cds

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.agents.pokeclaw.R
import io.agents.pokeclaw.widget.CommonToolbar

/**
 * The Claude Design System tokens for native Views, mirroring
 * `mockup/claude-ui/cds.css` value for value (which itself copies
 * Caho1/open-claude-ui's `cds-theme.css`). The WebView surface renders the
 * same numbers through CSS; this file is the View-side half so the native
 * settings editors look like they belong to the same product as the sheet
 * that launches them.
 *
 * Every constant below cites the CSS custom property it mirrors. Light values
 * are the `[data-base="clay"]` defaults (the base the live surface uses —
 * `ClaudeUiPreviewActivity` clears the stored base/accent keys, so defaults
 * are what the user actually sees); dark values are the `[data-mode="dark"]`
 * overrides on the same base. Resolution follows the system night mode, which
 * is also what the WebView falls back to in live mode.
 */
object CdsUi {

    class Palette(
        val isDark: Boolean,
        val surface0: Int,
        val surface1: Int,
        val surface2: Int,
        val surface3: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val accent: Int,
        val accentHover: Int,
        val accentSubtle: Int,
        val onAccent: Int,
        val border: Int,
        val borderStrong: Int,
        val ghostHover: Int,
        val fillSecondary: Int,
        val danger: Int,
    )

    private const val INK = 0x1D1916.toInt() // hsla(24,12%,10%) — the light-mode ink hue

    private val LIGHT = Palette(
        isDark = false,
        surface0 = 0xFFF2E9E1.toInt(), // --cds-surface-0
        surface1 = 0xFFFAF2EB.toInt(), // --cds-surface-1（画布）
        surface2 = 0xFFFDF8F4.toInt(), // --cds-surface-2（卡片/面板）
        surface3 = 0xFFFDF8F4.toInt(), // --cds-surface-3（输入框）
        textPrimary = 0xFF151515.toInt(),   // --cds-text-primary
        textSecondary = 0xFF525252.toInt(), // --cds-text-secondary
        textMuted = 0xFF6B625C.toInt(),     // --cds-text-muted
        accent = 0xFFD97757.toInt(),        // --theme-accent 陶土橙
        accentHover = 0xFFCC5A36.toInt(),   // --theme-accent-hover
        accentSubtle = 0xFFFBF3EF.toInt(),  // --theme-accent-subtle
        onAccent = 0xFF151515.toInt(),      // --theme-accent-on（accent 上的文字实测 ≥4.5）
        border = (0x24 shl 24) or INK,      // --cds-alpha-2 边框（0.14）
        borderStrong = (0x3D shl 24) or INK,// --cds-alpha-3（0.24）
        ghostHover = (0x0D shl 24) or INK,  // --cds-alpha-1（0.05）
        fillSecondary = (0x24 shl 24) or INK, // --cds-fill-secondary
        danger = 0xFFEF4444.toInt(),        // --cds-fill-danger
    )

    private val DARK = Palette(
        isDark = true,
        surface0 = 0xFF0B0B0B.toInt(),
        surface1 = 0xFF151515.toInt(),
        surface2 = 0xFF1A1A19.toInt(),
        surface3 = 0xFF20201F.toInt(),
        textPrimary = 0xFFF5F5F5.toInt(),
        textSecondary = 0xFFA3A3A3.toInt(),
        textMuted = 0xFF8A8A8A.toInt(),
        accent = 0xFFD97757.toInt(),
        accentHover = 0xFFCC5A36.toInt(),
        accentSubtle = (0x26 shl 24) or 0xD97757, // color-mix(accent 15%, transparent)
        onAccent = 0xFF151515.toInt(),
        border = (0x17 shl 24) or 0xFFFFFF,  // --cds-alpha-2 dark（white 0.09）
        borderStrong = (0x2E shl 24) or 0xFFFFFF, // --cds-alpha-3 dark（white 0.18）
        ghostHover = (0x0D shl 24) or 0xFFFFFF,
        fillSecondary = (0x17 shl 24) or 0xFFFFFF,
        danger = 0xFFF87171.toInt(),
    )

    /** Resolves the palette from the system night mode — the same signal the
     *  WebView's live mode falls back to (theme override keys are cleared). */
    fun palette(activity: Activity): Palette =
        if ((activity.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) DARK else LIGHT

    fun dp(context: android.content.Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    fun dp(context: android.content.Context, v: Float): Float =
        v * context.resources.displayMetrics.density

    /** The CDS panel: surface-2 fill, 1px hairline border, `--cds-panel-radius` 10px.
     *  (`--cds-panel-shadow` = 0 0 0 1px border + shadow-sm; the stroke stands in
     *  for the ring, elevation stays 0 to match the flat sheet look.) */
    fun panel(context: android.content.Context, p: Palette): GradientDrawable =
        GradientDrawable().apply {
            setColor(p.surface2)
            cornerRadius = dp(context, 10f)
            setStroke(dp(context, 1), p.border)
        }

    /** Text input: `--cds-surface-3` fill with an `--cds-alpha-2` hairline,
     *  `--cds-radius` 8px corners (composer field treatment). */
    fun input(context: android.content.Context, p: Palette): GradientDrawable =
        GradientDrawable().apply {
            setColor(p.surface3)
            cornerRadius = dp(context, 8f)
            setStroke(dp(context, 1), p.border)
        }

    /** Ghost row press state — `.settings-row:active { background: fill-ghost-selected }`. */
    fun ghostSelected(p: Palette): Int = p.fillSecondary

    /** `.group-label`: 11px / 600 / muted / 0.02em letterspacing. */
    fun styleGroupLabel(tv: TextView, p: Palette) {
        tv.setTextColor(p.textMuted)
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.letterSpacing = 0.02f
    }

    /** `.settings-topbar` treatment for CommonToolbar: canvas background, no
     *  divider shadow, title left of nothing at 15sp/600 — the sheet's
     *  `.settings-title` (14.5px/600) scaled to sp. Title sits next to the
     *  back arrow like the sheet's header, not centered. */
    fun styleToolbar(activity: AppCompatActivity, toolbar: CommonToolbar, p: Palette, title: CharSequence) {
        toolbar.setTitle(title)
        toolbar.setTitleCentered(false)
        toolbar.setBackgroundColor(p.surface1)
        toolbar.setTitleColor(p.textPrimary)
        toolbar.findViewById<TextView>(R.id.tvTitle)?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }
        toolbar.findViewById<ImageView>(R.id.ivBack)?.setColorFilter(p.textPrimary)
        activity.window.statusBarColor = p.surface1
    }

    /** Applies the input treatment + palette to an EditText in place. */
    fun styleEditText(et: EditText, p: Palette) {
        et.background = input(et.context, p)
        et.setTextColor(p.textPrimary)
        et.setHintTextColor(p.textMuted)
    }
}
