// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.widget

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import io.agents.nailongclaw.R
import android.widget.FrameLayout
import com.bumptech.glide.Glide
import io.agents.nailongclaw.widget.KButton

class QRCodeDialog private constructor(context: Context) : Dialog(context, R.style.DialogStyle) {

    private var title: String = ""
    private var subtitle: String? = null
    private var qrBitmap: Bitmap? = null
    private var qrImageUrl: String? = null
    private var onCloseListener: (() -> Unit)? = null

    companion object {
        /**
         * Show QR code dialog
         *
         * @param context context
         * @param title title (required)
         * @param subtitle subtitle (optional)
         * @param qrBitmap QR code bitmap
         * @param onClose close callback (optional)
         * @return QRCodeDialog instance
         */
        @JvmStatic
        @JvmOverloads
        fun show(
            context: Context,
            title: String,
            subtitle: String? = null,
            qrBitmap: Bitmap,
            onClose: (() -> Unit)? = null
        ): QRCodeDialog {
            return QRCodeDialog(context).apply {
                this.title = title
                this.subtitle = subtitle
                this.qrBitmap = qrBitmap
                this.onCloseListener = onClose
                show()
            }
        }

        /**
         * Show QR code dialog (load image via URL)
         *
         * @param context context
         * @param title title (required)
         * @param subtitle subtitle (optional)
         * @param qrImageUrl QR code image URL
         * @param onClose close callback (optional)
         * @return QRCodeDialog instance
         */
        @JvmStatic
        @JvmOverloads
        fun showWithUrl(
            context: Context,
            title: String,
            subtitle: String? = null,
            qrImageUrl: String,
            onClose: (() -> Unit)? = null
        ): QRCodeDialog {
            return QRCodeDialog(context).apply {
                this.title = title
                this.subtitle = subtitle
                this.qrImageUrl = qrImageUrl
                this.onCloseListener = onClose
                show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_qr_code)

        val p = io.agents.nailongclaw.ui.cds.CdsUi.palette(context)

        // Configure dialog style — the window itself becomes the CDS panel
        // (surface-2 + hairline + 10px radius, no elevation), the same frame
        // CdsDialog gives the settings editors. The layout's own rounded
        // ember background is dropped so the two don't double up.
        window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawable(io.agents.nailongclaw.ui.cds.CdsUi.panel(context, p))
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        (findViewById<FrameLayout>(android.R.id.content))?.getChildAt(0)?.background = null

        // Callback on dismiss (back key, close button tap, code dismiss, etc.)
        setOnDismissListener { onCloseListener?.invoke() }

        // Initialize views
        initViews()
    }

    private fun initViews() {
        val p = io.agents.nailongclaw.ui.cds.CdsUi.palette(context)

        // Title
        findViewById<TextView>(R.id.tvTitle).apply {
            text = this@QRCodeDialog.title
            typeface = io.agents.nailongclaw.ui.cds.CdsUi.fontSerifBold()
            setTextColor(p.textPrimary)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        }

        // Subtitle
        findViewById<TextView>(R.id.tvSubtitle).apply {
            if (!this@QRCodeDialog.subtitle.isNullOrEmpty()) {
                text = this@QRCodeDialog.subtitle
                visibility = android.view.View.VISIBLE
            }
            typeface = io.agents.nailongclaw.ui.cds.CdsUi.fontSans()
            setTextColor(p.textSecondary)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        // QR code image — the bitmap carries its own white plate, which is
        // what makes it scannable on both the light and the dark panel.
        findViewById<ImageView>(R.id.ivQrCode).apply {
            when {
                this@QRCodeDialog.qrBitmap != null -> {
                    setImageBitmap(this@QRCodeDialog.qrBitmap)
                }
                !this@QRCodeDialog.qrImageUrl.isNullOrEmpty() -> {
                    Glide.with(context)
                        .load(this@QRCodeDialog.qrImageUrl)
                        .into(this)
                }
            }
        }

        // Status overlay (扫码结果 / 过期) sits on the panel surface, not the
        // ember canvas color.
        findViewById<FrameLayout>(R.id.layoutStatusOverlay)?.apply {
            setBackgroundColor(p.surface2)
        }
        findViewById<TextView>(R.id.tvStatusText)?.apply {
            typeface = io.agents.nailongclaw.ui.cds.CdsUi.fontSans()
            setTextColor(p.textPrimary)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        // Close button
        findViewById<KButton>(R.id.btnClose).apply {
            setBgColor(p.accent)
            setTextColor(p.onAccent)
            typeface = io.agents.nailongclaw.ui.cds.CdsUi.fontSans()
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener { dismiss() }
        }
    }

    /**
     * Update QR code bitmap
     */
    fun updateQrBitmap(bitmap: Bitmap) {
        this.qrBitmap = bitmap
        this.qrImageUrl = null
        findViewById<ImageView>(R.id.ivQrCode)?.setImageBitmap(bitmap)
    }

    /**
     * Update QR code image via URL
     */
    fun updateQrImageUrl(url: String) {
        this.qrBitmap = null
        this.qrImageUrl = url
        findViewById<ImageView>(R.id.ivQrCode)?.let { imageView ->
            Glide.with(context)
                .load(url)
                .into(imageView)
        }
    }

    /**
     * Set the close callback
     */
    fun setOnCloseListener(listener: () -> Unit) {
        this.onCloseListener = listener
    }

    /**
     * Show the status overlay (for displaying states like "Expired", "Scanned", etc.)
     *
     * @param text status text to display
     */
    fun showStatusOverlay(text: String) {
        findViewById<FrameLayout>(R.id.layoutStatusOverlay)?.apply {
            visibility = android.view.View.VISIBLE
            findViewById<TextView>(R.id.tvStatusText)?.text = text
        }
    }

    /**
     * Hide the status overlay
     */
    fun hideStatusOverlay() {
        findViewById<FrameLayout>(R.id.layoutStatusOverlay)?.visibility = android.view.View.GONE
    }

    /**
     * Update the status text (overlay stays visible)
     *
     * @param text new status text
     */
    fun updateStatusText(text: String) {
        findViewById<TextView>(R.id.tvStatusText)?.text = text
    }
}
