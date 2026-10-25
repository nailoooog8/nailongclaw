// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import io.agents.pokeclaw.R
import io.agents.pokeclaw.ui.cds.CdsUi

/**
 * Menu item component - clickable row with leading icon, title, and trailing text/arrow
 */
class MenuItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val ivLeading: ImageView
    private val tvTitle: TextView
    private val viewRedDot: View
    private val tvTrailing: TextView
    private val ivTrailing: ImageView
    private val divider: View

    /** The clickable row container (first child of the merged layout). */
    private val rowContent: LinearLayout

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.widget_menu_item, this, true)

        rowContent = getChildAt(0) as LinearLayout
        ivLeading = findViewById(R.id.ivLeading)
        tvTitle = findViewById(R.id.tvTitle)
        viewRedDot = findViewById(R.id.viewRedDot)
        tvTrailing = findViewById(R.id.tvTrailing)
        ivTrailing = findViewById(R.id.ivTrailing)
        divider = findViewById(R.id.divider)

        // Show trailing arrow by default
        setShowTrailingIcon(true)
    }

    /**
     * Set leading icon
     */
    fun setLeadingIcon(@DrawableRes iconRes: Int) {
        ivLeading.setImageResource(iconRes)
    }

    /**
     * Set the leading icon to an arbitrary drawable — the theme page's base /
     * accent swatches are generated GradientDrawables, not resources. Clears
     * the color filter so a swatch's own colors survive (setLeadingIconColor
     * would otherwise repaint it).
     */
    fun setLeadingIconDrawable(drawable: android.graphics.drawable.Drawable?) {
        ivLeading.colorFilter = null
        ivLeading.setImageDrawable(drawable)
    }

    /**
     * Set the leading icon box size (dp). Swatches want a wider, shorter box
     * than the 24dp icon default so the three-step base ladder reads as a
     * strip rather than a square.
     */
    fun setLeadingIconSize(widthDp: Int, heightDp: Int) {
        val lp = ivLeading.layoutParams
        lp.width = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, widthDp.toFloat(), resources.displayMetrics
        ).toInt()
        lp.height = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, heightDp.toFloat(), resources.displayMetrics
        ).toInt()
        ivLeading.layoutParams = lp
    }

    /**
     * Set the title typeface — the CDS typography pass uses this to give row
     * titles the serif content face while chrome stays sans.
     */
    fun setTitleTypeface(typeface: android.graphics.Typeface) {
        tvTitle.typeface = typeface
    }

    /**
     * Set the trailing text typeface — CDS keeps meta (statuses, counts, hex
     * values) in the sans chrome face so it never competes with the serif
     * title for attention.
     */
    fun setTrailingTextTypeface(typeface: android.graphics.Typeface) {
        tvTrailing.typeface = typeface
    }

    /** Title size in sp — the CDS pass sets the reference's 13.5sp row label. */
    fun setTitleTextSizeSp(sizeSp: Float) {
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    /** Trailing size in sp — the CDS pass sets the reference's 12sp meta. */
    fun setTrailingTextSizeSp(sizeSp: Float) {
        tvTrailing.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    /**
     * Row vertical padding in dp — the CDS pass tightens the stock 16dp to the
     * reference settings-row density (11px ≈ 11dp here).
     */
    fun setRowPaddingVerticalDp(dp: Float) {
        val px = CdsUi.dp(context, dp).toInt()
        rowContent.setPadding(rowContent.paddingLeft, px, rowContent.paddingRight, px)
    }

    /**
     * CDS press feedback: a 5%-ink ripple over the row instead of the theme's
     * brand-colored `selectableItemBackground`. The stock ripple inherits
     * colorControlHighlight from the Material theme, which on the CDS pages
     * is a second, louder color than anything in the palette.
     */
    fun setCdsPress(p: CdsUi.Palette) {
        val ripple = RippleDrawable(
            ColorStateList.valueOf(p.ghostHover),
            null,
            ColorDrawable(p.fillSecondary)
        )
        rowContent.background = ripple
    }

    /**
     * Set leading icon color
     */
    fun setLeadingIconColor(color: Int) {
        ivLeading.setColorFilter(color)
    }

    /**
     * Set title
     */
    fun setTitle(title: CharSequence) {
        tvTitle.text = title
    }

    /**
     * Set title text color
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * Set trailing text
     */
    fun setTrailingText(text: CharSequence?) {
        tvTrailing.isVisible = !text.isNullOrEmpty()
        tvTrailing.text = text
    }

    /**
     * Set trailing text color
     */
    fun setTrailingTextColor(color: Int) {
        tvTrailing.setTextColor(color)
    }

    /**
     * Set trailing icon
     */
    fun setTrailingIcon(@DrawableRes iconRes: Int) {
        ivTrailing.setImageResource(iconRes)
    }

    /**
     * Set trailing icon color
     */
    fun setTrailingIconColor(color: Int) {
        ivTrailing.setColorFilter(color)
    }

    /**
     * Set whether to show trailing icon
     */
    fun setShowTrailingIcon(show: Boolean) {
        ivTrailing.visibility = if (show) View.VISIBLE else View.GONE
        val lp = tvTrailing.layoutParams as MarginLayoutParams
        lp.marginEnd = if (show) 0 else TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_PT, 8f, resources.displayMetrics
        ).toInt()
        tvTrailing.layoutParams = lp
    }

    /**
     * Set whether to show divider
     */
    fun setShowDivider(show: Boolean) {
        divider.isVisible = show
    }

    /**
     * Set divider color (used by the CDS restyle to switch to the palette's hairline)
     */
    fun setDividerColor(color: Int) {
        divider.background = android.graphics.drawable.ColorDrawable(color)
    }

    /**
     * Get trailing text
     */
    fun getTrailingText(): CharSequence? {
        return tvTrailing.text
    }

    /**
     * Set whether to show red dot
     */
    fun setShowRedDot(show: Boolean) {
        viewRedDot.visibility = if (show) View.VISIBLE else View.GONE
    }

    /**
     * Get title text
     */
    fun getTitle(): CharSequence? {
        return tvTitle.text
    }
}
