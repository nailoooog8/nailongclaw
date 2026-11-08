// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import io.agents.nailongclaw.R
import io.agents.nailongclaw.agent.skill.SkillRegistry
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.service.ClawAccessibilityService
import io.agents.nailongclaw.service.ForegroundService
import io.agents.nailongclaw.service.ShadowDisplayManager
import io.agents.nailongclaw.tool.ToolRegistry
import io.agents.nailongclaw.ui.cds.CdsUi
import io.agents.nailongclaw.widget.CommonToolbar
import io.agents.nailongclaw.widget.MenuGroup

/**
 * 工具 — what the agent can actually call, listed from the live registry.
 *
 * This replaces the old 管理工具 row, which was a dead row: it advertised
 * "12 tools enabled" and opened a Toast saying management was coming. A row
 * that describes a capability should open the capability. Everything here is
 * read from [ToolRegistry] and [SkillRegistry] at resume time, so the page
 * cannot drift from what the agent loop can call — MCP servers connected or
 * removed elsewhere show up here on the next visit.
 *
 * Read-only on purpose: tools are registered by code, MCP configs, and YAML
 * skill files, each of which has its own editor (MCP 服务器 / 技能 pages).
 * A tool list that let you toggle tools off would be a second, lying source
 * of truth for what is callable.
 */
class ToolsActivity : BaseActivity() {

    private lateinit var builtinGroup: MenuGroup
    private lateinit var mcpGroup: MenuGroup
    private lateinit var skillGroup: MenuGroup
    private lateinit var shadowGroup: MenuGroup

    private companion object {
        const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        const val REQ_PROJECTION_CONSENT = 4101
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION_CONSENT) return
        if (data == null) {
            Toast.makeText(this, "未获得授权结果", Toast.LENGTH_SHORT).show()
            return
        }
        val error = ForegroundService.startWithProjection(this, resultCode, data)
        Toast.makeText(
            this,
            error ?: "已授权，正在创建影子屏…",
            Toast.LENGTH_LONG
        ).show()
        renderAll()
    }

    private val cds by lazy { CdsUi.palette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(cds.surface1)
        setContentView(R.layout.activity_tools)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, "工具")
        toolbar.showBackButton(true) { finish() }

        builtinGroup = findViewById(R.id.builtinGroup)
        mcpGroup = findViewById(R.id.mcpGroup)
        skillGroup = findViewById(R.id.skillGroup)
        shadowGroup = findViewById(R.id.shadowGroup)
        listOf(builtinGroup, mcpGroup, skillGroup, shadowGroup).forEach { it.applyCds(cds) }

        findViewById<TextView>(R.id.tvFooter).apply {
            CdsUi.styleChromeText(this, cds, 11f)
            text = "列表来自运行时的工具注册表，与 Agent 实际可调用的完全一致。\n" +
                "MCP 工具在「MCP 服务器」页管理，技能配方在「技能」页管理。"
        }
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    private fun renderAll() {
        val all = ToolRegistry.getAllTools()
        val builtin = all.filterNot { it.getName().startsWith("mcp_") }
        val mcp = all.filter { it.getName().startsWith("mcp_") }
        val skills = SkillRegistry.getAll()

        renderGroup(builtinGroup, "内置工具（${builtin.size}）", builtin.isEmpty()) {
            builtin.forEach { tool ->
                builtinGroup.addMenuItem(
                    leadingIcon = android.R.drawable.ic_menu_manage,
                    title = tool.getDisplayName(),
                    onClick = {},
                    showDivider = true
                ).apply {
                    setTitleColor(cds.textPrimary)
                    setTrailingText(tool.getName())
                    setTrailingTextColor(cds.textMuted)
                    setLeadingIconColor(cds.textPrimary)
                    setTrailingIconColor(cds.textMuted)
                }
            }
        }

        renderGroup(mcpGroup, "MCP 远程工具（${mcp.size}）", mcp.isEmpty()) {
            mcp.forEach { tool ->
                mcpGroup.addMenuItem(
                    leadingIcon = android.R.drawable.ic_menu_share,
                    title = tool.getDisplayName(),
                    onClick = {},
                    showDivider = true
                ).apply {
                    setTitleColor(cds.textPrimary)
                    setTrailingText(tool.getName())
                    setTrailingTextColor(cds.textMuted)
                    setLeadingIconColor(cds.textPrimary)
                    setTrailingIconColor(cds.textMuted)
                }
            }
        }

        renderGroup(skillGroup, "技能（${skills.size}）", skills.isEmpty()) {
            skills.forEach { skill ->
                skillGroup.addMenuItem(
                    leadingIcon = R.drawable.ic_heart,
                    title = skill.name,
                    onClick = {},
                    showDivider = true
                ).apply {
                    setTitleColor(cds.textPrimary)
                    setTrailingText("${skill.steps.size} 步")
                    setTrailingTextColor(cds.textMuted)
                    setLeadingIconColor(cds.textPrimary)
                    setTrailingIconColor(cds.textMuted)
                }
            }
        }

        // 影子屏（虚拟屏）调试入口 — Stage A device QA: create/tear down the
        // display and launch a target app onto it, then exercise the
        // shadow_* tools over a channel or the chat.
        renderGroup(shadowGroup, "影子屏（调试）", false) {
            val id = ShadowDisplayManager.getDisplayIdOrNull()
            shadowGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_secure,
                title = "授权屏幕捕获（MediaProjection）",
                onClick = {
                    // The consent dialog must be driven by an Activity. The
                    // result goes to ForegroundService.startWithProjection,
                    // which upgrades to the mediaProjection FGS type and
                    // creates the display through the projection — a trusted
                    // display, the only kind that hosts OTHER packages.
                    val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                        as android.media.projection.MediaProjectionManager
                    startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION_CONSENT)
                },
                showDivider = true
            ).apply {
                setTitleColor(cds.textPrimary)
                setTrailingText("拉抖音前先授权")
                setTrailingTextColor(cds.textMuted)
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
            }
            shadowGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_view,
                title = if (id == null) "创建影子屏" else "销毁影子屏（id=$id）",
                onClick = {
                    if (ShadowDisplayManager.isCreated()) {
                        ShadowDisplayManager.release()
                        Toast.makeText(this, "影子屏已销毁", Toast.LENGTH_SHORT).show()
                    } else {
                        val created = ShadowDisplayManager.ensureCreated(this)
                        Toast.makeText(
                            this,
                            if (created != null) "影子屏已创建 displayId=$created" else "创建失败（需 Android 13+，见日志）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    renderAll()
                },
                showDivider = true
            ).apply {
                setTitleColor(cds.textPrimary)
                setTrailingText(if (id == null) "未创建" else "运行中")
                setTrailingTextColor(if (id == null) cds.textMuted else cds.accent)
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
            }
            shadowGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_send,
                title = "拉抖音上影子屏",
                onClick = {
                    val target = ShadowDisplayManager.getDisplayIdOrNull()
                    if (target == null) {
                        Toast.makeText(this, "先创建影子屏", Toast.LENGTH_SHORT).show()
                    } else {
                        // Try the production path (a11y service) first, then the
                        // foreground-activity path (no BAL question at all).
                        // Both failure reasons go to the Toast verbatim — the
                        // ROM refuses display-targeted launches for several
                        // distinct reasons and the text decides the remedy.
                        val serviceError = ClawAccessibilityService.getInstance()
                            ?.openAppOnDisplay(DOUYIN_PACKAGE, target)
                        val activityError = serviceError?.let { launchOnDisplay(DOUYIN_PACKAGE, target) }
                        val msg = when {
                            serviceError == null -> "服务路径：已发起启动，真机屏幕应保持不变"
                            activityError == null -> "服务路径失败($serviceError)；前台路径成功"
                            else -> "两条路径均失败：服务=$serviceError 前台=$activityError"
                        }
                        logLaunchDebug("display=$target service=$serviceError activity=$activityError")
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                },
                showDivider = false
            ).apply {
                setTitleColor(cds.textPrimary)
                setTrailingText("com.ss.android.ugc.aweme")
                setTrailingTextColor(cds.textMuted)
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
            }
        }
    }

    /**
     * Launch from this foreground Activity — the most permissive path (no
     * background-activity-launch question at all). Null = success, else the
     * failure reason verbatim.
     */
    private fun launchOnDisplay(packageName: String, displayId: Int): String? {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return "no launch intent for $packageName"
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = android.app.ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)
            startActivity(intent, options.toBundle())
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Persists launch attempts (display id + both paths' verbatim outcomes) to
     * external storage where adb can read them without root — this ROM filters
     * the app's logcat, and the vivo denial message is the one artifact that
     * names the actual missing permission. Diagnostics only, no cleanup yet.
     */
    private fun logLaunchDebug(line: String) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            java.io.File(dir, "shadow_launch_debug.log").appendText(
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " " + line + "\n"
            )
        }
    }

    private fun renderGroup(
        group: MenuGroup,
        title: String,
        empty: Boolean,
        fill: () -> Unit,
    ) {
        group.clearMenuItems()
        group.setTitle(title)
        if (empty) {
            group.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_info_details,
                title = "（无）",
                onClick = {},
                showDivider = false
            ).apply {
                setTitleColor(cds.textMuted)
                setLeadingIconColor(cds.textMuted)
                setShowTrailingIcon(false)
            }
            return
        }
        fill()
        // The last row of each group keeps no divider.
        val last = group.getMenuItemAt(group.getMenuItemCount() - 1)
        last?.setShowDivider(false)
    }
}
