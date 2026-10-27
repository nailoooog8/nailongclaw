// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.agents.nailongclaw.BuildConfig
import io.agents.nailongclaw.R
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.support.DebugReportManager
import io.agents.nailongclaw.ui.cds.CdsUi
import io.agents.nailongclaw.utils.XLog
import io.agents.nailongclaw.widget.CdsDialog
import io.agents.nailongclaw.widget.CommonToolbar
import io.agents.nailongclaw.widget.MenuGroup
import io.agents.nailongclaw.widget.QRCodeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关于 — version, support bundle, repo, sponsor.
 *
 * Split out of the old full-settings page so that page could become a pure
 * navigation hub. The report/share machinery moved here with it (it was
 * SettingsActivity's before); the sheet's 关于 row lands directly on this
 * page, so nothing is two taps deep that used to be one.
 */
class AboutActivity : BaseActivity() {

    private companion object {
        /**
         * Bundled copy of docs/sponsor-qr.jpg. Keep both in sync when rotating
         * the payment code — the repo copy is what FUNDING.yml links to, this
         * one is what the app renders offline.
         */
        const val SPONSOR_QR_ASSET = "sponsor-qr.jpg"
    }

    private val cds by lazy { CdsUi.palette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(cds.surface1)
        setContentView(R.layout.activity_about)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, "关于")
        toolbar.showBackButton(true) { finish() }

        val aboutGroup = findViewById<MenuGroup>(R.id.aboutGroup)
        val whatsNewGroup = findViewById<MenuGroup>(R.id.whatsNewGroup)
        val supportGroup = findViewById<MenuGroup>(R.id.supportGroup)
        aboutGroup.applyCds(cds)
        whatsNewGroup.applyCds(cds)
        supportGroup.applyCds(cds)

        aboutGroup.setTitle("NailongClaw")
        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = "版本",
            onClick = {},
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText("v${BuildConfig.VERSION_NAME}")
            setTrailingTextColor(cds.textSecondary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }
        aboutGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_send,
            title = "GitHub",
            onClick = {
                startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.REPO_URL.toUri()))
            },
            showDivider = false
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(BuildConfig.REPO_SLUG)
            setTrailingTextColor(cds.textSecondary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }

        // 最近更新。三条新功能的常驻说明——刻意不做启动弹窗：社区共识是
        // 没人读 what's new 对话框，这里随「关于」可查，行内文案说清
        // 「它现在长什么样」，与一键 coach mark 各司其职。
        whatsNewGroup.setTitle("最近更新")
        listOf(
            Triple(
                android.R.drawable.ic_menu_recent_history,
                "定时任务",
                "到点自动跑；排队、错过、取消都写在明面上，侧栏「定时」里管",
            ),
            Triple(
                android.R.drawable.ic_secure,
                "确认卡分级",
                "破坏性操作只能允许一次——卡面不再给不会被兑现的「始终允许」",
            ),
            Triple(
                android.R.drawable.ic_menu_agenda,
                "完成摘要",
                "任务答案上方一行数字：耗时、步骤、工具、已建日程",
            ),
        ).forEachIndexed { index, (icon, title, desc) ->
            whatsNewGroup.addMenuItem(
                leadingIcon = icon,
                title = title,
                onClick = {},
                showDivider = index < 2,
            ).apply {
                setTitleColor(cds.textPrimary)
                setTrailingText(desc)
                setTrailingTextColor(cds.textMuted)
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
            }
        }

        supportGroup.setTitle("支持")
        supportGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_send,
            title = getString(R.string.settings_about_report_bug),
            onClick = { reportBug() },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(getString(R.string.settings_about_report_bug_desc))
            setTrailingTextColor(cds.textMuted)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }
        supportGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_upload,
            title = getString(R.string.settings_about_share_debug),
            onClick = { shareDebugReport() },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(getString(R.string.settings_about_share_debug_desc))
            setTrailingTextColor(cds.textMuted)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }

        // Sponsorship. This replaces a row that used to read "开发者 / ithiria894"
        // and linked to the UPSTREAM author. On a fork build that is a misdirection:
        // people holding this APK were sent to a repository that stopped receiving
        // work, while the actual maintainer was one tap further away. Same class of
        // bug as .github/FUNDING.yml shipping `github: ithiria894` — a reference
        // inherited from upstream that silently routes people to someone else.
        //
        // Upstream attribution is NOT lost: NOTICE carries "Copyright 2026 agents.io"
        // and the README has an explicit "上游状态" section. That is where Apache-2.0
        // §4 wants it — a credits screen that points at a dead repo is not
        // attribution, it is a dead link.
        supportGroup.addMenuItem(
            leadingIcon = R.drawable.ic_heart,
            title = getString(R.string.settings_about_sponsor),
            onClick = { showSponsorQrCode() },
            showDivider = false
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(getString(R.string.settings_about_sponsor_desc))
            setTrailingTextColor(cds.textMuted)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }
    }

    /**
     * Show the bundled WeChat payment code in-app.
     *
     * The image ships in assets/ rather than being fetched, so this works offline
     * and cannot be broken by a markdown renderer or a CDN. Decoding a ~95 KB JPEG
     * is not free, so it happens off the main thread; the dialog is only shown once
     * the bitmap exists because QRCodeDialog takes the bitmap eagerly.
     */
    private fun showSponsorQrCode() {
        XLog.i("AboutActivity", "showSponsorQrCode: loading bundled asset")
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    assets.open(SPONSOR_QR_ASSET).use { BitmapFactory.decodeStream(it) }
                }.onFailure { error ->
                    XLog.e("AboutActivity", "Failed to decode sponsor QR asset", error)
                }.getOrNull()
            }
            if (bitmap == null) {
                // Never fail silently (CLAUDE.md): tell the user what happened.
                Toast.makeText(this@AboutActivity, R.string.sponsor_qr_load_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            XLog.i("AboutActivity", "showSponsorQrCode: showing dialog ${bitmap.width}x${bitmap.height}")
            QRCodeDialog.show(
                context = this@AboutActivity,
                title = getString(R.string.sponsor_qr_title),
                subtitle = getString(R.string.sponsor_qr_subtitle),
                qrBitmap = bitmap,
            )
        }
    }

    private fun reportBug() {
        buildSupportBundle(
            preparingToast = "正在生成问题报告…"
        ) { report ->
            CdsDialog.show(
                context = this,
                title = "问题报告已生成",
                content = CdsDialog.message(
                    this,
                    """
                    ${report.name} 已就绪。

                    现在打开 GitHub Issue 提交问题。如果浏览器或 GitHub 应用上传附件不方便，点「分享 ZIP」手动发送。
                    """.trimIndent()
                ),
                confirmText = "打开 GitHub Issue",
                cancelText = "分享 ZIP",
                onConfirm = { openGitHubIssue(report) },
                onCancel = {
                    shareReportFile(
                        report = report,
                        chooserTitle = "分享问题报告 ZIP",
                        subject = "NailongClaw bug report ${BuildConfig.VERSION_NAME}",
                        body = """
                            Attach this ZIP to your GitHub issue:
                            ${BuildConfig.ISSUES_URL}
                        """.trimIndent()
                    )
                }
            )
        }
    }

    private fun shareDebugReport() {
        buildSupportBundle(
            preparingToast = "Preparing debug report…",
        ) { report ->
            shareReportFile(
                report = report,
                chooserTitle = "Share debug report",
                subject = "NailongClaw debug report ${BuildConfig.VERSION_NAME}",
                body = "Attach this debug report when reporting a NailongClaw issue."
            )
        }
    }

    private fun buildSupportBundle(
        preparingToast: String,
        onReportReady: (java.io.File) -> Unit,
    ) {
        lifecycleScope.launch {
            Toast.makeText(this@AboutActivity, preparingToast, Toast.LENGTH_SHORT).show()
            runCatching {
                withContext(Dispatchers.IO) {
                    DebugReportManager.buildReport(this@AboutActivity)
                }
            }.onSuccess { report ->
                onReportReady(report)
            }.onFailure { error ->
                XLog.e("AboutActivity", "Failed to build debug report", error)
                Toast.makeText(this@AboutActivity, "Failed to build debug report", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openGitHubIssue(report: java.io.File) {
        val issueUri = BuildConfig.ISSUES_URL.toUri()
            .buildUpon()
            .appendQueryParameter(
                "title",
                "[Bug] ${Build.MANUFACTURER} ${Build.MODEL} - "
            )
            .appendQueryParameter("body", buildGitHubIssueBody(report))
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, issueUri))
            Toast.makeText(
                this,
                "Attach ${report.name} to the GitHub issue after the page opens",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可打开 GitHub 的应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildGitHubIssueBody(report: java.io.File): String {
        return """
            ## What happened
            -

            ## What you expected
            -

            ## Exact steps to reproduce
            1.
            2.
            3.

            ## Device
            - Manufacturer: ${Build.MANUFACTURER}
            - Model: ${Build.MODEL}
            - Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})

            ## Attachments
            - Attach this ZIP from PokeClaw: `${report.name}`
            - If this looks device-specific and you have ADB available, also attach `adb logcat`

            Generated by PokeClaw ${BuildConfig.VERSION_NAME}.
        """.trimIndent()
    }

    private fun shareReportFile(
        report: java.io.File,
        chooserTitle: String,
        subject: String,
        body: String,
    ) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            report
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可分享报告的应用", Toast.LENGTH_LONG).show()
        }
    }
}
