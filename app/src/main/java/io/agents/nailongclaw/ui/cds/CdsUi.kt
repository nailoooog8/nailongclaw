// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.cds

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.agents.nailongclaw.R
import io.agents.nailongclaw.widget.CommonToolbar

/**
 * The Claude Design System tokens for native Views, mirroring
 * `mockup/claude-ui/cds.css` value for value (which itself copies
 * Caho1/open-claude-ui's `cds-theme.css`). The WebView surface renders the
 * same numbers through CSS; this file is the View-side half so the native
 * settings editors look like they belong to the same product as the sheet
 * that launches them.
 *
 * ## Resolution: KVUtils is the single source of truth
 *
 * The palette is resolved per call from the three theme axes the sidebar
 * popover and the native theme page both write — mode (`THEME_MODE`), base
 * (`THEME_BASE`), accent (`THEME_ACCENT`). Before this, the palette was two
 * hardcoded clay tables and the pages never read the axes: picking 鼠尾草绿
 * in the theme popover turned the WebView green while every native settings
 * page stayed terracotta — the exact "surface jumps styles" bug the CDS
 * migration was supposed to kill, one layer down.
 *
 * Defaults reproduce the old hardcoded palettes exactly: base `clay`,
 * accent `clay`, and mode falling back to the system night mode (the same
 * signal the WebView's live mode falls back to when nothing is stored).
 *
 * Every constant below cites the CSS custom property it mirrors. Light values
 * are the `[data-base="…"]` defaults, dark values the
 * `[data-mode="dark"][data-base="…"]` overrides on the same base; accents are
 * mode-orthogonal (`[data-theme]` blocks apply to both).
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

    /* ------------------------------------------------------------- 强调色
       cds.css 的 [data-theme] 块 —— 与深浅正交，七套同值。
       subtle 是浅色底的专属浅色 wash；暗色下的 subtle 由 DARK 分支用
       accent 15% 混色重建（镜像 cds.css:305 的 color-mix）。 */
    private class AccentScheme(
        val key: String,
        val accent: Int,
        val accentHover: Int,
        val accentSubtle: Int,
    )

    private val ACCENT_SCHEMES = listOf(
        AccentScheme("clay", 0xFFD97757.toInt(), 0xFFCC5A36.toInt(), 0xFFFBF3EF.toInt()),
        AccentScheme("clay-bright", 0xFFE08A68.toInt(), 0xFFEAA07F.toInt(), 0xFFFDF1EB.toInt()),
        AccentScheme("sage", 0xFF6F9068.toInt(), 0xFF7EA076.toInt(), 0xFFEEF3EA.toInt()),
        AccentScheme("indigo", 0xFF7B93C4.toInt(), 0xFF8DA3CE.toInt(), 0xFFEDF0F6.toInt()),
        AccentScheme("ochre", 0xFFC47068.toInt(), 0xFFCF857C.toInt(), 0xFFF8ECEB.toInt()),
        AccentScheme("wisteria", 0xFFA084B8.toInt(), 0xFFAE95C4.toInt(), 0xFFF2EDF6.toInt()),
        AccentScheme("teal", 0xFF5C918E.toInt(), 0xFF6B9F9C.toInt(), 0xFFEAF2F1.toInt()),
    )

    /* ------------------------------------------------------------- 底色
       cds.css 的 [data-base]（浅色三阶，surface-3 = surface-2）与
       [data-mode="dark"][data-base]（深色四阶）逐值镜像。
       ink 是该底色 hsla(h,s%,10%) 的 RGB（暖黑，边框用它而不是中性黑 ——
       中性黑压暖底会被吃掉）；alpha2/alpha3 是边框/强边框的 alpha 字节
       （各底色不同：沙/灰褐/藕荷/薄荷 .15/.26，焦糖 .16/.28）。
       textMuted 两套都可能被底色覆盖（浅色焦糖 #5e564d、薄荷 #59655d；
       深色薄荷 #8a938c，其余深色 #8a8a8a）。 */
    private class BaseScheme(
        val key: String,
        val lightSurfaces: List<Int>,   // surface-0..2；surface-3 取 surface-2
        val darkSurfaces: List<Int>,    // surface-0..3
        val inkLight: Int,
        val alpha2Light: Int,
        val alpha3Light: Int,
        val textMutedLight: Int,
        val textMutedDark: Int,
    )

    private fun baseScheme(
        key: String,
        light: List<Int>,
        dark: List<Int>,
        inkLight: Int,
        alpha2Light: Int,
        alpha3Light: Int,
        textMutedLight: Int = 0xFF6B625C.toInt(),
        textMutedDark: Int = 0xFF8A8A8A.toInt(),
    ) = BaseScheme(key, light, dark, inkLight, alpha2Light, alpha3Light, textMutedLight, textMutedDark)

    private val BASE_SCHEMES = listOf(
        baseScheme(
            "clay",
            light = listOf(0xFFF2E9E1.toInt(), 0xFFFAF2EB.toInt(), 0xFFFDF8F4.toInt()),
            dark = listOf(0xFF0B0B0B.toInt(), 0xFF151515.toInt(), 0xFF1A1A19.toInt(), 0xFF20201F.toInt()),
            inkLight = 0xFF1D1916.toInt(),
            alpha2Light = 0x24,
            alpha3Light = 0x3D,
        ),
        baseScheme(
            "porcelain",
            light = listOf(0xFFECEBE7.toInt(), 0xFFF7F6F3.toInt(), 0xFFFCFBF9.toInt()),
            dark = listOf(0xFF101010.toInt(), 0xFF1A1A1A.toInt(), 0xFF202020.toInt(), 0xFF262626.toInt()),
            inkLight = 0xFF1B1A18.toInt(),
            alpha2Light = 0x24,
            alpha3Light = 0x3D,
        ),
        baseScheme(
            "sand",
            light = listOf(0xFFE8DFD0.toInt(), 0xFFF3EBDD.toInt(), 0xFFFAF4EA.toInt()),
            dark = listOf(0xFF100E0B.toInt(), 0xFF1A1713.toInt(), 0xFF201C17.toInt(), 0xFF26221C.toInt()),
            inkLight = 0xFF1F1A14.toInt(),
            alpha2Light = 0x26,
            alpha3Light = 0x42,
        ),
        baseScheme(
            "caramel",
            light = listOf(0xFFE6D5C0.toInt(), 0xFFF1E4D2.toInt(), 0xFFF9F0E5.toInt()),
            dark = listOf(0xFF120D08.toInt(), 0xFF1C1611.toInt(), 0xFF221B15.toInt(), 0xFF29211A.toInt()),
            inkLight = 0xFF211912.toInt(),
            alpha2Light = 0x29,
            alpha3Light = 0x47,
            textMutedLight = 0xFF5E564D.toInt(),
        ),
        baseScheme(
            "taupe",
            light = listOf(0xFFE4E1DA.toInt(), 0xFFEEECE6.toInt(), 0xFFF7F5F0.toInt()),
            dark = listOf(0xFF0F0F0F.toInt(), 0xFF191919.toInt(), 0xFF1F1F1F.toInt(), 0xFF252525.toInt()),
            inkLight = 0xFF1C1A17.toInt(),
            alpha2Light = 0x26,
            alpha3Light = 0x42,
        ),
        baseScheme(
            "rose",
            light = listOf(0xFFEAE0DD.toInt(), 0xFFF4ECEA.toInt(), 0xFFFBF5F3.toInt()),
            dark = listOf(0xFF100C0D.toInt(), 0xFF1A1517.toInt(), 0xFF201B1D.toInt(), 0xFF262023.toInt()),
            inkLight = 0xFF1D1716.toInt(),
            alpha2Light = 0x26,
            alpha3Light = 0x42,
        ),
        baseScheme(
            "mint",
            light = listOf(0xFFDFEAE3.toInt(), 0xFFEEF4F0.toInt(), 0xFFF7FAF8.toInt()),
            dark = listOf(0xFF0A0F0D.toInt(), 0xFF141A17.toInt(), 0xFF1A201D.toInt(), 0xFF202623.toInt()),
            inkLight = 0xFF161D1A.toInt(),
            alpha2Light = 0x26,
            alpha3Light = 0x42,
            textMutedLight = 0xFF59655D.toInt(),
            textMutedDark = 0xFF8A938C.toInt(),
        ),
    )

    /** Resolves the palette from the stored theme axes — the same KVUtils
     *  keys the WebView reads through the bridge, so a pick in either surface
     *  shows up in the other. Mode falls back to the system night mode when
     *  nothing is stored yet; unknown keys fall back to clay (the live
     *  surface's default base/accent). */
    fun palette(context: Context): Palette {
        val storedMode = io.agents.nailongclaw.utils.KVUtils.getString("THEME_MODE", "")
        val nightMask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val systemNight = (context.resources.configuration.uiMode and nightMask) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = when (storedMode) {
            "dark" -> true
            "light" -> false
            else -> systemNight
        }
        val baseKey = io.agents.nailongclaw.utils.KVUtils.getString("THEME_BASE", "clay")
        val accentKey = io.agents.nailongclaw.utils.KVUtils.getString("THEME_ACCENT", "clay")
        val base = BASE_SCHEMES.firstOrNull { it.key == baseKey } ?: BASE_SCHEMES.first()
        val accent = ACCENT_SCHEMES.firstOrNull { it.key == accentKey } ?: ACCENT_SCHEMES.first()

        val surfaces = if (dark) base.darkSurfaces else base.lightSurfaces
        // Light: border/ghost are the base's warm ink at its own alphas.
        // Dark: white alphas, global across bases (cds.css:269-271).
        val border = if (dark) {
            (0x17 shl 24) or 0xFFFFFF
        } else {
            (base.alpha2Light shl 24) or base.inkLight
        }
        val borderStrong = if (dark) {
            (0x2E shl 24) or 0xFFFFFF
        } else {
            (base.alpha3Light shl 24) or base.inkLight
        }
        val ghostInk = if (dark) 0xFFFFFF else base.inkLight
        return Palette(
            isDark = dark,
            surface0 = surfaces[0],
            surface1 = surfaces[1],
            surface2 = surfaces[2],
            surface3 = if (dark) surfaces[3] else surfaces[2],
            textPrimary = if (dark) 0xFFF5F5F5.toInt() else 0xFF151515.toInt(),
            textSecondary = if (dark) 0xFFA3A3A3.toInt() else 0xFF525252.toInt(),
            textMuted = if (dark) base.textMutedDark else base.textMutedLight,
            accent = accent.accent,
            accentHover = accent.accentHover,
            // Dark washes the accent at 15% instead of the light hex —
            // cds.css:305's color-mix(accent 15%, transparent).
            accentSubtle = if (dark) {
                (0x26 shl 24) or (accent.accent and 0xFFFFFF)
            } else {
                accent.accentSubtle
            },
            onAccent = 0xFF151515.toInt(), // --theme-accent-on（实测 ≥4.5）
            border = border,
            borderStrong = borderStrong,
            ghostHover = (0x0D shl 24) or ghostInk,   // --cds-alpha-1
            fillSecondary = border,                    // --cds-fill-secondary = alpha-2
            danger = if (dark) 0xFFF87171.toInt() else 0xFFEF4444.toInt(),
        )
    }

    /**
     * Fingerprint of the three theme axes. An activity that cached a palette
     * compares this in onResume: a change made in the theme page (which does
     * not recreate the pages underneath it) means the cached tokens are stale.
     */
    fun fingerprint(context: Context): String =
        listOf(
            io.agents.nailongclaw.utils.KVUtils.getString("THEME_MODE", ""),
            io.agents.nailongclaw.utils.KVUtils.getString("THEME_BASE", "clay"),
            io.agents.nailongclaw.utils.KVUtils.getString("THEME_ACCENT", "clay"),
        ).joinToString("|")

    fun dp(context: android.content.Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    fun dp(context: android.content.Context, v: Float): Float =
        v * context.resources.displayMetrics.density

    /* ------------------------------------------------------------------ 字体
       镜像 cds.css 的两条字体栈，让原生页面和 WebView 表面看起来是同一种
       产品：

         serif → --font-anthropic-serif：Noto Serif SC / Source Han Serif SC /
                 SimSun / Songti SC，随后是 ui-serif/Georgia。内容（行标题、
                 正文、对话框标题）用衬线 —— 参考项目的判断：纯无衬线 + 处处
                 描边正是「AI 生成 UI」的典型特征。
         sans  → --font-sans-fallbacks：system-ui/Segoe/Roboto，CJK 回退
                 PingFang/Hiragino。界面 chrome（分组标签、次要说明、等宽
                 元数据、按钮）用无衬线。

       Android 上 `Typeface.create("serif", NORMAL)` 在绝大多数设备解析为
       Noto Serif，与 CSS 栈的第一个可达字体一致；SANS_SERIF 对应 Roboto /
       Noto Sans。设备连这两个都没有时（极端精简 ROM）框架回退到默认字体，
       可接受 —— 颜色与层级仍然成立。 */
    fun fontSerif(): Typeface =
        Typeface.create("serif", Typeface.NORMAL)

    fun fontSerifBold(): Typeface =
        Typeface.create("serif", Typeface.BOLD)

    fun fontSans(): Typeface =
        Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    fun fontMono(): Typeface = Typeface.MONOSPACE

    /**
     * CDS typography for a content TextView (row titles, body copy): serif,
     * primary ink. Matches `.turn-assistant .body` / `.settings-row`.
     */
    fun styleContentText(tv: TextView, p: Palette, sizeSp: Float = 13.5f, bold: Boolean = false) {
        tv.typeface = if (bold) fontSerifBold() else fontSerif()
        tv.setTextColor(p.textPrimary)
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    /**
     * CDS typography for chrome text (group labels, hints, meta): sans,
     * muted. Matches `.group-label` / `.hint` / `.swatch-hex`.
     */
    fun styleChromeText(tv: TextView, p: Palette, sizeSp: Float = 11f) {
        tv.typeface = fontSans()
        tv.setTextColor(p.textMuted)
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

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
        tv.typeface = fontSans()
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
