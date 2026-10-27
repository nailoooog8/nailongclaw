// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import io.agents.nailongclaw.R
import io.agents.nailongclaw.automation.ExternalAutomationGuard
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.ui.cds.CdsUi
import io.agents.nailongclaw.utils.KVUtils
import io.agents.nailongclaw.widget.CommonToolbar
import io.agents.nailongclaw.widget.CdsDialog
import io.agents.nailongclaw.widget.MenuGroup

/**
 * 渠道接入 — the page that answers "what can I plug NailongClaw into".
 *
 * Replaces the flat channel rows that used to sit inside the full settings
 * page. Three groups, in the order a user asks the questions:
 *
 *  1. 可接入的软件 — one row per channel the app can actually bind today
 *     (Discord / Telegram / 微信 / QQ / 抖音), each showing live bound state.
 *     Tap an unbound row → its config editor; tap a bound row → unbind first
 *     (the old settings-page behaviour, kept because silently reopening the
 *     editor on a bound channel reads as "nothing happened").
 *  2. 入口 — the ways tasks and config reach the app: pairing PIN, LAN config
 *     console, external automation (Tasker / MacroDroid / adb).
 *  3. 即将推出 — WhatsApp and the web console, honestly labelled instead of
 *     pretending to work.
 *
 * WeChat keeps its QR-login shortcut: the QR flow is the reason most people
 * bind WeChat at all, and burying it behind the manual URL form made the
 * manual path look like the only one.
 */
class ChannelsActivity : BaseActivity() {

    private lateinit var softwareGroup: MenuGroup
    private lateinit var entryGroup: MenuGroup
    private lateinit var soonGroup: MenuGroup

    private val viewModel by lazy {
        ViewModelProvider(this)[SettingsViewModel::class.java]
    }

    private val cds by lazy { CdsUi.palette(this) }

    private val channelConfigLauncher = ChannelConfigActivity.registerLauncher(this) { result ->
        // Any save/unbind lands here; refresh so the trailing state is truthful.
        if (result != null) {
            viewModel.refresh()
            renderAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(cds.surface1)
        setContentView(R.layout.activity_channels)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, getString(R.string.settings_group_channel))
        toolbar.showBackButton(true) { finish() }

        softwareGroup = findViewById(R.id.softwareGroup)
        entryGroup = findViewById(R.id.entryGroup)
        soonGroup = findViewById(R.id.soonGroup)
        listOf(softwareGroup, entryGroup, soonGroup).forEach { it.applyCds(cds) }

        findViewById<TextView>(R.id.tvFooter).apply {
            CdsUi.styleChromeText(this, cds, 11f)
            text = "接入后，别人在对应软件里给机器人发消息即可远程下达任务。\n" +
                "所有凭据只保存在本机（MMKV 加密存储），可随时解绑清空。"
        }
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    private fun renderAll() {
        renderSoftware()
        renderEntries()
        renderSoon()
    }

    /** One row per bindable channel. */
    private fun renderSoftware() {
        softwareGroup.clearMenuItems()
        softwareGroup.setTitle("可接入的软件")

        data class ChannelRow(
            val icon: Int,
            val name: String,
            val type: ChannelConfigActivity.ChannelType,
            val bound: Boolean,
            val unbind: () -> Unit,
        )

        val rows = listOf(
            ChannelRow(
                R.drawable.ic_channel_discord, getString(R.string.menu_discord),
                ChannelConfigActivity.ChannelType.DISCORD,
                viewModel.isDiscordBound(), viewModel::unbindDiscord
            ),
            ChannelRow(
                R.drawable.ic_channel_telegram, getString(R.string.menu_telegram),
                ChannelConfigActivity.ChannelType.TELEGRAM,
                viewModel.isTelegramBound(), viewModel::unbindTelegram
            ),
            ChannelRow(
                R.drawable.ic_channel_wechat, getString(R.string.menu_wechat),
                ChannelConfigActivity.ChannelType.WECHAT,
                viewModel.isWechatBound(), viewModel::unbindWeChat
            ),
            ChannelRow(
                R.drawable.ic_channel_qq, getString(R.string.menu_qq),
                ChannelConfigActivity.ChannelType.QQ,
                viewModel.isQqBound(), viewModel::unbindQq
            ),
            ChannelRow(
                R.drawable.ic_channel_douyin, getString(R.string.menu_douyin),
                ChannelConfigActivity.ChannelType.DOUYIN,
                viewModel.isDouyinBound(), viewModel::unbindDouyin
            ),
        )

        rows.forEachIndexed { index, row ->
            softwareGroup.addMenuItem(
                leadingIcon = row.icon,
                title = row.name,
                onClick = { onChannelClicked(row.name, row.type, row.bound, row.unbind) },
                showDivider = index != rows.lastIndex
            ).apply {
                setTitleColor(cds.textPrimary)
                setTrailingText(
                    if (row.bound) getString(R.string.settings_status_connected)
                    else getString(R.string.settings_status_not_configured)
                )
                setTrailingTextColor(if (row.bound) cds.accent else cds.textMuted)
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
            }
        }
    }

    private fun onChannelClicked(
        name: String,
        type: ChannelConfigActivity.ChannelType,
        bound: Boolean,
        unbind: () -> Unit,
    ) {
        if (!bound) {
            if (type == ChannelConfigActivity.ChannelType.WECHAT) {
                // QR login first: it is the flow most WeChat binds actually use.
                CdsDialog.show(
                    context = this,
                    title = getString(R.string.channel_config_wechat_title),
                    content = CdsDialog.message(
                        this,
                        getString(R.string.channel_config_wechat_choose_mode)
                    ),
                    confirmText = getString(R.string.channel_config_wechat_qr_mode),
                    cancelText = getString(R.string.channel_config_wechat_manual_mode),
                    onConfirm = { viewModel.startWeChatQrLogin(this) },
                    onCancel = { channelConfigLauncher.launch(type) }
                )
                return
            }
            channelConfigLauncher.launch(type)
            return
        }
        CdsDialog.show(
            context = this,
            title = getString(R.string.unbind_title),
            content = CdsDialog.message(this, getString(R.string.unbind_message, name, name)),
            confirmText = getString(R.string.unbind_action),
            cancelText = getString(R.string.common_cancel),
            danger = true,
            onConfirm = {
                unbind()
                Toast.makeText(this, R.string.common_unbound_success, Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    /** Pairing PIN, LAN console, external automation. */
    private fun renderEntries() {
        entryGroup.clearMenuItems()
        entryGroup.setTitle("入口")

        entryGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_lock_lock,
            title = getString(R.string.menu_channel_pin),
            onClick = { showPairPinDialog() },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(KVUtils.getChannelPairPin())
            setTrailingTextColor(cds.textSecondary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }

        entryGroup.addMenuItem(
            leadingIcon = R.drawable.ic_lan_config,
            title = getString(R.string.menu_lan_config),
            onClick = { toggleLanConfig() },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(lanTrailingText())
            setTrailingTextColor(cds.textSecondary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }

        entryGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "外部自动化",
            onClick = { toggleExternalAutomation() },
            showDivider = false
        ).apply {
            setTitleColor(cds.textPrimary)
            val enabled = KVUtils.isExternalAutomationEnabled()
            setTrailingText(
                if (enabled) getString(R.string.settings_status_enabled)
                else getString(R.string.settings_status_disabled)
            )
            setTrailingTextColor(if (enabled) cds.accent else cds.textMuted)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }

        // The token that gates the exported entry points above. Only useful
        // once automation is on, so the row says so instead of offering a
        // dialog that would mint a token for a feature that is switched off.
        entryGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_manage,
            title = getString(R.string.ext_automation_token_row),
            onClick = { showExternalAutomationToken() },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(
                if (KVUtils.isExternalAutomationEnabled()) {
                    getString(R.string.ext_automation_token_set)
                } else {
                    getString(R.string.ext_automation_token_hidden)
                }
            )
            setTrailingTextColor(cds.textSecondary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }
    }

    private fun lanTrailingText(): String =
        if (io.agents.nailongclaw.server.ConfigServerManager.isRunning()) {
            io.agents.nailongclaw.server.ConfigServerManager.getAddress()
                ?: getString(R.string.lan_config_stopped)
        } else {
            getString(R.string.lan_config_stopped)
        }

    private fun toggleLanConfig() {
        val result = viewModel.toggleConfigServer(this)
        if (result == getString(R.string.lan_config_no_wifi)) {
            Toast.makeText(this, R.string.lan_config_no_wifi, Toast.LENGTH_SHORT).show()
        }
        renderAll()
    }

    private fun toggleExternalAutomation() {
        if (KVUtils.isExternalAutomationEnabled()) {
            KVUtils.setExternalAutomationEnabled(false)
            Toast.makeText(this, "外部自动化已关闭", Toast.LENGTH_SHORT).show()
            renderAll()
            return
        }
        CdsDialog.show(
            context = this,
            title = "启用外部自动化？",
            content = CdsDialog.message(
                this,
                "允许 Tasker、MacroDroid 或 adb 等受信任的应用通过显式 Android intent 启动 NailongClaw 任务。" +
                    "请只在你自己掌控这些自动化调用时开启。"
            ),
            confirmText = "启用",
            cancelText = getString(R.string.common_cancel),
            onConfirm = {
                KVUtils.setExternalAutomationEnabled(true)
                Toast.makeText(this, "外部自动化已开启", Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    private fun showPairPinDialog() {
        val currentPin = KVUtils.getChannelPairPin()

        val layout = CdsDialog.column(this)
        val input = CdsDialog.field(
            context = this,
            hint = getString(R.string.channel_pair_pin_hint),
            preset = currentPin,
        ).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        layout.addView(input)

        CdsDialog.show(
            context = this,
            title = getString(R.string.channel_pair_pin_title),
            content = layout,
            confirmText = getString(R.string.common_save),
            cancelText = getString(R.string.common_cancel),
            // Six digits or nothing: a rejected PIN keeps the dialog (and the
            // typed value) alive instead of closing on an invalid save.
            dismissOnConfirm = false,
            onConfirm = { handle ->
                val trimmed = input.text.toString().trim()
                if (trimmed.length == 6 && trimmed.all { it.isDigit() }) {
                    KVUtils.setChannelPairPin(trimmed)
                    viewModel.refresh()
                    Toast.makeText(this, R.string.channel_pair_pin_saved, Toast.LENGTH_SHORT).show()
                    handle.dismiss()
                    renderAll()
                } else {
                    Toast.makeText(
                        this,
                        R.string.channel_pair_pin_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /**
     * The pairing token an automation app has to send as the "token" extra.
     * Without it every RUN_TASK / RUN_CHAT request is refused, because both
     * entry points are exported to every app on the device.
     *
     * Shown in full, once, on purpose: the user has to copy it into another
     * app, so masking it would only push them toward a screenshot. The row
     * itself never displays it.
     */
    private fun showExternalAutomationToken() {
        if (!KVUtils.isExternalAutomationEnabled()) {
            Toast.makeText(this, R.string.ext_automation_token_needs_enabled, Toast.LENGTH_LONG).show()
            return
        }
        // Minting can fail closed when the keystore refuses to encrypt; an
        // empty token means there is nothing to show, and the save path has
        // already surfaced why.
        val token = ExternalAutomationGuard.getOrCreateToken()
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.ext_automation_token_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        CdsDialog.show(
            context = this,
            title = getString(R.string.ext_automation_token_title),
            content = CdsDialog.message(
                this,
                getString(R.string.ext_automation_token_message, token)
            ),
            confirmText = getString(R.string.ext_automation_copy_token),
            cancelText = getString(R.string.common_cancel),
            neutralText = getString(R.string.ext_automation_reset_token),
            onConfirm = {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        getString(R.string.ext_automation_token_title),
                        token
                    )
                )
                Toast.makeText(this, R.string.ext_automation_token_copied, Toast.LENGTH_SHORT).show()
            },
            onNeutral = { confirmResetToken() },
        )
    }

    private fun confirmResetToken() {
        CdsDialog.show(
            context = this,
            title = getString(R.string.ext_automation_reset_title),
            content = CdsDialog.message(this, getString(R.string.ext_automation_reset_message)),
            confirmText = getString(R.string.ext_automation_reset_action),
            cancelText = getString(R.string.common_cancel),
            onConfirm = {
                // A reset invalidates every caller still holding the old
                // token, which is the point — but say so before doing it.
                if (ExternalAutomationGuard.resetToken().isEmpty()) {
                    Toast.makeText(this, R.string.ext_automation_token_unavailable, Toast.LENGTH_LONG).show()
                    return@show
                }
                Toast.makeText(this, R.string.ext_automation_reset_done, Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    /** Honest "not yet" rows — labelled, not pretending. */
    private fun renderSoon() {
        soonGroup.clearMenuItems()
        soonGroup.setTitle("即将推出")

        soonGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_call,
            title = "WhatsApp",
            onClick = {
                Toast.makeText(this, "WhatsApp 接入开发中，暂不可用", Toast.LENGTH_SHORT).show()
            },
            showDivider = true
        ).apply {
            setTitleColor(cds.textMuted)
            setTrailingText(getString(R.string.settings_status_coming_soon))
            setTrailingTextColor(cds.textMuted)
            setLeadingIconColor(cds.textMuted)
            setTrailingIconColor(cds.textMuted)
        }

        soonGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_myplaces,
            title = "Web 控制台",
            onClick = {
                Toast.makeText(this, "Web 控制台开发中，暂不可用", Toast.LENGTH_SHORT).show()
            },
            showDivider = false
        ).apply {
            setTitleColor(cds.textMuted)
            setTrailingText(getString(R.string.settings_status_coming_soon))
            setTrailingTextColor(cds.textMuted)
            setLeadingIconColor(cds.textMuted)
            setTrailingIconColor(cds.textMuted)
        }
    }
}
