// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.settings

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.agents.nailongclaw.ClawApplication
import io.agents.nailongclaw.R
import io.agents.nailongclaw.agent.CloudProvider
import io.agents.nailongclaw.agent.llm.ActiveModelMode
import io.agents.nailongclaw.agent.llm.LocalModelManager
import io.agents.nailongclaw.agent.llm.ModelConfigRepository
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.ui.cds.CdsUi
import io.agents.nailongclaw.utils.KVUtils
import io.agents.nailongclaw.widget.CommonToolbar
import io.agents.nailongclaw.widget.KButton
import java.util.concurrent.Executors

/**
 * 模型选择 — the sheet's 模型与任务 landing spot.
 *
 * The page used to be colored like CDS (H31) but still typeset and wired in
 * the ember language: CardView cards with stock shadows, plain-text action
 * "buttons" (使用 / ↓ 下载 / a 🗑 emoji), English section headers next to the
 * Chinese pages around it, and success/warning colors borrowed from the chat
 * theme. This pass finishes the migration the way [MenuGroup.applyCds] did for
 * the row pages: every surface is a `--cds-panel`, names are serif content
 * text, meta is sans chrome text, actions are ghost pills, and statuses use
 * the CDS palette (accent = ready, danger = error — the CDS has no amber, so
 * the RAM warning stays muted and the ⚠ glyph carries the meaning).
 */
class LlmConfigActivity : BaseActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var isDownloading = false
    private var selectedProvider: CloudProvider = CloudProvider.OPENAI
    private var selectedModelId: String = ""

    private val cds by lazy { CdsUi.palette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        // CDS tokens: this page is the sheet's 模型与预算 row. The chat
        // ThemeManager (ember) stays with the chat surfaces only.
        window.decorView.setBackgroundColor(cds.surface1)
        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, "模型")
        toolbar.showBackButton(true) { finish() }

        // Include user's custom local model URL (#36) at the tail of the rendered list.
        val models = LocalModelManager.AVAILABLE_MODELS + listOfNotNull(LocalModelManager.customModel())
        val activeModelName = findViewById<TextView>(R.id.tvActiveModelName)
        val activeModelMeta = findViewById<TextView>(R.id.tvActiveModelMeta)
        val activeModelStatus = findViewById<TextView>(R.id.tvActiveModelStatus)
        val defaultLocalName = findViewById<TextView>(R.id.tvDefaultLocalName)
        val defaultLocalMeta = findViewById<TextView>(R.id.tvDefaultLocalMeta)
        val defaultLocalStatus = findViewById<TextView>(R.id.tvDefaultLocalStatus)
        val defaultCloudName = findViewById<TextView>(R.id.tvDefaultCloudName)
        val defaultCloudMeta = findViewById<TextView>(R.id.tvDefaultCloudMeta)
        val defaultCloudStatus = findViewById<TextView>(R.id.tvDefaultCloudStatus)
        val modelList = findViewById<LinearLayout>(R.id.layoutModelList)
        val resolvedConfig = ModelConfigRepository.snapshot()
        val deviceSupport = LocalModelManager.deviceSupport(this)
        val catalog = LocalModelManager.catalog(this).associateBy { it.model.id }

        // ---- Panels: the three cards become --cds-panel (surface-2 + hairline
        // + 10px radius, zero elevation — the flat sheet look). ----
        listOf(R.id.activeCard, R.id.defaultsCard, R.id.cloudCard).forEach { id ->
            findViewById<View>(id)?.background = cardBackground(cds.surface2)
        }
        findViewById<View>(R.id.dividerDefaults)?.setBackgroundColor(cds.border)
        findViewById<TextView>(R.id.avatarActive)?.background = GradientDrawable().apply {
            setColor(cds.surface3)
            cornerRadius = dp(10f)
            setStroke(dp(1), cds.border)
        }

        // ---- Typography: section headers are the direct-child id-less
        // TextViews of the scroll content; field labels carry ids now (the
        // old size-based heuristic recolored emoji on some builds). ----
        val scrollContent = findViewById<LinearLayout>(R.id.layoutModelList)?.parent as? LinearLayout
        scrollContent?.let { root ->
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is TextView && child.id == View.NO_ID) {
                    CdsUi.styleGroupLabel(child, cds)
                }
            }
        }
        listOf(
            R.id.labelDefaultLocal, R.id.labelDefaultCloud,
            R.id.labelApiKey, R.id.labelBaseUrl, R.id.labelModelName,
        ).forEach { id ->
            findViewById<TextView>(id)?.let { CdsUi.styleChromeText(it, cds, 12f) }
        }

        // Active-model card text — serif names, sans meta.
        CdsUi.styleContentText(activeModelName, cds, 15f, bold = true)
        CdsUi.styleChromeText(activeModelMeta, cds, 12f)
        activeModelStatus.typeface = CdsUi.fontSans()
        activeModelStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        CdsUi.styleContentText(defaultLocalName, cds, 14f, bold = true)
        CdsUi.styleChromeText(defaultLocalMeta, cds, 12f)
        defaultLocalStatus.typeface = CdsUi.fontSans()
        defaultLocalStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        CdsUi.styleContentText(defaultCloudName, cds, 14f, bold = true)
        CdsUi.styleChromeText(defaultCloudMeta, cds, 12f)
        defaultCloudStatus.typeface = CdsUi.fontSans()
        defaultCloudStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        findViewById<TextView>(R.id.tvCustomHint)?.let { CdsUi.styleChromeText(it, cds, 11f) }

        // Active model — show what is ACTUALLY active based on provider
        if (resolvedConfig.activeMode == ActiveModelMode.LOCAL) {
            val activeState = LocalModelManager.resolveActiveModelState(this, resolvedConfig.local)
            activeModelName.text = activeState.displayName
            activeModelMeta.text = activeState.metaText
            activeModelStatus.text = activeState.statusText
            activeModelStatus.setTextColor(statusColor(activeState.statusKind))
        } else {
            val cloudModel = resolvedConfig.activeCloud.modelName
            if (cloudModel.isNotEmpty()) {
                activeModelName.text = cloudModel
                val providerName = resolvedConfig.activeCloud.provider.displayName
                activeModelMeta.text = "$providerName · 云端"
                activeModelStatus.text = "● 已连接"
                activeModelStatus.setTextColor(cds.accent)
            } else {
                activeModelName.text = "未选择模型"
                activeModelMeta.text = "在下方配置云端模型"
                activeModelStatus.text = "● 未配置"
                activeModelStatus.setTextColor(cds.textMuted)
            }
        }

        val defaultLocalState = LocalModelManager.resolveActiveModelState(this, resolvedConfig.local)
        defaultLocalName.text = defaultLocalState.displayName
        defaultLocalMeta.text = defaultLocalState.metaText
        defaultLocalStatus.text = defaultLocalState.statusText
        defaultLocalStatus.setTextColor(statusColor(defaultLocalState.statusKind))

        if (resolvedConfig.defaultCloud.isConfigured) {
            defaultCloudName.text = resolvedConfig.defaultCloud.modelName
            defaultCloudMeta.text = "${resolvedConfig.defaultCloud.provider.displayName} · 云端"
            defaultCloudStatus.text = "● 就绪"
            defaultCloudStatus.setTextColor(cds.accent)
        } else {
            defaultCloudName.text = "未设置默认云端模型"
            defaultCloudMeta.text = "在下方配置云端模型"
            defaultCloudStatus.text = "● 未配置"
            defaultCloudStatus.setTextColor(cds.textMuted)
        }

        val activeLocalModelId = if (resolvedConfig.activeMode == ActiveModelMode.LOCAL) resolvedConfig.local.modelId else ""
        val defaultLocalModelId = resolvedConfig.local.modelId
        val configuredBuiltInLocal = LocalModelManager.configuredBuiltInModel(resolvedConfig.local)

        // Build model list
        models.forEach { model ->
            val modelEntry = catalog[model.id]
            val availability = LocalModelManager.availabilityForModel(this, model, resolvedConfig.local)
            val downloaded = availability.isAvailable
            val isActive = model.id == activeLocalModelId
            val isDefaultLocal = model.id == defaultLocalModelId
            val supportedOnDevice = modelEntry?.isSupported == true
            val resolvedLocalPath = when (availability.source) {
                LocalModelManager.AvailabilitySource.MANAGED_DOWNLOAD -> LocalModelManager.getModelPath(this, model)
                LocalModelManager.AvailabilitySource.LINKED_FILE ->
                    if (configuredBuiltInLocal?.id == model.id) resolvedConfig.local.modelPath else null
                LocalModelManager.AvailabilitySource.MISSING -> null
            }

            val card = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBackground(cds.surface2)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }

            // Model info
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            info.addView(TextView(this).apply {
                text = model.displayName
                CdsUi.styleContentText(this, cds, 14f, bold = isActive)
            })

            info.addView(TextView(this).apply {
                val baseText = "${model.sizeBytes / 1_000_000} MB · 需 ${model.minRamGb}GB+ 内存"
                text = if (supportedOnDevice) baseText else "$baseText · 本机 ${deviceSupport.deviceRamGb}GB"
                CdsUi.styleChromeText(this, cds, 11.5f)
                if (!supportedOnDevice) setTextColor(cds.textMuted)
            })

            card.addView(info)

            // Actions — ghost pills instead of bare text and a 🗑 emoji.
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            if (downloaded) {
                if (isActive) {
                    actions.addView(TextView(this).apply {
                        text = if (supportedOnDevice) "✓ 已启用" else "⚠ 已启用"
                        typeface = CdsUi.fontSans()
                        setTextColor(if (supportedOnDevice) cds.accent else cds.textMuted)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                    })
                } else {
                    if (isDefaultLocal) {
                        actions.addView(TextView(this).apply {
                            text = "默认"
                            typeface = CdsUi.fontSans()
                            setTextColor(cds.textMuted)
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
                            setPadding(dp(4), dp(6), dp(8), dp(6))
                        })
                    }
                    if (supportedOnDevice) {
                        actions.addView(ghostPill("使用", cds.accent) {
                            val path = resolvedLocalPath
                            if (path != null) {
                                // Save as default local model (independent of cloud config)
                                // Only switch active provider if currently on local tab
                                val shouldActivateLocal = ModelConfigRepository.isLocalActive() || !KVUtils.hasDefaultCloudModel()
                                ModelConfigRepository.saveLocalDefault(path, model.id, shouldActivateLocal)
                                ClawApplication.appViewModelInstance.updateAgentConfig()
                                ClawApplication.appViewModelInstance.initAgent()
                                Toast.makeText(this@LlmConfigActivity, "设为默认本地模型：${model.displayName}", Toast.LENGTH_SHORT).show()
                                recreate()
                            } else {
                                Toast.makeText(this@LlmConfigActivity, "模型文件不存在", Toast.LENGTH_SHORT).show()
                            }
                        })
                    } else {
                        actions.addView(TextView(this).apply {
                            text = "需 ${model.minRamGb}GB+ 内存"
                            typeface = CdsUi.fontSans()
                            setTextColor(cds.textMuted)
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
                            setPadding(dp(4), dp(6), dp(4), dp(6))
                        })
                    }

                    if (availability.source == LocalModelManager.AvailabilitySource.MANAGED_DOWNLOAD) {
                        actions.addView(ghostPill("删除", cds.textMuted) {
                            LocalModelManager.deleteModel(this@LlmConfigActivity, model)
                            Toast.makeText(this@LlmConfigActivity, "已删除 ${model.displayName}", Toast.LENGTH_SHORT).show()
                            recreate()
                        })
                    }
                }
            } else {
                if (supportedOnDevice) {
                    val dlBtn = ghostPill("↓ 下载", cds.accent) {
                        if (isDownloading) {
                            Toast.makeText(this@LlmConfigActivity, "已在下载中", Toast.LENGTH_SHORT).show()
                            return@ghostPill
                        }
                        isDownloading = true
                        text = "下载中…"
                        isEnabled = false

                        executor.submit {
                            LocalModelManager.downloadModel(this@LlmConfigActivity, model, object : LocalModelManager.DownloadCallback {
                                override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                                    val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                                    runOnUiThread { text = "$pct%" }
                                }
                                override fun onComplete(modelPath: String) {
                                    runOnUiThread {
                                        ModelConfigRepository.saveLocalDefault(
                                            modelPath = modelPath,
                                            modelId = model.id,
                                            activateNow = false
                                        )
                                        isDownloading = false
                                        Toast.makeText(this@LlmConfigActivity, "已下载", Toast.LENGTH_SHORT).show()
                                        recreate()
                                    }
                                }
                                override fun onError(error: String) {
                                    runOnUiThread {
                                        isDownloading = false
                                        text = "↓ 下载"
                                        isEnabled = true
                                        Toast.makeText(this@LlmConfigActivity, error, Toast.LENGTH_LONG).show()
                                    }
                                }
                            })
                        }
                    }
                    actions.addView(dlBtn)
                } else {
                    actions.addView(TextView(this).apply {
                        text = "需 ${model.minRamGb}GB+ 内存"
                        typeface = CdsUi.fontSans()
                        setTextColor(cds.textMuted)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                    })
                }
            }

            card.addView(actions)
            modelList.addView(card)
        }

        // Storage info
        updateStorageInfo()

        // Cloud LLM — Provider tabs + model cards
        setupCloudLlm(cds)
    }

    /** `--cds-panel`: surface fill, 1px hairline, 10px radius, no elevation. */
    private fun cardBackground(fill: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(10f)
            setStroke(dp(1), cds.border)
        }

    /**
     * Status ink from the CDS palette: accent for ready (`--cds-fill-brand`),
     * muted for neutral, danger for error. The palette has no amber — the RAM
     * warning keeps the ⚠ glyph and reads as muted instead of borrowing the
     * chat theme's warning color.
     */
    private fun statusColor(kind: LocalModelManager.StatusKind): Int =
        when (kind) {
            LocalModelManager.StatusKind.READY -> cds.accent
            LocalModelManager.StatusKind.WARNING -> cds.textMuted
            LocalModelManager.StatusKind.NEUTRAL -> cds.textMuted
        }

    /**
     * The mockup's `.pill`: content-width capsule, hairline border, sans
     * 12.5sp label, 5%-ink press. Row actions on the model cards use it so
     * 使用 / 下载 / 删除 read as buttons instead of bare text.
     */
    private fun ghostPill(text: String, color: Int, onClick: (TextView.() -> Unit)? = null): TextView =
        TextView(this).apply {
            this.text = text
            typeface = CdsUi.fontSans()
            setTextColor(color)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            val normal = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setStroke(dp(1), cds.border)
                setColor(0x00000000)
            }
            val pressed = GradientDrawable().apply {
                cornerRadius = dp(16f)
                setStroke(dp(1), cds.border)
                setColor(cds.ghostHover)
            }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }
            setOnClickListener { onClick?.invoke(this) }
        }

    private fun updateStorageInfo() {
        val models = LocalModelManager.AVAILABLE_MODELS + listOfNotNull(LocalModelManager.customModel())
        var totalSize = 0L
        var count = 0
        models.forEach { model ->
            if (LocalModelManager.isModelDownloaded(this, model)) {
                totalSize += model.sizeBytes
                count++
            }
        }
        val mbUsed = totalSize / 1_000_000
        val allocated = 4000L // 4GB rough estimate
        val pct = (mbUsed * 100 / allocated).toInt().coerceAtMost(100)

        findViewById<TextView>(R.id.tvStorageInfo).text = "$count 个模型 · ${mbUsed} MB"
        findViewById<ProgressBar>(R.id.progressStorage).progress = pct
        findViewById<TextView>(R.id.tvStorageDetail).text = "已用 ${mbUsed} MB / 配额 ${allocated} MB"
    }

    private fun setupCloudLlm(cds: CdsUi.Palette) {
        val tabLayout = findViewById<LinearLayout>(R.id.layoutProviderTabs)
        val modelListLayout = findViewById<LinearLayout>(R.id.layoutCloudModels)
        val layoutBaseUrl = findViewById<View>(R.id.layoutBaseUrl)
        val tvCustomHint = findViewById<TextView>(R.id.tvCustomHint)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val tvStatus = findViewById<TextView>(R.id.tvConnectionStatus)
        val btnTest = findViewById<TextView>(R.id.btnTestConnection)
        val btnSave = findViewById<KButton>(R.id.btnSaveCloud)
        val btnClear = findViewById<TextView>(R.id.btnClearApiKey)
        val scrollView = findViewById<android.widget.ScrollView>(R.id.scrollContent)
        val configSnapshot = ModelConfigRepository.snapshot()
        val cloudSeed = configSnapshot.defaultCloud.let {
            if (it.modelName.isNotEmpty() || it.apiKey.isNotEmpty() || it.baseUrl.isNotEmpty()) it else configSnapshot.activeCloud
        }

        installKeyboardAwareScrolling(scrollView, listOf(etApiKey, etBaseUrl, etModelName))

        // CDS input treatment on the credential fields
        listOf(etApiKey, etBaseUrl, etModelName).forEach {
            CdsUi.styleEditText(it, cds)
        }
        tvStatus?.typeface = CdsUi.fontSans()
        tvStatus?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        btnClear.typeface = CdsUi.fontSans()
        btnClear.setTextColor(cds.danger)
        btnSave?.apply {
            setBgColor(cds.accent)
            setTextColor(cds.onAccent)
            typeface = CdsUi.fontSans()
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        // Test connection — ghost pill at button height.
        btnTest.typeface = CdsUi.fontSans()
        btnTest.setTextColor(cds.accent)
        btnTest.gravity = Gravity.CENTER
        val testNormal = GradientDrawable().apply {
            cornerRadius = dp(16f)
            setStroke(dp(1), cds.border)
            setColor(0x00000000)
        }
        val testPressed = GradientDrawable().apply {
            cornerRadius = dp(16f)
            setStroke(dp(1), cds.border)
            setColor(cds.ghostHover)
        }
        btnTest.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), testPressed)
            addState(intArrayOf(), testNormal)
        }

        // Clear API key button
        btnClear.setOnClickListener {
            etApiKey.setText("")
            KVUtils.setApiKeyForProvider(selectedProvider.name, "")
            KVUtils.setLlmApiKey("")
            Toast.makeText(this, "已清除 API Key", Toast.LENGTH_SHORT).show()
        }

        // Determine current provider from saved config
        selectedProvider = CloudProvider.fromName(cloudSeed.providerName)
        selectedModelId = cloudSeed.modelName
        etApiKey.setText(cloudSeed.apiKey)

        // The tab strip is the CDS segmented track (alpha ink); the selected
        // tab is a surface-2 thumb — same tokens as CdsSegmented.
        tabLayout.background = GradientDrawable().apply {
            cornerRadius = dp(20f)
            setColor(cds.ghostHover)
        }

        // Build provider tabs
        val tabViews = mutableMapOf<CloudProvider, TextView>()
        CloudProvider.entries.forEach { provider ->
            val tab = TextView(this).apply {
                text = provider.displayName
                typeface = CdsUi.fontSans()
                textSize = 13f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                // Click handler set below after renderModels is defined
            }
            tabViews[provider] = tab
            tabLayout.addView(tab)
        }

        fun updateTabStyles() {
            tabViews.forEach { (provider, tab) ->
                if (provider == selectedProvider) {
                    tab.setTextColor(cds.textPrimary)
                    tab.setTypeface(tab.typeface, android.graphics.Typeface.BOLD)
                    tab.background = GradientDrawable().apply {
                        cornerRadius = dp(16f)
                        setColor(cds.surface2)
                    }
                } else {
                    tab.setTextColor(cds.textMuted)
                    tab.background = null
                }
            }
        }
        updateTabStyles()

        // Render model cards for current provider
        fun renderModels() {
            modelListLayout.removeAllViews()
            val isCustom = selectedProvider == CloudProvider.CUSTOM
            layoutBaseUrl.visibility = if (isCustom) View.VISIBLE else View.GONE
            tvCustomHint?.visibility = if (isCustom) View.VISIBLE else View.GONE

            if (isCustom) {
                etBaseUrl.setText(cloudSeed.baseUrl)
                etModelName.setText(selectedModelId)
                return
            }

            selectedProvider.models.forEach { model ->
                val isSelected = model.id == selectedModelId
                val card = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    // Selected card carries the accent-subtle wash (the CDS
                    // selected-state tint), unselected ones stay surface-2.
                    background = cardBackground(if (isSelected) cds.accentSubtle else cds.surface2)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener {
                        selectedModelId = model.id
                        renderModels()
                    }
                }

                // Radio dot — drawn, not a ◉/○ glyph (font-dependent).
                card.addView(android.view.View(this).apply {
                    val d = dp(14)
                    layoutParams = LinearLayout.LayoutParams(d, d).apply {
                        marginEnd = dp(10)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (isSelected) {
                            setColor(cds.accent)
                        } else {
                            setStroke(dp(1), cds.borderStrong)
                            setColor(0x00000000)
                        }
                    }
                })

                // Model info
                val info = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                nameRow.addView(TextView(this).apply {
                    text = model.displayName
                    CdsUi.styleContentText(this, cds, 14f, bold = isSelected)
                })
                // Recommended badge
                if (model.recommended) {
                    nameRow.addView(TextView(this).apply {
                        text = " ⚡"
                        textSize = 12f
                    })
                }
                info.addView(nameRow)

                // Price + tier
                info.addView(TextView(this).apply {
                    text = "${model.tier.stars} ${model.tier.label} · \$${model.inputPricePerM} / \$${model.outputPricePerM} / 每百万 token"
                    CdsUi.styleChromeText(this, cds, 11f)
                })
                card.addView(info)

                modelListLayout.addView(card)
            }
        }
        renderModels()

        // Provider tab switch — load per-provider saved API key
        fun switchProvider(provider: CloudProvider) {
            selectedProvider = provider
            selectedModelId = when {
                provider == CloudProvider.CUSTOM -> cloudSeed.modelName
                provider == cloudSeed.provider && provider.models.any { it.id == cloudSeed.modelName } -> cloudSeed.modelName
                else -> provider.models.firstOrNull()?.id ?: ""
            }
            updateTabStyles()
            renderModels()
            tvStatus.visibility = View.GONE
            val savedKey = KVUtils.getApiKeyForProvider(provider.name)
            etApiKey.setText(savedKey)
        }
        // Re-assign click listeners with the inner function
        tabViews.forEach { (provider, tab) ->
            tab.setOnClickListener { switchProvider(provider) }
        }

        // Test Connection
        btnTest.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "请先输入 API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "测试中…"
            tvStatus.setTextColor(cds.textMuted)

            executor.submit {
                try {
                    val baseUrl = if (selectedProvider == CloudProvider.CUSTOM) etBaseUrl.text.toString().trim()
                        else selectedProvider.defaultBaseUrl
                    val modelId = if (selectedProvider == CloudProvider.CUSTOM) etModelName.text.toString().trim()
                        else selectedModelId
                    // Quick test: just validate the key format
                    if (apiKey.length < 10) throw RuntimeException("API key too short")
                    if (modelId.isEmpty()) throw RuntimeException("未选择模型")
                    runOnUiThread {
                        tvStatus.text = "✓ 可以保存"
                        tvStatus.setTextColor(cds.accent)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvStatus.text = "✗ ${e.message}"
                        tvStatus.setTextColor(cds.danger)
                    }
                }
            }
        }

        // Save & Activate
        btnSave.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                Toast.makeText(this, "输入 API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isCustom = selectedProvider == CloudProvider.CUSTOM
            val baseUrl = if (isCustom) etBaseUrl.text.toString().trim() else selectedProvider.defaultBaseUrl
            val modelId = if (isCustom) etModelName.text.toString().trim() else selectedModelId

            if (modelId.isEmpty()) {
                Toast.makeText(this, "选择一个模型", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save & activate — the button says "Save & Activate", so honour it
            // even from local mode. The previous `!isLocalActive()` guard
            // silently degraded to save-as-default while local was active, and
            // no other door activates a configured cloud model (the default
            // cloud row is display-only), so a local user could configure
            // cloud and then never switch to it.
            ModelConfigRepository.saveCloudDefault(
                providerName = selectedProvider.name,
                modelId = modelId,
                baseUrl = baseUrl,
                apiKey = apiKey,
                activateNow = true
            )
            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.afterInit()
            // F6: encryption failure now means "not saved", so the success toast
            // has to be conditional or it would lie about the API key.
            if (KVUtils.consumeSecureWriteFailure() != null) {
                Toast.makeText(this, R.string.secure_write_failed, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "已保存并切换到云端模型：$modelId", Toast.LENGTH_SHORT).show()
            }
            finish()
        }

        // Active model card is already set in onCreate based on actual provider
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
