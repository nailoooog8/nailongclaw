// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.settings

import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import io.agents.nailongclaw.R
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.ui.cds.CdsUi
import io.agents.nailongclaw.utils.KVUtils
import io.agents.nailongclaw.utils.XLog
import io.agents.nailongclaw.widget.CommonToolbar
import io.agents.nailongclaw.widget.CdsSegmented
import io.agents.nailongclaw.widget.MenuGroup

/**
 * 主题 — the merged theme page: 深浅 × 底色 × 强调色, three orthogonal axes in
 * one place.
 *
 * This replaces two half-pages that used to fight each other: the old native
 * page offered ember light/dark only (no background control at all), while the
 * Claude UI sidebar popover controlled 底色 × 强调色 with a separate 深浅色
 * toggle. Same product, three inconsistent doors. Now there is one page (the
 * sidebar popover is the same three axes, quick access), and all three axes
 * persist to [KVUtils] — the single source of truth the Claude UI surface
 * reads through the PokeClawChat bridge.
 *
 * The values mirror `mockup/claude-ui/cds.css` rule for rule: each base is the
 * `[data-base=…]` surface ladder (light and dark), each accent is the
 * `[data-theme=…]` `--theme-accent`. Changing a value here changes what the
 * chat surface renders on its next resume; changing 深浅 applies app-wide
 * through [AppCompatDelegate] (no more `cmd uimode night` + process restart —
 * that ROM hack rebooted the whole app to flip one axis).
 */
class ThemeActivity : BaseActivity() {

    /** One `[data-base]` scheme: the three surface steps per mode. */
    private data class BaseScheme(
        val key: String,
        val labelRes: Int,
        val lightSurfaces: Triple<Int, Int, Int>,
        val darkSurfaces: Triple<Int, Int, Int>,
    )

    /** One `[data-theme]` accent. */
    private data class AccentScheme(
        val key: String,
        val labelRes: Int,
        val color: Int,
    )

    private lateinit var baseGroup: MenuGroup
    private lateinit var accentGroup: MenuGroup
    private lateinit var modeSegmented: CdsSegmented

    private val cds by lazy { CdsUi.palette(this) }

    private val baseSchemes = listOf(
        BaseScheme(
            "clay", R.string.theme_base_clay,
            Triple(0xFFF2E9E1.toInt(), 0xFFFAF2EB.toInt(), 0xFFFDF8F4.toInt()),
            Triple(0xFF0B0B0B.toInt(), 0xFF151515.toInt(), 0xFF1A1A19.toInt()),
        ),
        BaseScheme(
            "porcelain", R.string.theme_base_porcelain,
            Triple(0xFFECEBE7.toInt(), 0xFFF7F6F3.toInt(), 0xFFFCFBF9.toInt()),
            Triple(0xFF101010.toInt(), 0xFF1A1A1A.toInt(), 0xFF202020.toInt()),
        ),
        BaseScheme(
            "sand", R.string.theme_base_sand,
            Triple(0xFFE8DFD0.toInt(), 0xFFF3EBDD.toInt(), 0xFFFAF4EA.toInt()),
            Triple(0xFF100E0B.toInt(), 0xFF1A1713.toInt(), 0xFF201C17.toInt()),
        ),
        BaseScheme(
            "caramel", R.string.theme_base_caramel,
            Triple(0xFFE6D5C0.toInt(), 0xFFF1E4D2.toInt(), 0xFFF9F0E5.toInt()),
            Triple(0xFF120D08.toInt(), 0xFF1C1611.toInt(), 0xFF221B15.toInt()),
        ),
        BaseScheme(
            "taupe", R.string.theme_base_taupe,
            Triple(0xFFE4E1DA.toInt(), 0xFFEEECE6.toInt(), 0xFFF7F5F0.toInt()),
            Triple(0xFF0F0F0F.toInt(), 0xFF191919.toInt(), 0xFF1F1F1F.toInt()),        ),        BaseScheme(
            "rose", R.string.theme_base_rose,
            Triple(0xFFEAE0DD.toInt(), 0xFFF4ECEA.toInt(), 0xFFFBF5F3.toInt()),
            Triple(0xFF100C0D.toInt(), 0xFF1A1517.toInt(), 0xFF201B1D.toInt()),
        ),
        BaseScheme(
            "mint", R.string.theme_base_mint,
            Triple(0xFFDFEAE3.toInt(), 0xFFEEF4F0.toInt(), 0xFFF7FAF8.toInt()),
            Triple(0xFF0A0F0D.toInt(), 0xFF141A17.toInt(), 0xFF1A201D.toInt()),
        ),
    )

    private val accentSchemes = listOf(
        AccentScheme("clay", R.string.theme_accent_clay, 0xFFD97757.toInt()),
        AccentScheme("clay-bright", R.string.theme_accent_clay_bright, 0xFFE08A68.toInt()),
        AccentScheme("sage", R.string.theme_accent_sage, 0xFF6F9068.toInt()),
        AccentScheme("indigo", R.string.theme_accent_indigo, 0xFF7B93C4.toInt()),
        AccentScheme("ochre", R.string.theme_accent_ochre, 0xFFC47068.toInt()),
        AccentScheme("wisteria", R.string.theme_accent_wisteria, 0xFFA084B8.toInt()),
        AccentScheme("teal", R.string.theme_accent_teal, 0xFF5C918E.toInt()),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Page chrome follows the *current* selection, not the system: this
        // page is where you pick the theme, so it must depict what it picks.
        window.decorView.setBackgroundColor(cds.surface1)
        setContentView(R.layout.activity_theme)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, getString(R.string.settings_group_appearance))
        toolbar.showBackButton(true) { finish() }

        baseGroup = findViewById(R.id.baseGroup)
        accentGroup = findViewById(R.id.accentGroup)
        baseGroup.applyCds(cds)
        accentGroup.applyCds(cds)

        modeSegmented = findViewById(R.id.modeSegmented)
        findViewById<LinearLayout>(R.id.modeCard)?.background = CdsUi.panel(this, cds)
        findViewById<TextView>(R.id.tvModeLabel)?.let { CdsUi.styleGroupLabel(it, cds) }
        findViewById<TextView>(R.id.tvModeHint).apply {
            CdsUi.styleChromeText(this, cds, 11f)
            text = getString(R.string.theme_mode_hint)
        }
        findViewById<TextView>(R.id.tvFooter).apply {
            CdsUi.styleChromeText(this, cds, 11f)
            text = getString(R.string.theme_page_foot)
        }

        modeSegmented.setOptions(
            options = listOf(
                "light" to getString(R.string.theme_option_light),
                "dark" to getString(R.string.theme_option_dark),
            ),
            selectedKey = currentModeKey(),
        ) { key -> onModeSelected(key) }

        renderBaseRows()
        renderAccentRows()
    }

    // ---------------------------------------------------------------- 深浅

    private fun currentModeKey(): String {
        val stored = KVUtils.getString("THEME_MODE", "")
        if (stored == "light" || stored == "dark") return stored
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (night) "dark" else "light"
    }

    private fun onModeSelected(key: String) {
        XLog.i("ThemeActivity", "mode -> $key")
        KVUtils.putString("THEME_MODE", key)
        // AppCompat recreates this activity with the new night mode; the
        // selection survives because it is already in KVUtils.
        AppCompatDelegate.setDefaultNightMode(
            if (key == "dark") AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    // ---------------------------------------------------------------- 底色

    private fun renderBaseRows() {
        baseGroup.clearMenuItems()
        baseGroup.setTitle(getString(R.string.theme_base_section))
        val selectedKey = KVUtils.getString("THEME_BASE", "clay")
        val isDark = currentModeKey() == "dark"
        // Fresh palette per render: a pick earlier in this session already
        // moved the axes, and the page must depict what it picks.
        val cds = CdsUi.palette(this)

        baseSchemes.forEachIndexed { index, scheme ->
            val surfaces = if (isDark) scheme.darkSurfaces else scheme.lightSurfaces
            baseGroup.addMenuItem(
                leadingIcon = R.drawable.ic_heart, // placeholder; replaced by the swatch below
                title = getString(scheme.labelRes),
                onClick = { onBaseSelected(scheme.key) },
                showDivider = index != baseSchemes.lastIndex
            ).apply {
                setTitle(getString(scheme.labelRes))
                setTitleColor(cds.textPrimary)
                val isSelected = scheme.key == selectedKey
                if (isSelected) {
                    setTrailingText(getString(R.string.theme_in_use))
                    setTrailingTextColor(cds.accent)
                } else {
                    setTrailingText(hexOf(surfaces.second))
                    setTrailingTextColor(cds.textMuted)
                }
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
                // The three-step ladder swatch: sidebar-deep → canvas → card.
                setLeadingIconSize(widthDp = 28, heightDp = 18)
                setLeadingIconDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        colors = intArrayOf(surfaces.first, surfaces.second, surfaces.third)
                        orientation = android.graphics.drawable.GradientDrawable
                            .Orientation.LEFT_RIGHT
                        cornerRadius = CdsUi.dp(context, 5f)
                        setStroke(CdsUi.dp(context, 1), cds.borderStrong)
                    }
                )
            }
        }
    }

    // ---------------------------------------------------------------- 强调色

    private fun renderAccentRows() {
        accentGroup.clearMenuItems()
        accentGroup.setTitle(getString(R.string.theme_accent_section))
        val selectedKey = KVUtils.getString("THEME_ACCENT", "clay")
        val cds = CdsUi.palette(this)

        accentSchemes.forEachIndexed { index, scheme ->
            accentGroup.addMenuItem(
                leadingIcon = R.drawable.ic_heart, // placeholder; replaced by the swatch below
                title = getString(scheme.labelRes),
                onClick = { onAccentSelected(scheme.key) },
                showDivider = index != accentSchemes.lastIndex
            ).apply {
                setTitle(getString(scheme.labelRes))
                setTitleColor(cds.textPrimary)
                val isSelected = scheme.key == selectedKey
                if (isSelected) {
                    setTrailingText(getString(R.string.theme_in_use))
                    setTrailingTextColor(cds.accent)
                } else {
                    setTrailingText(hexOf(scheme.color))
                    setTrailingTextColor(cds.textMuted)
                }
                setLeadingIconSize(widthDp = 20, heightDp = 20)
                setLeadingIconDrawable(
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(scheme.color)
                        setStroke(CdsUi.dp(context, 1), cds.borderStrong)
                    }
                )
            }
        }
    }

    private fun onBaseSelected(key: String) {
        XLog.i("ThemeActivity", "base -> $key")
        KVUtils.putString("THEME_BASE", key)
        // The base moves the whole surface ladder (canvas, cards, hairlines,
        // muted ink) — repainting rows alone would leave the page chrome on
        // the previous base, so rebuild with fresh tokens.
        recreate()
    }

    private fun onAccentSelected(key: String) {
        XLog.i("ThemeActivity", "accent -> $key")
        KVUtils.putString("THEME_ACCENT", key)
        renderAccentRows()
    }

    private fun hexOf(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)
}
