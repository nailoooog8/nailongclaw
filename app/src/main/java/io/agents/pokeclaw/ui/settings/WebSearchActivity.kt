// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.SearchProvider
import io.agents.pokeclaw.agent.web.WebSearchClient
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.cds.CdsUi
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.widget.CdsDialog
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.MenuGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Web search settings: pick the active search API provider (Bocha / Tavily)
 * for the built-in web_search tool and manage its API key.
 *
 * Keys are stored encrypted via KVUtils; the active provider is what
 * WebSearchTool reads at execution time.
 */
class WebSearchActivity : BaseActivity() {

    private lateinit var providersGroup: MenuGroup

    /** CDS palette (follows system night mode), shared by all render passes. */
    private val cds by lazy { CdsUi.palette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.setBackgroundColor(cds.surface1)

        setContentView(R.layout.activity_web_search)

        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, "网络搜索")
        toolbar.showBackButton(true) { finish() }

        providersGroup = findViewById(R.id.providersGroup)
        providersGroup.applyCds(cds)

        findViewById<TextView>(R.id.tvWebSearchHint)?.apply {
            text = "为 agent 的 web_search 工具选择搜索服务并保存 API Key（加密存储于本机）。\n" +
                "DeepSeek 最便宜（$0.75/千次，仅成功请求计费）且与 DeepSeek 模型共用同一把 API Key。\n" +
                "博查 / DeepSeek 国内直连；Tavily 需海外网络。\n" +
                "未配置时，知识类问题由模型凭自身知识回答。\n" +
                "「在某 App 里搜索」仍走设备自动化，与此设置无关。"
            CdsUi.styleChromeText(this, cds, 11f)
        }

        renderProviderItems()
    }

    override fun onResume() {
        super.onResume()
        renderProviderItems()
    }

    private fun currentProvider(): SearchProvider =
        SearchProvider.fromName(KVUtils.getWebSearchProvider())

    private fun renderProviderItems() {
        val active = currentProvider()
        providersGroup.clearMenuItems()
        providersGroup.setTitle("搜索服务")

        SearchProvider.entries.forEachIndexed { index, provider ->
            val hasKey = KVUtils.getWebSearchApiKeyFor(provider.name).isNotBlank()
            providersGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_search,
                title = provider.displayName,
                onClick = { showEditDialog(provider) },
                showDivider = index != SearchProvider.entries.size - 1
            ).apply {
                when {
                    provider == active && hasKey -> {
                        setTrailingText("使用中")
                        setTitleColor(cds.textPrimary)
                    }
                    hasKey -> {
                        setTrailingText("已配置")
                        setTitleColor(cds.textPrimary)
                    }
                    else -> {
                        setTrailingText("未配置")
                        setTitleColor(cds.textMuted)
                    }
                }
                setLeadingIconColor(cds.textPrimary)
                setTrailingTextColor(cds.textSecondary)
            }
        }
    }

    private fun showEditDialog(provider: SearchProvider) {
        val layout = CdsDialog.column(this)

        fun label(text: String) {
            layout.addView(CdsDialog.label(this, text))
        }

        label("API Key（${provider.docsUrl} 注册获取）")
        val keyInput = CdsDialog.field(this, provider.keyHint, KVUtils.getWebSearchApiKeyFor(provider.name), password = true)
        layout.addView(keyInput)

        val activeCheck = CheckBox(this).apply {
            text = "设为当前使用的搜索服务"
            isChecked = currentProvider() == provider
            setTextColor(cds.textPrimary)
            typeface = CdsUi.fontSans()
            setPadding(0, CdsUi.dp(context, 12), 0, 0)
        }
        layout.addView(activeCheck)

        // Ghost-pill test button — same shape language as LlmConfigActivity's
        // Test Connection pill, adapted to the dialog surface.
        val btnTest = TextView(this).apply {
            text = "测试连接"
            typeface = CdsUi.fontSans()
            textSize = 13f
            setTextColor(cds.accent)
            gravity = Gravity.CENTER
            setPadding(CdsUi.dp(context, 16), CdsUi.dp(context, 8), CdsUi.dp(context, 16), CdsUi.dp(context, 8))
            background = GradientDrawable().apply {
                cornerRadius = CdsUi.dp(context, 16f)
                setStroke(CdsUi.dp(context, 1), cds.border)
                setColor(0x00000000)
            }
        }
        layout.addView(btnTest)

        val tvTestStatus = TextView(this).apply {
            typeface = CdsUi.fontSans()
            textSize = 12f
            setTextColor(cds.textSecondary)
            setPadding(0, CdsUi.dp(context, 8), 0, 0)
        }
        layout.addView(tvTestStatus)

        btnTest.setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isEmpty()) {
                tvTestStatus.text = "请先填入 API Key"
                tvTestStatus.setTextColor(cds.danger)
                return@setOnClickListener
            }
            btnTest.isEnabled = false
            tvTestStatus.text = "搜索测试中…"
            tvTestStatus.setTextColor(cds.textSecondary)
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    WebSearchClient(provider, key).search("hello", 1)
                }
                btnTest.isEnabled = true
                result.fold(
                    onSuccess = {
                        tvTestStatus.text = "连接成功，返回 ${it.size} 条结果"
                        tvTestStatus.setTextColor(cds.textSecondary)
                    },
                    onFailure = {
                        tvTestStatus.text = "失败：${it.message?.take(120)}"
                        tvTestStatus.setTextColor(cds.danger)
                    }
                )
            }
        }

        CdsDialog.show(
            context = this,
            title = "配置 ${provider.displayName}",
            content = layout,
            confirmText = "保存",
            cancelText = getString(R.string.common_cancel),
            onConfirm = {
                val key = keyInput.text.toString().trim()
                KVUtils.setWebSearchApiKeyFor(provider.name, key)
                if (activeCheck.isChecked) {
                    if (key.isNotBlank()) {
                        KVUtils.setWebSearchProvider(provider.name)
                    } else {
                        Toast.makeText(this, "Key 为空，未切换当前搜索服务", Toast.LENGTH_SHORT).show()
                    }
                }
                renderProviderItems()
                // F6: a failed encryption stores nothing; don't claim otherwise.
                if (KVUtils.consumeSecureWriteFailure() != null) {
                    Toast.makeText(this, R.string.secure_write_failed, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
