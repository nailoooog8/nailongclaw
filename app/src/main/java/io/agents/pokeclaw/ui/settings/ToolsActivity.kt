// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.skill.SkillRegistry
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.ui.cds.CdsUi
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.MenuGroup

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
        listOf(builtinGroup, mcpGroup, skillGroup).forEach { it.applyCds(cds) }

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
