// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import androidx.core.view.isVisible
import io.agents.nailongclaw.R
import io.agents.nailongclaw.ui.cds.CdsUi

/**
 * Menu group component - rounded card container with a title
 */
class MenuGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tvTitle: TextView
    private val cardContainer: MaterialCardView
    private val itemsContainer: LinearLayout

    /** When set (via [applyCds]), newly added items pick up CDS defaults. */
    private var cdsPalette: CdsUi.Palette? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_menu_group, this, true)
        tvTitle = findViewById(R.id.tvTitle)
        cardContainer = findViewById(R.id.cardContainer)
        itemsContainer = findViewById(R.id.itemsContainer)
    }

    /**
     * Restyle the group onto the Claude Design System: the `--cds-panel` card
     * (surface-2, hairline border, 10px radius, no elevation) and a
     * `.group-label`-styled title. Also arms the palette so items added later
     * inherit CDS defaults without every caller restating them.
     */
    fun applyCds(palette: CdsUi.Palette) {
        cdsPalette = palette
        cardContainer.setCardBackgroundColor(palette.surface2)
        cardContainer.radius = CdsUi.dp(context, 10f)
        cardContainer.strokeWidth = CdsUi.dp(context, 1)
        cardContainer.setStrokeColor(palette.border)
        cardContainer.cardElevation = 0f
        tvTitle.apply {
            typeface = CdsUi.fontSans()
            setTextColor(palette.textMuted)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.02f
            // group-label sits ~4px inside the card margin, not 32dp deep
            setPadding(
                CdsUi.dp(context, 4), paddingTop,
                CdsUi.dp(context, 4), paddingBottom
            )
        }
    }

    /**
     * Set title; hides the title when null or empty
     */
    fun setTitle(title: CharSequence?) {
        tvTitle.isVisible = !title.isNullOrEmpty()
        tvTitle.text = title
    }

    /**
     * Set title text color
     */
    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * Set card background color
     */
    fun setCardBackgroundColor(color: Int) {
        cardContainer.setCardBackgroundColor(color)
    }

    /**
     * Add a menu item
     */
    fun addMenuItem(item: MenuItem) {
        itemsContainer.addView(item)
    }

    /**
     * Add a menu item (with configuration)
     */
    fun addMenuItem(
        leadingIcon: Int,
        title: String,
        onClick: () -> Unit,
        trailingText: String? = null,
        trailingIcon: Int? = null,
        showTrailingIcon: Boolean = true,
        showDivider: Boolean = true
    ): MenuItem {
        val item = MenuItem(context).apply {
            setLeadingIcon(leadingIcon)
            setTitle(title)
            setOnClickListener { onClick() }
            trailingText?.let { setTrailingText(it) }
            trailingIcon?.let { setTrailingIcon(it) }
            setShowTrailingIcon(showTrailingIcon)
            setShowDivider(showDivider)
            cdsPalette?.let { cds ->
                setTitleColor(cds.textPrimary)
                setTrailingTextColor(cds.textSecondary)
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
                setDividerColor(cds.border)
                // CDS typography + density, mirroring the reference's
                // .settings-row: serif content title, sans meta, 13.5/12sp,
                // 11px vertical padding, 5%-ink press. Without this the pages
                // were colored like CDS but still typeset in the system font
                // at the stock 14dp/16dp — a different product from the sheet
                // that links to them.
                setTitleTypeface(CdsUi.fontSerif())
                setTrailingTextTypeface(CdsUi.fontSans())
                setTitleTextSizeSp(13.5f)
                setTrailingTextSizeSp(12f)
                setRowPaddingVerticalDp(11f)
                setLeadingIconSize(20, 20)
                setCdsPress(cds)
            }
        }
        itemsContainer.addView(item)
        return item
    }

    /**
     * Remove all menu items
     */
    fun clearMenuItems() {
        itemsContainer.removeAllViews()
    }

    /**
     * Get menu item count
     */
    fun getMenuItemCount(): Int = itemsContainer.childCount

    /**
     * Get menu item at given index
     */
    fun getMenuItemAt(index: Int): MenuItem? {
        return itemsContainer.getChildAt(index) as? MenuItem
    }
}
