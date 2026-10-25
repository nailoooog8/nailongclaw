// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.widget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.agents.pokeclaw.ui.cds.CdsUi

/**
 * The Claude Design System dialog: the sheet's `--cds-panel` treatment applied
 * to a system [AlertDialog].
 *
 * Why this exists instead of a bare `AlertDialog.Builder`: the system dialog is
 * light-themed on every build, so every editor in the app used to ship a
 * comment saying "use fixed dark text, not theme colors" — the fields were
 * legible but the frame (white box, default type, stock grey buttons) looked
 * like a different product from the CDS page that opened it. Styling the real
 * dialog keeps the modal behaviour (BACK dismisses, window token, scroll) and
 * fixes the frame.
 *
 * What it styles, and the token each mirrors:
 *  - window background → `panel()`: surface-2 fill, `--cds-panel-radius` 10px,
 *    1px `--cds-border` hairline (no elevation — the flat sheet look)
 *  - title → `--font-anthropic-serif`, `--cds-text-primary`
 *  - confirm button → `--theme-accent` on `--theme-accent-on`; cancel → muted
 *  - content → whatever the caller built; use [label] / [field] to get CDS
 *    typography and the composer's input treatment
 *
 * Validation belongs in the confirm click, not the builder callback: the
 * builder callback fires after dismissal, so a rejected value there throws
 * away everything the user typed. Pass `dismissOnConfirm = false` and call
 * [CdsDialogHandle.dismiss] yourself once the value is good.
 */
object CdsDialog {

    /** Handle returned by [show]; only what callers need after the fact. */
    interface Handle {
        fun dismiss()
        fun confirmButton(): android.widget.Button?
        fun cancelButton(): android.widget.Button?
    }

    private class AlertDialogHandle(private val dialog: AlertDialog) : Handle {
        override fun dismiss() = dialog.dismiss()
        override fun confirmButton(): android.widget.Button? =
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        override fun cancelButton(): android.widget.Button? =
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
    }
    /**
     * Show a CDS-styled modal.
     *
     * @param content the dialog body (usually a vertical LinearLayout built
     *   with [column] / [label] / [field])
     * @param confirmText label for the positive button; null hides it
     * @param cancelText label for the negative button; null hides it
     * @param neutralText label for a third button between the two; null hides
     *   it. Destructive-but-not-only choices (MCP 确认的「始终允许」, 记忆抑制
     *   的「仅解除抑制」) need three exits, and a confirm/cancel pair would
     *   force one of them into a dismissal that means something else.
     * @param danger paints the confirm button `--cds-fill-danger` instead of
     *   the accent — for actions that destroy state (解绑 / 全部清除).
     * @param dismissOnConfirm when true (default) a confirm tap dismisses
     *   immediately. Pass false to validate first and dismiss via the handle.
     * @param onConfirm runs on confirm; receives the handle when
     *   [dismissOnConfirm] is false, otherwise nothing to call.
     */
    fun show(
        context: Context,
        title: String,
        content: android.view.View,
        confirmText: String? = null,
        cancelText: String? = null,
        neutralText: String? = null,
        danger: Boolean = false,
        dismissOnConfirm: Boolean = true,
        onConfirm: ((Handle) -> Unit)? = null,
        onNeutral: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
    ): Handle {
        val p = CdsUi.palette(context)
        val builder = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(content)
            .setOnCancelListener { onCancel?.invoke() }
        if (confirmText != null) builder.setPositiveButton(confirmText, null)
        if (cancelText != null) builder.setNegativeButton(cancelText, null)
        if (neutralText != null) builder.setNeutralButton(neutralText, null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(CdsUi.panel(context, p))
            // alertTitle is an AppCompat-internal id; it exists on the
            // androidx dialog the same way it does on the platform one.
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
                typeface = CdsUi.fontSerifBold()
                setTextColor(p.textPrimary)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            (dialog.findViewById<android.view.View>(android.R.id.message) as? TextView)?.apply {
                typeface = CdsUi.fontSerif()
                setTextColor(p.textSecondary)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(if (danger) p.danger else p.accent)
                typeface = Typeface.create(CdsUi.fontSans(), Typeface.BOLD)
                // Always wired here so validation can veto the dismissal;
                // the builder callback stays null on purpose (it would fire
                // after the dialog is already gone).
                setOnClickListener {
                    onConfirm?.invoke(AlertDialogHandle(dialog))
                    if (dismissOnConfirm) dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(p.textMuted)
                typeface = CdsUi.fontSans()
                // The cancel button runs onCancel, not just a dismissal:
                // the MCP confirmation's 「本次拒绝」is an ANSWER, and a
                // silent close would leave the parked agent thread waiting
                // for the timeout. Callers without an onCancel just close.
                setOnClickListener {
                    onCancel?.invoke()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(p.textSecondary)
                typeface = CdsUi.fontSans()
                setOnClickListener { onNeutral?.invoke() }
            }
            // A dialog whose body holds a field opens with the keyboard up —
            // the old bottom-sheet InputDialog did this, and a confirm-style
            // dialog with no field must not summon the IME.
            if (containsField(content)) {
                dialog.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                )
            }
        }
        dialog.show()
        return AlertDialogHandle(dialog)
    }

    /** True when any descendant of [root] is an [EditText]. */
    private fun containsField(root: android.view.View): Boolean {
        if (root is EditText) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (containsField(root.getChildAt(i))) return true
            }
        }
        return false
    }

    /** A vertical dialog body with the sheet's gutter padding. */
    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = CdsUi.dp(context, 20)
        setPadding(pad, CdsUi.dp(context, 8), pad, CdsUi.dp(context, 4))
    }

    /** `.group-label`-style caption above a field. */
    fun label(context: Context, text: String, topGapDp: Int = 12): TextView =
        TextView(context).apply {
            this.text = text
            typeface = CdsUi.fontSans()
            setTextColor(CdsUi.palette(context).textMuted)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            letterSpacing = 0.02f
            setPadding(0, CdsUi.dp(context, topGapDp), 0, CdsUi.dp(context, 4))
        }

    /**
     * Serif body copy for a dialog that leads with prose instead of fields —
     * the confirmation prompts (解绑 / 启用外部自动化 / MCP 确认) read as
     * sentences, so they get the content face at secondary ink, same as the
     * page behind them.
     */
    fun message(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            typeface = CdsUi.fontSerif()
            setTextColor(CdsUi.palette(context).textSecondary)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, CdsUi.dp(context, 4), 0, CdsUi.dp(context, 4))
        }

    /**
     * A radio-style option row — the mockup's `.pop-item[aria-checked="true"]`:
     * the selected row carries the ghost-selected wash and a 6px accent dot,
     * everything else stays flat with an outlined dot. This is the shape the
     * budget picker and the suppression list use, so "pick one of N" stops
     * meaning a stock [android.widget.Spinner] dropped into the dialog.
     *
     * @param tag trailing meta (「15 轮」/「挡过 2 次」), muted and right-aligned
     * @return the row view; wire [selected] state yourself by rebuilding the
     *   list (these dialogs are short-lived, a diff would be ceremony)
     */
    fun optionRow(
        context: Context,
        label: String,
        tag: String? = null,
        selected: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): android.view.View {
        val p = CdsUi.palette(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = CdsUi.dp(context, 2) }
            setPadding(
                CdsUi.dp(context, 12),
                CdsUi.dp(context, 10),
                CdsUi.dp(context, 12),
                CdsUi.dp(context, 10)
            )
            // Persistent wash for the selected row + ripple press on top,
            // masked to the same 8px rounded rect (RippleDrawable's mask is
            // alpha-only, so a white rounded shape is enough).
            val content = GradientDrawable().apply {
                setColor(if (selected) p.ghostHover else 0x00000000)
                cornerRadius = CdsUi.dp(context, 8f)
            }
            val mask = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = CdsUi.dp(context, 8f)
            }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(p.fillSecondary),
                content,
                mask
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick?.invoke() }
        }

        row.addView(android.view.View(context).apply {
            val d = CdsUi.dp(context, 7)
            layoutParams = LinearLayout.LayoutParams(d, d).apply {
                marginEnd = CdsUi.dp(context, 10)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) {
                    setColor(p.accent)
                } else {
                    setStroke(CdsUi.dp(context, 1), p.borderStrong)
                    setColor(0x00000000)
                }
            }
        })

        row.addView(TextView(context).apply {
            text = label
            typeface = CdsUi.fontSans()
            setTextColor(p.textPrimary)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })

        if (tag != null) {
            row.addView(TextView(context).apply {
                text = tag
                typeface = CdsUi.fontSans()
                setTextColor(p.textMuted)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            })
        }
        return row
    }

    /** Composer-treatment input: surface-3 fill, hairline, 8px corners. */
    fun field(
        context: Context,
        hint: String,
        preset: String? = null,
        password: Boolean = false,
        multiLine: Boolean = false,
    ): EditText = EditText(context).apply {
        this.hint = hint
        setText(preset ?: "")
        val p = CdsUi.palette(context)
        background = CdsUi.input(context, p)
        setTextColor(p.textPrimary)
        setHintTextColor(p.textMuted)
        typeface = CdsUi.fontSerif()
        setPadding(
            CdsUi.dp(context, 12),
            CdsUi.dp(context, 10),
            CdsUi.dp(context, 12),
            CdsUi.dp(context, 10)
        )
        isSingleLine = !multiLine
        if (multiLine) minLines = 2
        if (password) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
    }

    /** Flips a [field] between masked and plain, for the eye toggle. */
    fun togglePasswordVisibility(field: EditText, currentlyVisible: Boolean): Boolean {
        val nowVisible = !currentlyVisible
        field.transformationMethod = if (nowVisible) {
            HideReturnsTransformationMethod.getInstance()
        } else {
            PasswordTransformationMethod.getInstance()
        }
        field.setSelection(field.text.length)
        return nowVisible
    }

    /** Vertical spacing helper so callers don't hand-roll empty views. */
    fun gap(context: Context, heightDp: Int): android.view.View = android.view.View(context)
        .apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                CdsUi.dp(context, heightDp)
            )
        }
}
