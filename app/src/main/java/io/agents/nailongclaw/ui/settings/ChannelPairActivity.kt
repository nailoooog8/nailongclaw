// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import io.agents.nailongclaw.R
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.channel.Channel
import io.agents.nailongclaw.channel.security.ChannelPairingListModel
import io.agents.nailongclaw.channel.security.ChannelSecurityGuard
import io.agents.nailongclaw.channel.security.PairingListModel
import io.agents.nailongclaw.channel.security.PairingRow
import io.agents.nailongclaw.ui.cds.CdsUi
import io.agents.nailongclaw.widget.CdsDialog
import io.agents.nailongclaw.widget.CommonToolbar
import io.agents.nailongclaw.widget.MenuGroup
import java.text.DateFormat
import java.util.Date

/**
 * 已配对的发送者 — the settings list for [ChannelSecurityGuard]'s pairing
 * whitelist (BACKLOG P1). Before this page the whitelist had no UI at all:
 * the user could neither see who was paired nor revoke one sender — the only
 * lever was wiping everything.
 *
 * Three groups, in the order the user asks the questions:
 *
 *  1. Paired — one row per `CHANNEL|senderId` entry with the channel and the
 *     recorded pairing date (metadata kept by [ChannelSecurityGuard] since the
 *     pairing-time change). Tap → revoke exactly that entry, behind a danger
 *     confirm: the same id on another channel stays paired, because
 *     authorisation is per channel (SECURITY_AUDIT_R2 F1).
 *  2. Pending re-bind — leftovers of the F1 upgrade migration
 *     ([ChannelSecurityGuard.purgeLegacyEntries]): senders paired before the
 *     whitelist became per-channel. They authorise nothing, so the rows are
 *     greyed and badged instead of looking alive; tapping deletes the record.
 *     A successful /bind resolves them on its own.
 *  3. Clear all — the pre-existing wipe ([ChannelSecurityGuard.clearWhitelist]),
 *     kept as the only bulk path, now behind a confirm that says what it wipes.
 *
 * Storage is touched only through [ChannelSecurityGuard] — this page never
 * reaches into any other guard's keys.
 */
class ChannelPairActivity : BaseActivity() {

    private lateinit var pairedGroup: MenuGroup
    private lateinit var pendingGroup: MenuGroup
    private lateinit var dangerGroup: MenuGroup
    private lateinit var tvEmptyState: TextView

    private val cds by lazy { CdsUi.palette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(cds.surface1)
        setContentView(R.layout.activity_channel_pair)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, getString(R.string.channel_pair_screen_title))
        toolbar.showBackButton(true) { finish() }

        pairedGroup = findViewById(R.id.pairedGroup)
        pendingGroup = findViewById(R.id.pendingGroup)
        dangerGroup = findViewById(R.id.dangerGroup)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        listOf(pairedGroup, pendingGroup, dangerGroup).forEach { it.applyCds(cds) }

        tvEmptyState.apply {
            CdsUi.styleChromeText(this, cds, 12f)
            text = getString(R.string.channel_pair_empty)
        }
        findViewById<TextView>(R.id.tvFooter).apply {
            CdsUi.styleChromeText(this, cds, 11f)
            text = getString(R.string.channel_pair_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    private fun renderAll() {
        // Surface F1 migration records even before any message arrives: the
        // purge is one-shot per process and the first verify() may never have
        // run, so without this the quarantined entries stay invisible.
        ChannelSecurityGuard.purgeLegacyEntries()

        val model = ChannelPairingListModel.build(
            whitelist = ChannelSecurityGuard.getWhitelist(),
            pairTimes = ChannelSecurityGuard.getPairTimes(),
            pendingIds = ChannelSecurityGuard.getPendingRebindIds(),
        )

        renderPaired(model)
        renderPending(model)
        renderDanger(model)

        tvEmptyState.isVisible = model.isEmpty
    }

    /** One row per active `CHANNEL|senderId` entry. */
    private fun renderPaired(model: PairingListModel) {
        pairedGroup.clearMenuItems()
        pairedGroup.isVisible = model.activeRows.isNotEmpty()
        if (model.activeRows.isEmpty()) return
        pairedGroup.setTitle(getString(R.string.channel_pair_group_paired, model.activeRows.size))

        model.activeRows.forEachIndexed { index, row ->
            pairedGroup.addMenuItem(
                leadingIcon = channelIcon(row.channel),
                title = row.senderId,
                onClick = { confirmRevoke(row) },
                showDivider = index != model.activeRows.lastIndex
            ).apply {
                setTitleColor(cds.textPrimary)
                setTrailingText(
                    getString(
                        R.string.channel_pair_row_channel_date,
                        row.channel.displayName,
                        pairedAtText(row.pairedAtMs)
                    )
                )
                setTrailingTextColor(cds.textSecondary)
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
            }
        }
    }

    /**
     * Pending re-bind rows, visually distinct from live pairings: greyed id
     * (`textMuted`, like the "coming soon" rows elsewhere) and a danger-tinted
     * badge as the trailing text — a row that authorises nothing must not
     * render like one that does.
     */
    private fun renderPending(model: PairingListModel) {
        pendingGroup.clearMenuItems()
        pendingGroup.isVisible = model.pendingRows.isNotEmpty()
        if (model.pendingRows.isEmpty()) return
        pendingGroup.setTitle(getString(R.string.channel_pair_group_pending, model.pendingRows.size))

        model.pendingRows.forEachIndexed { index, row ->
            pendingGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_help,
                title = row.senderId,
                onClick = { confirmForget(row) },
                showDivider = index != model.pendingRows.lastIndex
            ).apply {
                setTitleColor(cds.textMuted)
                setTrailingText(getString(R.string.channel_pair_pending_badge))
                setTrailingTextColor(cds.danger)
                setLeadingIconColor(cds.textMuted)
                setTrailingIconColor(cds.textMuted)
            }
        }
    }

    /** The bulk wipe, shown only while there is anything to wipe. */
    private fun renderDanger(model: PairingListModel) {
        dangerGroup.clearMenuItems()
        dangerGroup.isVisible = !model.isEmpty
        if (model.isEmpty) return
        dangerGroup.setTitle(null)
        dangerGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_delete,
            title = getString(R.string.channel_pair_clear_row),
            onClick = { confirmClearAll() },
            showDivider = false
        ).apply {
            setTitleColor(cds.danger)
            setLeadingIconColor(cds.danger)
            setTrailingIconColor(cds.textMuted)
        }
    }

    /** Revoke one active entry: only this guard's own storage moves. */
    private fun confirmRevoke(row: PairingRow.Active) {
        CdsDialog.show(
            context = this,
            title = getString(R.string.channel_pair_revoke_title),
            content = CdsDialog.message(
                this,
                getString(R.string.channel_pair_revoke_message, row.senderId, row.channel.displayName)
            ),
            confirmText = getString(R.string.channel_pair_revoke_action),
            cancelText = getString(R.string.common_cancel),
            danger = true,
            onConfirm = {
                ChannelSecurityGuard.removeSenderFromWhitelist(row.channel, row.senderId)
                Toast.makeText(this, R.string.channel_pair_revoke_done, Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    /** Delete one pending re-bind record. No permission changes — there are none. */
    private fun confirmForget(row: PairingRow.PendingRebind) {
        CdsDialog.show(
            context = this,
            title = getString(R.string.channel_pair_forget_title),
            content = CdsDialog.message(
                this,
                getString(R.string.channel_pair_forget_message, row.senderId)
            ),
            confirmText = getString(R.string.channel_pair_forget_action),
            cancelText = getString(R.string.common_cancel),
            danger = true,
            onConfirm = {
                ChannelSecurityGuard.removePendingRebindId(row.senderId)
                Toast.makeText(this, R.string.channel_pair_forget_done, Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    /** The existing clear-all path, unchanged — just confirmed first. */
    private fun confirmClearAll() {
        CdsDialog.show(
            context = this,
            title = getString(R.string.channel_pair_clear_title),
            content = CdsDialog.message(this, getString(R.string.channel_pair_clear_message)),
            confirmText = getString(R.string.channel_pair_clear_action),
            cancelText = getString(R.string.common_cancel),
            danger = true,
            onConfirm = {
                ChannelSecurityGuard.clearWhitelist()
                Toast.makeText(this, R.string.channel_pair_clear_done, Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    /**
     * When the entry was first paired, in the user's locale; a null timestamp
     * (entry predates the recording, or a lost write) says "unknown" instead
     * of inventing a date.
     */
    private fun pairedAtText(pairedAtMs: Long?): String =
        pairedAtMs
            ?.let {
                DateFormat.getDateInstance(DateFormat.MEDIUM, java.util.Locale.getDefault())
                    .format(Date(it))
            }
            ?: getString(R.string.channel_pair_paired_at_unknown)

    private fun channelIcon(channel: Channel): Int = when (channel) {
        Channel.DISCORD -> R.drawable.ic_channel_discord
        Channel.TELEGRAM -> R.drawable.ic_channel_telegram
        Channel.WECHAT -> R.drawable.ic_channel_wechat
        Channel.QQ -> R.drawable.ic_channel_qq
        Channel.DOUYIN -> R.drawable.ic_channel_douyin
        // LOCAL / EXTERNAL / SCHEDULED never arrive through /bind pairing;
        // the fallback keeps the `when` honest if that ever changes.
        else -> android.R.drawable.ic_lock_lock
    }
}
