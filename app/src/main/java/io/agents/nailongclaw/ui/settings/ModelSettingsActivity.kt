// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.agents.nailongclaw.R
import io.agents.nailongclaw.agent.EffortLevel
import io.agents.nailongclaw.agent.llm.ActiveModelMode
import io.agents.nailongclaw.agent.llm.ModelConfigRepository
import io.agents.nailongclaw.base.BaseActivity
import io.agents.nailongclaw.ui.cds.CdsUi
import io.agents.nailongclaw.utils.KVUtils
import io.agents.nailongclaw.utils.XLog
import io.agents.nailongclaw.widget.CommonToolbar
import io.agents.nailongclaw.widget.CdsDialog
import io.agents.nailongclaw.widget.CdsSegmented
import io.agents.nailongclaw.widget.MenuGroup

/**
 * 模型与任务 — model selection plus the two gears that used to be dead pills
 * on the composer: the effort gear and the sensitive-operation policy.
 *
 * Both gears are persisted in [KVUtils], which is the single source of truth
 * the Claude UI composer reads through the PokeClawChat bridge — so a change
 * here shows on the pill without a reload, and a change on the pill shows
 * here. The page is where you go to understand what the gears do; the pill is
 * where you go to flip them mid-conversation.
 *
 * The effort gear maps to [io.agents.nailongclaw.agent.AgentConfig.maxIterations]
 * — the agent loop's round budget. That is the honest mapping: there is no
 * separate "try harder" knob, and pretending otherwise would make the gear a
 * label with no behaviour.
 */
class ModelSettingsActivity : BaseActivity() {

    private lateinit var modelGroup: MenuGroup
    private lateinit var taskGroup: MenuGroup
    private lateinit var effortSegmented: CdsSegmented
    private lateinit var policySegmented: CdsSegmented

    private val cds by lazy { CdsUi.palette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(cds.surface1)
        setContentView(R.layout.activity_model_settings)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        CdsUi.styleToolbar(this, toolbar, cds, "模型与任务")
        toolbar.showBackButton(true) { finish() }

        modelGroup = findViewById(R.id.modelGroup)
        taskGroup = findViewById(R.id.taskGroup)
        modelGroup.applyCds(cds)
        taskGroup.applyCds(cds)

        // The two segmented cards share the panel treatment with the groups.
        listOf(R.id.effortCard, R.id.policyCard).forEach { id ->
            findViewById<LinearLayout>(id)?.background = CdsUi.panel(this, cds)
        }
        listOf(R.id.tvEffortLabel, R.id.tvPolicyLabel).forEach { id ->
            findViewById<TextView>(id)?.let { CdsUi.styleGroupLabel(it, cds) }
        }
        findViewById<TextView>(R.id.tvEffortHint).apply {
            CdsUi.styleChromeText(this, cds, 11f)
            text = "档位 = 单任务允许的工具调用轮数：低 15 · 中 30 · 高 60 · 最大 150。\n" +
                "调低更快更省，调高更顽固 —— 卡住的任务先降档，别急着重开。"
        }
        findViewById<TextView>(R.id.tvPolicyHint).apply {
            CdsUi.styleChromeText(this, cds, 11f)
            text = "每次询问：MCP 工具调用前弹确认卡（默认， fail-closed）。\n" +
                "自动允许：跳过确认卡直接执行 —— 仅在你完全信任已接入的 MCP 服务器时开启。"
        }
        findViewById<TextView>(R.id.tvFooter).apply {
            CdsUi.styleChromeText(this, cds, 11f)
            text = "模型凭据只保存在本机。全局提示词会注入每次任务的系统提示开头。"
        }

        effortSegmented = findViewById(R.id.effortSegmented)
        effortSegmented.setOptions(
            options = EffortLevel.entries.map { it.key to it.label },
            selectedKey = EffortLevel.current().key,
        ) { key -> onEffortSelected(key) }

        policySegmented = findViewById(R.id.policySegmented)
        policySegmented.setOptions(
            options = listOf(
                "ask" to "每次询问",
                "auto" to "自动允许",
            ),
            selectedKey = if (KVUtils.isAutoAllowOperations()) "auto" else "ask",
        ) { key -> onPolicySelected(key) }

        buildModelGroup()
        buildTaskGroup()
    }

    private fun buildModelGroup() {
        modelGroup.clearMenuItems()
        modelGroup.setTitle("模型")

        modelGroup.addMenuItem(
            leadingIcon = R.drawable.icon_current_model,
            title = "模型选择",
            onClick = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(activeModelLabel())
            setTrailingTextColor(cds.textSecondary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }

        modelGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_share,
            title = "自定义本地模型 URL",
            onClick = { showCustomModelUrlDialog() },
            showDivider = false
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(
                if (KVUtils.getCustomLocalModelUrl().isBlank()) "未设置" else "已设置"
            )
            setTrailingTextColor(cds.textSecondary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }
    }

    private fun buildTaskGroup() {
        taskGroup.clearMenuItems()
        taskGroup.setTitle("任务")

        taskGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_recent_history,
            title = "任务预算",
            onClick = { showBudgetDialog() },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(io.agents.nailongclaw.agent.TaskBudget.describeCurrentBudget())
            setTrailingTextColor(cds.textSecondary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }

        taskGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_edit,
            title = "全局提示词",
            onClick = { showGlobalPromptDialog() },
            showDivider = false
        ).apply {
            setTitleColor(cds.textPrimary)
            val current = KVUtils.getGlobalPrompt()
            setTrailingText(
                if (current.isBlank()) getString(R.string.global_prompt_not_set)
                else getString(R.string.global_prompt_set_status, current.length)
            )
            setTrailingTextColor(cds.textSecondary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }
    }

    private fun activeModelLabel(): String {
        val config = ModelConfigRepository.snapshot()
        return if (config.activeMode == ActiveModelMode.LOCAL) {
            config.local.displayName.ifBlank { "未选择" }
        } else {
            config.activeCloud.modelName.ifBlank { "未选择" }
        }
    }

    private fun onEffortSelected(key: String) {
        val level = EffortLevel.fromKey(key)
        KVUtils.setEffortLevel(level.key)
        XLog.i("ModelSettings", "effort gear -> ${level.key} (maxIterations=${level.maxIterations})")
        Toast.makeText(this, "努力档位：${level.label}", Toast.LENGTH_SHORT).show()
        // The orchestrator reads the gear per task, so nothing to reload here;
        // the composer pill picks the new value through the bridge on resume.
    }

    private fun onPolicySelected(key: String) {
        val auto = key == "auto"
        KVUtils.setAutoAllowOperations(auto)
        XLog.i("ModelSettings", "operation policy -> ${if (auto) "auto-allow" else "ask"}")
        Toast.makeText(
            this,
            if (auto) "MCP 工具将直接执行，不再询问" else "MCP 工具调用前会再次询问",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showBudgetDialog() {
        val currentTokens = io.agents.nailongclaw.agent.TaskBudget.getConfiguredMaxTokens()
        val currentCost = io.agents.nailongclaw.agent.TaskBudget.getConfiguredMaxCost()

        val layout = CdsDialog.column(this)
        layout.addView(CdsDialog.label(this, "单任务最大 token 数"))

        // Radio rows instead of the stock Spinner the dialog used to drop in:
        // seven options with the current one marked, the mockup's pop-item
        // shape (accent dot + ghost wash on the selected row).
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(options)
        var selectedTokens = currentTokens

        fun renderTokenOptions() {
            options.removeAllViews()
            listOf(
                "不设上限" to null,
                "10K" to 10_000,
                "50K" to 50_000,
                "100K" to 100_000,
                "200K" to 200_000,
                "250K" to 250_000,
                "500K" to 500_000,
            ).forEach { (label, value) ->
                options.addView(
                    CdsDialog.optionRow(
                        context = this,
                        label = label,
                        selected = value == selectedTokens,
                    ) {
                        selectedTokens = value
                        renderTokenOptions()
                    }
                )
            }
        }
        renderTokenOptions()

        layout.addView(CdsDialog.label(this, "单任务成本上限（美元）"))
        val costInput = CdsDialog.field(
            context = this,
            hint = "留空 = 不设成本上限",
            preset = currentCost?.let { String.format("%.2f", it) } ?: "",
        ).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(costInput)

        CdsDialog.show(
            context = this,
            title = "任务预算",
            content = layout,
            confirmText = getString(R.string.common_save),
            cancelText = getString(R.string.common_cancel),
            onConfirm = {
                val tokens = selectedTokens
                val newCost = costInput.text.toString().trim().toDoubleOrNull()
                when (tokens) {
                    null -> io.agents.nailongclaw.agent.TaskBudget.clearMaxTokens()
                    else -> io.agents.nailongclaw.agent.TaskBudget.saveMaxTokens(tokens)
                }
                when {
                    newCost == null || newCost <= 0.0 -> io.agents.nailongclaw.agent.TaskBudget.clearMaxCost()
                    else -> io.agents.nailongclaw.agent.TaskBudget.saveMaxCost(newCost)
                }
                Toast.makeText(
                    this,
                    "预算：${io.agents.nailongclaw.agent.TaskBudget.describeCurrentBudget()}",
                    Toast.LENGTH_SHORT
                ).show()
                buildTaskGroup()
            }
        )
    }

    private fun showGlobalPromptDialog() {
        val current = KVUtils.getGlobalPrompt()
        XLog.i("ModelSettings", "open global prompt dialog: current.len=${current.length}")

        val layout = CdsDialog.column(this)
        val input = CdsDialog.field(
            context = this,
            hint = getString(R.string.global_prompt_hint),
            preset = current,
            multiLine = true,
        ).apply {
            minLines = 4
            filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
        }
        layout.addView(input)

        CdsDialog.show(
            context = this,
            title = getString(R.string.global_prompt_dialog_title),
            content = layout,
            confirmText = getString(R.string.common_save),
            cancelText = getString(R.string.common_cancel),
            onConfirm = {
                val text = input.text.toString().trim()
                KVUtils.setGlobalPrompt(text)
                XLog.i("ModelSettings", "global prompt saved: new.len=${text.length}")
                buildTaskGroup()
            }
        )
    }

    private fun showCustomModelUrlDialog() {
        val current = KVUtils.getCustomLocalModelUrl()

        val layout = CdsDialog.column(this)
        val input = CdsDialog.field(
            context = this,
            hint = getString(R.string.custom_local_model_url_hint),
            preset = current,
        )
        layout.addView(input)

        CdsDialog.show(
            context = this,
            title = getString(R.string.custom_local_model_url_dialog_title),
            content = layout,
            confirmText = getString(R.string.common_save),
            cancelText = getString(R.string.common_cancel),
            // Validation vetoes the dismissal: the builder callback fires
            // after the dialog is gone, so a rejected URL would throw away
            // everything typed. Confirm here, dismiss through the handle.
            dismissOnConfirm = false,
            onConfirm = { handle ->
                val raw = input.text.toString().trim()
                val normalized = when {
                    raw.startsWith("HTTPS://") -> "https://" + raw.substring(8)
                    raw.startsWith("HTTP://") -> "http://" + raw.substring(7)
                    else -> raw
                }
                val lower = normalized.lowercase()
                val valid = lower.isEmpty() || lower.startsWith("http://") || lower.startsWith("https://")
                if (valid) {
                    KVUtils.setCustomLocalModelUrl(normalized)
                    XLog.i("ModelSettings", "custom local model url saved: new.len=${normalized.length}")
                    handle.dismiss()
                    buildModelGroup()
                } else {
                    Toast.makeText(
                        this,
                        R.string.custom_local_model_url_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}
