// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import io.agents.nailongclaw.AppCapabilityCoordinator
import io.agents.nailongclaw.R
import io.agents.nailongclaw.agent.llm.ActiveModelMode
import io.agents.nailongclaw.agent.llm.ModelConfigRepository
import io.agents.nailongclaw.agent.mcp.McpConnectionManager
import io.agents.nailongclaw.agent.memory.MemoryStore
import io.agents.nailongclaw.agent.skill.SkillRegistry
import io.agents.nailongclaw.agent.skill.UserSkillLoader
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.tool.ToolRegistry
import io.agents.nailongclaw.ui.cds.CdsUi
import io.agents.nailongclaw.utils.KVUtils
import io.agents.nailongclaw.utils.XLog
import io.agents.nailongclaw.widget.CommonToolbar
import io.agents.nailongclaw.widget.MenuGroup

/**
 * 设置 — a navigation hub, not a page of settings.
 *
 * This used to be one flat scroll holding permissions, channels, model,
 * appearance, tools, remote and about rows all at once — the page the Claude
 * UI sheet's 「全部设置」 row opened, which made settings nest inside settings
 * for no benefit. It is now split: every row below opens the section page that
 * owns that behaviour, and this page only answers "where do I find X".
 *
 * The sheet on the Claude UI surface links to the same section pages, so the
 * two doors lead to identical rooms. Trailing labels are computed on resume
 * from the live registries — a hub that lies about counts is worse than no
 * hub.
 */
class SettingsActivity : BaseActivity() {

    private lateinit var configGroup: MenuGroup
    private lateinit var featureGroup: MenuGroup
    private lateinit var appearanceGroup: MenuGroup
    private lateinit var systemGroup: MenuGroup

    /** Row references, refreshed on resume so trailing labels stay truthful. */
    private var modelItem: io.agents.nailongclaw.widget.MenuItem? = null
    private var channelsItem: io.agents.nailongclaw.widget.MenuItem? = null
    private var monitorItem: io.agents.nailongclaw.widget.MenuItem? = null
    private var toolsItem: io.agents.nailongclaw.widget.MenuItem? = null
    private var mcpItem: io.agents.nailongclaw.widget.MenuItem? = null
    private var webSearchItem: io.agents.nailongclaw.widget.MenuItem? = null
    private var skillsItem: io.agents.nailongclaw.widget.MenuItem? = null
    private var memoryItem: io.agents.nailongclaw.widget.MenuItem? = null
    private var themeItem: io.agents.nailongclaw.widget.MenuItem? = null
    private var aboutItem: io.agents.nailongclaw.widget.MenuItem? = null

    /** CDS palette for this page (resolved from the stored theme axes). */
    private val cds by lazy { CdsUi.palette(this) }

    /** Theme fingerprint captured at build time — the theme page below this
     *  one in the stack writes the axes without recreating us, so a stale
     *  palette would keep painting yesterday's accent. */
    private var themeFingerprint: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeFingerprint = CdsUi.fingerprint(this)
        window.decorView.setBackgroundColor(cds.surface1)
        setContentView(R.layout.activity_settings)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, getString(R.string.settings_title))
        toolbar.showBackButton(true) { finish() }

        configGroup = findViewById(R.id.configGroup)
        featureGroup = findViewById(R.id.featureGroup)
        appearanceGroup = findViewById(R.id.appearanceGroup)
        systemGroup = findViewById(R.id.systemGroup)
        listOf(configGroup, featureGroup, appearanceGroup, systemGroup)
            .forEach { it.applyCds(cds) }

        buildGroups()
    }

    override fun onResume() {
        super.onResume()
        if (themeFingerprint != CdsUi.fingerprint(this)) {
            // Theme changed underneath us (native theme page, or the sidebar
            // popover while we were backgrounded) — rebuild with fresh tokens.
            recreate()
            return
        }
        refreshTrailingTexts()
    }

    private fun buildGroups() {
        configGroup.setTitle("配置")
        modelItem = configGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = getString(R.string.settings_section_model),
            onClick = { open(ModelSettingsActivity::class.java) },
            showDivider = true
        )
        channelsItem = configGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_send,
            title = getString(R.string.settings_group_channel),
            onClick = { open(ChannelsActivity::class.java) },
            showDivider = true
        )
        monitorItem = configGroup.addMenuItem(
            leadingIcon = R.drawable.ic_accessibility,
            title = getString(R.string.settings_section_monitor),
            onClick = { open(MonitorActivity::class.java) },
            showDivider = false
        )

        featureGroup.setTitle("功能")
        toolsItem = featureGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_manage,
            title = getString(R.string.settings_group_tools),
            onClick = { open(ToolsActivity::class.java) },
            showDivider = true
        )
        mcpItem = featureGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "MCP 服务器",
            onClick = { open(McpServersActivity::class.java) },
            showDivider = true
        )
        webSearchItem = featureGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_search,
            title = "网络搜索",
            onClick = { open(WebSearchActivity::class.java) },
            showDivider = true
        )
        skillsItem = featureGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_agenda,
            title = getString(R.string.settings_section_skills),
            onClick = { open(UserSkillsActivity::class.java) },
            showDivider = true
        )
        memoryItem = featureGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_save,
            title = getString(R.string.settings_section_memory),
            onClick = { open(MemoryActivity::class.java) },
            showDivider = false
        )

        appearanceGroup.setTitle(getString(R.string.settings_group_appearance))
        themeItem = appearanceGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_slideshow,
            title = "主题",
            onClick = { open(ThemeActivity::class.java) },
            showDivider = false
        )

        systemGroup.setTitle(getString(R.string.settings_group_about))
        aboutItem = systemGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_info_details,
            title = getString(R.string.settings_group_about),
            onClick = { open(AboutActivity::class.java) },
            showDivider = false
        )

        refreshTrailingTexts()
    }

    /** Live counts/labels, recomputed on every resume. */
    private fun refreshTrailingTexts() {
        modelItem?.setTrailingText(activeModelLabel())
        channelsItem?.setTrailingText(boundChannelsLabel())
        monitorItem?.setTrailingText(monitorLabel())
        toolsItem?.setTrailingText("${ToolRegistry.getAllTools().size} 个")
        mcpItem?.setTrailingText(mcpLabel())
        webSearchItem?.setTrailingText(webSearchLabel())
        skillsItem?.setTrailingText(skillsLabel())
        memoryItem?.setTrailingText(memoryLabel())
        themeItem?.setTrailingText(themeLabel())
        aboutItem?.setTrailingText("v${io.agents.nailongclaw.BuildConfig.VERSION_NAME}")
    }

    private fun open(target: Class<*>) {
        XLog.i("SettingsActivity", "open section: ${target.simpleName}")
        startActivity(Intent(this, target))
    }

    private fun activeModelLabel(): String {
        val config = ModelConfigRepository.snapshot()
        return if (config.activeMode == ActiveModelMode.LOCAL) {
            config.local.displayName.ifBlank { "未选择" }
        } else {
            config.activeCloud.modelName.ifBlank { "未选择" }
        }
    }

    private fun boundChannelsLabel(): String {
        var bound = 0
        if (KVUtils.getDiscordBotToken().isNotEmpty()) bound++
        if (KVUtils.getTelegramBotToken().isNotEmpty()) bound++
        if (KVUtils.getWechatBotToken().isNotEmpty() || KVUtils.getWechatApiBaseUrl().isNotEmpty()) bound++
        if (KVUtils.getQqWsUrl().isNotEmpty()) bound++
        if (KVUtils.getDouyinWebhookUrl().isNotEmpty() || KVUtils.getDouyinToken().isNotEmpty()) bound++
        return "$bound/5 已接入"
    }

    private fun monitorLabel(): String {
        val snapshot = AppCapabilityCoordinator.snapshot(this)
        return if (snapshot.accessibilityState == io.agents.nailongclaw.ServiceBindingState.READY &&
            snapshot.notificationAccessState == io.agents.nailongclaw.ServiceBindingState.READY
        ) {
            "就绪"
        } else {
            "未就绪"
        }
    }

    private fun mcpLabel(): String {
        val count = McpConnectionManager.loadConfigs(this).size
        return if (count == 0) getString(R.string.settings_status_not_configured)
        else getString(R.string.settings_count_configured, count)
    }

    private fun webSearchLabel(): String {
        if (!KVUtils.isWebSearchConfigured()) return getString(R.string.settings_status_not_configured)
        return io.agents.nailongclaw.agent.SearchProvider.fromName(KVUtils.getWebSearchProvider()).displayName
    }

    private fun skillsLabel(): String {
        val user = UserSkillLoader.listFiles(this).size
        val builtin = SkillRegistry.getAll().count()
        return "内置 $builtin · 自定义 $user"
    }

    private fun memoryLabel(): String {
        MemoryStore.init(this)
        if (!MemoryStore.isEnabled()) return getString(R.string.settings_off)
        val count = MemoryStore.count()
        return if (count == 0) getString(R.string.settings_count_empty)
        else getString(R.string.settings_count_items, count)
    }

    private fun themeLabel(): String {
        val base = KVUtils.getString("THEME_BASE", "clay")
        val accent = KVUtils.getString("THEME_ACCENT", "clay")
        val mode = KVUtils.getString("THEME_MODE", "")
        val modeSuffix = when (mode) {
            "light" -> " · 浅色"
            "dark" -> " · 深色"
            else -> ""
        }
        return baseLabel(base) + " · " + accentLabel(accent) + modeSuffix
    }

    private fun baseLabel(key: String): String = when (key) {
        "clay" -> getString(R.string.theme_base_clay)
        "porcelain" -> getString(R.string.theme_base_porcelain)
        "sand" -> getString(R.string.theme_base_sand)
        "caramel" -> getString(R.string.theme_base_caramel)
        "taupe" -> getString(R.string.theme_base_taupe)
        "rose" -> getString(R.string.theme_base_rose)
        "mint" -> getString(R.string.theme_base_mint)
        else -> key
    }

    private fun accentLabel(key: String): String = when (key) {
        "clay" -> getString(R.string.theme_accent_clay)
        "clay-bright" -> getString(R.string.theme_accent_clay_bright)
        "sage" -> getString(R.string.theme_accent_sage)
        "indigo" -> getString(R.string.theme_accent_indigo)
        "ochre" -> getString(R.string.theme_accent_ochre)
        "wisteria" -> getString(R.string.theme_accent_wisteria)
        "teal" -> getString(R.string.theme_accent_teal)
        else -> key
    }
}
