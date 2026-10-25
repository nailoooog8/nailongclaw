// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import io.agents.pokeclaw.R

/**
 * Lightweight loading dialog matching the project's rounded card style
 *
 * No text (spinner only):
 * ```
 * val loading = LoadingDialog.show(context)
 * loading.dismiss()
 * ```
 *
 * With message text:
 * ```
 * val loading = LoadingDialog.show(context, "Loading…")
 * loading.dismiss()
 * ```
 */
class LoadingDialog private constructor(context: Context) : Dialog(context, R.style.DialogStyle) {

    private var message: String? = null
    private var isDismissible: Boolean = false

    companion object {

        @JvmStatic
        fun show(
            context: Context,
            message: String? = null,
            cancelable: Boolean = false
        ): LoadingDialog {
            return LoadingDialog(context).apply {
                this.message = message
                this.isDismissible = cancelable
                show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_loading)

        val p = io.agents.pokeclaw.ui.cds.CdsUi.palette(context)

        setCancelable(isDismissible)
        setCanceledOnTouchOutside(isDismissible)

        // Same CDS frame as QRCodeDialog / CdsDialog: the window is the panel,
        // the layout's own ember card background is dropped under it.
        window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(io.agents.pokeclaw.ui.cds.CdsUi.panel(context, p))
            setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            // Keep background from dimming to keep the loading dialog lightweight
            setDimAmount(0.3f)
        }
        (findViewById<android.widget.FrameLayout>(android.R.id.content))?.getChildAt(0)?.background = null

        findViewById<android.widget.ProgressBar>(R.id.progressBar)?.apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(p.accent)
        }

        val tvMessage = findViewById<TextView>(R.id.tvMessage)
        tvMessage.typeface = io.agents.pokeclaw.ui.cds.CdsUi.fontSans()
        tvMessage.setTextColor(p.textSecondary)
        tvMessage.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
        if (!message.isNullOrEmpty()) {
            tvMessage.text = message
            tvMessage.visibility = View.VISIBLE
        }
    }

    /**
     * Update the message text (can be called after the dialog is already shown)
     */
    fun setMessage(text: String?) {
        this.message = text
        val tvMessage = findViewById<TextView>(R.id.tvMessage) ?: return
        if (text.isNullOrEmpty()) {
            tvMessage.visibility = View.GONE
        } else {
            tvMessage.text = text
            tvMessage.visibility = View.VISIBLE
        }
    }
}
