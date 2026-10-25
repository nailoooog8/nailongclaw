// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.skill.SkillRegistry
import io.agents.pokeclaw.agent.skill.ToolCallRecorder
import io.agents.pokeclaw.agent.skill.UserSkillLoader
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.cds.CdsUi
import io.agents.pokeclaw.widget.CdsDialog
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.MenuGroup
import java.io.File

/**
 * User skill manager: lists YAML skill recipes, lets the user create new ones
 * from the last captured agent task, edit the YAML by hand, and delete them.
 *
 * Persisted as *.yaml files in the app's external files dir (/skills/), same
 * location strategy as mcp_servers.json — adb push workflows still work.
 */
class UserSkillsActivity : BaseActivity() {

    private lateinit var skillsGroup: MenuGroup
    private var files: List<File> = emptyList()

    /** CDS palette (follows system night mode), shared by all render passes. */
    private val cds by lazy { io.agents.pokeclaw.ui.cds.CdsUi.palette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.setBackgroundColor(cds.surface1)

        setContentView(R.layout.activity_user_skills)

        val contentFrame = findViewById<android.view.ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? android.view.View)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        io.agents.pokeclaw.ui.cds.CdsUi.styleToolbar(this, toolbar, cds, "自定义技能")
        toolbar.showBackButton(true) { finish() }

        skillsGroup = findViewById(R.id.skillsGroup)
        skillsGroup.applyCds(cds)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        files = UserSkillLoader.listFiles(this)
        renderItems()
    }

    private fun renderItems() {
        skillsGroup.clearMenuItems()
        skillsGroup.setTitle("技能配方（${files.size}）")

        for (file in files) {
            val skill = try {
                UserSkillLoader.parse(file.readText())
            } catch (_: Exception) {
                null
            }
            val title = skill?.name ?: file.name
            val trailing = when {
                skill == null -> "解析失败"
                else -> "${skill.steps.size} 步"
            }
            skillsGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_agenda,
                title = title,
                onClick = { showEditDialog(file) },
                showDivider = true
            ).apply {
                setTitle(title)
                setTrailingText(trailing)
                setTitleColor(if (skill == null) cds.textMuted else cds.textPrimary)
                setTrailingTextColor(if (skill == null) cds.danger else cds.textSecondary)
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
                setOnLongClickListener {
                    showDeleteDialog(file, title)
                    true
                }
            }
        }

        // Create from last captured task
        val capture = ToolCallRecorder.lastCapture
        val captureTitle = if (capture == null) "＋ 从上次任务创建（暂无捕获）" else "＋ 从上次任务创建"
        skillsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_camera,
            title = captureTitle,
            onClick = {
                if (capture == null) {
                    Toast.makeText(this, "还没有捕获：完成一个任务后再来（agent 循环执行成功后会记录工具序列）", Toast.LENGTH_LONG).show()
                } else {
                    showCreateFromCaptureDialog(capture)
                }
            },
            showDivider = false
        ).apply {
            setTitleColor(if (capture == null) cds.textMuted else cds.accent)
            setLeadingIconColor(if (capture == null) cds.textMuted else cds.accent)
            setShowTrailingIcon(false)
        }

        // Manual blank recipe
        skillsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_add,
            title = "＋ 手动新建配方",
            onClick = { showEditDialog(null) },
            showDivider = false
        ).apply {
            setTitleColor(cds.accent)
            setLeadingIconColor(cds.accent)
            setShowTrailingIcon(false)
        }

        findViewById<TextView>(R.id.tvCaptureHint)?.apply {
            text = "技能配方存于应用外部目录 /skills/（*.yaml），与 adb 推送方式兼容，重启后自动加载。\n" +
                "点击配方可查看/编辑 YAML，长按删除。\n" +
                "配方引用的工具须在工具注册表中（mcp_* 远程工具允许后注册）。"
            CdsUi.styleChromeText(this, cds, 11f)
        }
    }

    private fun showDeleteDialog(file: File, title: String) {
        CdsDialog.show(
            context = this,
            title = "删除技能？",
            content = CdsDialog.message(
                this,
                "将删除 \"${file.name}\"（$title），下次启动不再加载。"
            ),
            confirmText = "删除",
            cancelText = getString(R.string.common_cancel),
            danger = true,
            onConfirm = {
                val ok = UserSkillLoader.delete(this, file)
                if (!ok) Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                reload()
            }
        )
    }

    /** Create-from-capture: ask for id/name, then open the YAML editor prefilled. */
    private fun showCreateFromCaptureDialog(capture: ToolCallRecorder.Capture) {
        // CDS dialog (CdsDialog): same frame as the page that opened it.
        val layout = CdsDialog.column(this)

        val suggestedId = "captured_" + (System.currentTimeMillis() / 1000)
        layout.addView(CdsDialog.label(this, "技能 ID（文件名，英文/数字/下划线）"))
        val idInput = CdsDialog.field(this, hint = "my_skill", preset = suggestedId)
        layout.addView(idInput)

        layout.addView(CdsDialog.label(this, "技能名称"))
        val nameInput = CdsDialog.field(this, hint = "我的技能", preset = capture.task.take(20))
        layout.addView(nameInput)

        layout.addView(CdsDialog.label(this, "描述（可留空）"))
        val descInput = CdsDialog.field(this, hint = "", preset = "")
        layout.addView(descInput)

        CdsDialog.show(
            context = this,
            title = "从上次任务创建技能",
            content = layout,
            confirmText = "生成配方",
            cancelText = "取消",
            dismissOnConfirm = false,
            onConfirm = { handle ->
            // Validate on click: a rejected ID in the builder callback fired
            // after dismissal and discarded the name and description already typed.
            val id = idInput.text.toString().trim()
            val name = nameInput.text.toString().trim()
            if (id.isEmpty() || !id.matches(Regex("[a-zA-Z0-9_]+"))) {
                Toast.makeText(this, "ID 只能含英文/数字/下划线", Toast.LENGTH_SHORT).show()
                return@show
            }
            if (name.isEmpty()) {
                Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                return@show
            }
            val yaml = UserSkillLoader.captureToYaml(
                capture, id, name, descInput.text.toString().trim()
            )
            if (yaml.isEmpty()) {
                Toast.makeText(this, "捕获中没有可用的动作步骤（只截屏/finish 的任务无法成技能）", Toast.LENGTH_LONG).show()
                return@show
            }
            handle.dismiss()
            showEditor(id, yaml, isNew = true)
        }
    )
    }

    /** YAML editor: new file (null) / existing file / prefilled capture text. */
    private fun showEditDialog(file: File?) {
        val presetId = file?.nameWithoutExtension ?: ""
        val presetYaml = try {
            file?.readText() ?: TEMPLATE_YAML
        } catch (_: Exception) {
            TEMPLATE_YAML
        }
        showEditor(presetId, presetYaml, isNew = file == null, editingFile = file)
    }

    private fun showEditor(
        presetId: String,
        presetYaml: String,
        isNew: Boolean,
        editingFile: File? = null
    ) {
        val layout = CdsDialog.column(this)

        layout.addView(CdsDialog.label(this, "技能 ID"))
        val idInput = CdsDialog.field(this, hint = "", preset = presetId).apply {
            // Editing an existing file: id fixed to the filename
            isEnabled = isNew || editingFile == null
        }
        layout.addView(idInput)

        layout.addView(CdsDialog.label(this, "YAML 配方"))
        val yamlInput = CdsDialog.field(this, hint = "", preset = presetYaml, multiLine = true).apply {
            minLines = 8
            maxLines = 20
            typeface = CdsUi.fontMono()
            textSize = 12f
        }
        layout.addView(yamlInput)

        CdsDialog.show(
            context = this,
            title = if (isNew) "保存技能配方" else "编辑配方",
            content = layout,
            confirmText = "保存",
            cancelText = "取消",
            dismissOnConfirm = false,
            onConfirm = { handle ->
            // Validate on click: a YAML error in the builder callback fired
            // after dismissal and discarded the whole recipe the user typed.
            val id = idInput.text.toString().trim()
            val yaml = yamlInput.text.toString()
            if (id.isEmpty() || !id.matches(Regex("[a-zA-Z0-9_]+"))) {
                Toast.makeText(this, "ID 只能含英文/数字/下划线", Toast.LENGTH_SHORT).show()
                return@show
            }
            val parsed = try {
                UserSkillLoader.parse(yaml)
            } catch (e: Exception) {
                null
            }
            if (parsed == null) {
                Toast.makeText(this, "YAML 解析失败或引用未知工具，未保存", Toast.LENGTH_LONG).show()
                return@show
            }
            if (parsed.id != id) {
                Toast.makeText(this, "YAML 里的 id（${parsed.id}）须与上方 ID 一致", Toast.LENGTH_LONG).show()
                return@show
            }
            // Renaming an existing file: remove the old one
            if (editingFile != null && editingFile.name != "$id.yaml") {
                editingFile.delete()
            }
            val saved = UserSkillLoader.save(this, id, yaml)
            if (saved == null) {
                Toast.makeText(this, "写入失败", Toast.LENGTH_SHORT).show()
                return@show
            }
            SkillRegistry.register(parsed)
            Toast.makeText(this, "已保存并启用：${parsed.name}（${parsed.steps.size} 步）", Toast.LENGTH_SHORT).show()
            handle.dismiss()
            reload()
        }
    )
    }

    private companion object {
        const val TEMPLATE_YAML = """id: my_skill
name: 我的技能
description: 这个技能做什么
category: GENERAL
user_facing: true
estimated_steps_saved: 3
triggers: []
parameters: []
steps:
  - tool: open_app
    description: 打开应用
    optional: false
    retries: 1
    params:
      app_name: "设置"
  - tool: wait
    description: 等待加载
    optional: false
    retries: 1
    params:
      duration_ms: "2000"
fallback_goal: 完成上述操作
"""
    }
}
