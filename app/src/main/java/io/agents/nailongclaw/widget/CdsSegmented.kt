// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.widget

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import io.agents.nailongclaw.ui.cds.CdsUi

/**
 * The Claude Design System segmented control — a rounded track with a sliding
 * thumb, ported from the reference project's `SegmentedControl.tsx`.
 *
 * Used for every "pick one of a few" decision that used to be a dead pill or
 * a spinner: the composer's effort gear (低/中/高/最大), the sensitive-
 * operation policy (询问/自动允许), and the theme's light/dark axis. A radio
 * group was the old native answer and reads like a form; this reads like the
 * control it is.
 *
 * The track is `--cds-segmented-track` (alpha ink), the selected thumb is
 * `--cds-segmented-thumb` (a surface step, not the accent — the reference
 * keeps the thumb neutral so accent stays meaningful), selected label at
 * 600 weight. Touch targets are 40dp tall, above the 48dp guideline when the
 * row padding is counted.
 */
class CdsSegmented @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private data class Option(val key: String, val label: String)

    private val track = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, CdsUi.dp(context, 40))
    }

    private var options: List<Option> = emptyList()
    private var selectedKey: String? = null
    private var onSelect: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        addView(track)
    }

    /**
     * Install the options.
     *
     * @param options `key` to `label` pairs; keys are what [onSelect] reports
     *   and what callers persist, labels are what the user reads.
     * @param selectedKey the initially selected key (null = nothing selected)
     */
    fun setOptions(
        options: List<Pair<String, String>>,
        selectedKey: String?,
        onSelect: (String) -> Unit,
    ) {
        this.options = options.map { Option(it.first, it.second) }
        this.selectedKey = selectedKey
        this.onSelect = onSelect
        render()
    }

    /** Re-point the selection without firing [onSelect] (state sync from outside). */
    fun setSelectedKey(key: String?) {
        if (key == selectedKey) return
        selectedKey = key
        render()
    }

    fun selectedKey(): String? = selectedKey

    private fun render() {
        val p = CdsUi.palette(context)
        track.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(p.fillSecondary)
            cornerRadius = CdsUi.dp(context, 20f)
        }
        val pad = CdsUi.dp(context, 3)
        track.setPadding(pad, pad, pad, pad)
        track.removeAllViews()

        options.forEach { option ->
            val isSelected = option.key == selectedKey
            val label = TextView(context).apply {
                text = option.label
                gravity = Gravity.CENTER
                typeface = CdsUi.fontSans()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(if (isSelected) p.textPrimary else p.textSecondary)
                if (isSelected) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                if (isSelected) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(p.surface2)
                        cornerRadius = CdsUi.dp(context, 17f)
                    }
                }
                setOnClickListener {
                    if (option.key == selectedKey) return@setOnClickListener
                    selectedKey = option.key
                    render()
                    onSelect?.invoke(option.key)
                }
            }
            track.addView(label)
        }
    }
}
