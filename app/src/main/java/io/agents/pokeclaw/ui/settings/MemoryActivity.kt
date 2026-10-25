// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.memory.MemoryEntry
import io.agents.pokeclaw.agent.memory.MemoryKind
import io.agents.pokeclaw.agent.memory.MemoryRetention
import io.agents.pokeclaw.agent.memory.MemorySimilarity
import io.agents.pokeclaw.agent.memory.MemoryStore
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.ui.cds.CdsUi
import io.agents.pokeclaw.widget.CdsDialog
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.MenuGroup

/**
 * Memory bank manager: browse, search, edit, pin and delete what the agent
 * remembers between sessions.
 *
 * The agent writes here by itself (memory_save), so this screen is mostly for
 * review and correction — the tool a user reaches for when a memory is wrong,
 * too broad, or missing something they would rather state once.
 */
class MemoryActivity : BaseActivity() {

    private lateinit var memoryGroup: MenuGroup
    private lateinit var actionsGroup: MenuGroup
    private var searchQuery: String = ""

    /** CDS palette (follows system night mode), shared by all render passes. */
    private val cds by lazy { io.agents.pokeclaw.ui.cds.CdsUi.palette(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MemoryStore.init(this)

        window.decorView.setBackgroundColor(cds.surface1)

        setContentView(R.layout.activity_memory)

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        contentFrame?.setBackgroundColor(cds.surface1)
        (contentFrame?.getChildAt(0) as? ViewGroup)?.setBackgroundColor(cds.surface1)

        val toolbar = findViewById<CommonToolbar>(R.id.toolbar)
        io.agents.pokeclaw.ui.cds.CdsUi.styleToolbar(this, toolbar, cds, "记忆库")
        toolbar.showBackButton(true) { finish() }

        findViewById<EditText>(R.id.etSearch).apply {
            io.agents.pokeclaw.ui.cds.CdsUi.styleEditText(this, cds)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString().orEmpty().trim()
                    renderMemories()
                }
            })
        }

        memoryGroup = findViewById(R.id.memoryGroup)
        memoryGroup.applyCds(cds)

        actionsGroup = findViewById(R.id.actionsGroup)
        actionsGroup.applyCds(cds)

        findViewById<TextView>(R.id.tvFooter).apply {
            CdsUi.styleChromeText(this, cds, 11f)
        }
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    private fun renderAll() {
        renderMemories()
        renderActions()
    }

    // ── Memory list ───────────────────────────────────────────────────────────

    private fun filtered(): List<MemoryEntry> {
        val all = MemoryStore.all()
        if (searchQuery.isEmpty()) return all
        val q = searchQuery.lowercase()
        return all.filter {
            it.content.lowercase().contains(q) ||
                it.kindLabel.contains(searchQuery) ||
                it.tags.any { tag -> tag.contains(q) }
        }
    }

    private fun renderMemories() {
        memoryGroup.clearMenuItems()

        val entries = filtered()
        memoryGroup.setTitle("记忆（${entries.size}）")

        if (entries.isEmpty()) {
            memoryGroup.addMenuItem(
                leadingIcon = android.R.drawable.ic_menu_info_details,
                title = if (searchQuery.isEmpty()) "还没有记忆。对话中值得长期记住的事会被自动记下来。" else "没有匹配的记忆",
                onClick = {},
                showDivider = false
            ).apply {
                setTitleColor(cds.textMuted)
                setLeadingIconColor(cds.textMuted)
                setShowTrailingIcon(false)
            }
            return
        }

        val now = System.currentTimeMillis()
        for (entry in entries) {
            val conflicted = entry.conflictsWith.isNotEmpty()
            memoryGroup.addMenuItem(
                leadingIcon = leadingIconFor(entry),
                title = (if (entry.pinned) "📌 " else "") + (if (conflicted) "⚠ " else "") + entry.content,
                onClick = { showEditDialog(entry) },
                showDivider = true
            ).apply {
                setTitleColor(cds.textPrimary)
                setTrailingText(
                    buildString {
                        append(entry.kindLabel)
                        if (conflicted) append(" · 矛盾")
                        if (entry.hits > 0) append(" · ${entry.hits}次")
                        // Only the fading tail of the retention curve is worth
                        // showing; tagging every steady entry would be noise.
                        if (MemoryRetention.isFading(entry, now)) {
                            append(" · ${MemoryRetention.labelOf(entry, now)}")
                        }
                    }
                )
                setTrailingTextColor(if (conflicted) cds.danger else cds.textSecondary)
                setLeadingIconColor(cds.textPrimary)
                setTrailingIconColor(cds.textMuted)
                setOnLongClickListener {
                    showDeleteDialog(entry)
                    true
                }
            }
        }

        findViewById<TextView>(R.id.tvFooter).text = footerText()
    }

    private fun leadingIconFor(entry: MemoryEntry): Int = when (entry.kind) {
        MemoryKind.PROFILE -> android.R.drawable.ic_menu_myplaces
        MemoryKind.PREFERENCE -> android.R.drawable.ic_menu_preferences
        MemoryKind.EVENT -> android.R.drawable.ic_menu_recent_history
        else -> android.R.drawable.ic_menu_agenda
    }

    private fun footerText(): String {
        val total = MemoryStore.count()
        val all = MemoryStore.all()
        val now = System.currentTimeMillis()
        val pinned = all.count { it.pinned }
        val conflicted = all.count { it.conflictsWith.isNotEmpty() }
        val suppressed = MemoryStore.suppressionCount()
        // Reported so the fading marks above read as a working curve rather
        // than entries quietly going missing.
        val fading = all.count { MemoryRetention.isFading(it, now) }
        val state = if (MemoryStore.isEnabled()) "已开启" else "已关闭"
        return "记忆库$state · 共 $total 条（置顶 $pinned 条" +
            (if (conflicted > 0) " · 矛盾 $conflicted 条" else "") +
            (if (fading > 0) " · 渐淡 $fading 条" else "") +
            "）· 上限 ${MemoryStore.MAX_ENTRIES} 条\n" +
            "存储于应用外部目录 memories.json，可直接用 adb 推送编辑。\n" +
            "长期没被召回的记忆会慢慢淡出简报，被召回得越多淡得越慢；只淡出，不会自动删除。\n" +
            (if (suppressed > 0) "删除过的 $suppressed 条记忆留有抑制指纹，Agent 不会自动写回相似内容。\n" else "") +
            "点击编辑，长按删除。"
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun renderActions() {
        actionsGroup.clearMenuItems()
        actionsGroup.setTitle("管理")

        val enabled = MemoryStore.isEnabled()
        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_manage,
            title = "记忆库总开关",
            onClick = {
                MemoryStore.setEnabled(!enabled)
                Toast.makeText(
                    this,
                    if (enabled) "已关闭：不再向模型注入记忆" else "已开启",
                    Toast.LENGTH_SHORT
                ).show()
                renderAll()
            },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setTrailingText(if (enabled) "开启" else "关闭")
            setTrailingTextColor(if (enabled) cds.accent else cds.textMuted)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }

        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_add,
            title = "＋ 添加记忆",
            onClick = { showEditDialog(null) },
            showDivider = true
        ).apply {
            setTitleColor(cds.accent)
            setLeadingIconColor(cds.accent)
            setTrailingIconColor(cds.textMuted)
        }

        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_save,
            // Says "分享" because that is what it now does. The old title
            // promised an export and delivered a Toast with a filesystem path
            // the user cannot reach, which made the export useless.
            title = "导出并分享",
            onClick = { exportAndShare() },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }

        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_delete,
            title = "清空记忆库",
            onClick = { showClearDialog() },
            showDivider = true
        ).apply {
            setTitleColor(cds.textPrimary)
            setLeadingIconColor(cds.danger)
            setTrailingIconColor(cds.textMuted)
        }

        // Forgetting leaves a fingerprint so the agent cannot re-learn the fact.
        // This is the way back if a deletion was a mistake.
        val suppressed = MemoryStore.suppressionCount()
        actionsGroup.addMenuItem(
            leadingIcon = android.R.drawable.ic_menu_close_clear_cancel,
            title = "遗忘抑制（$suppressed）",
            onClick = { showSuppressionListDialog() },
            showDivider = false
        ).apply {
            setTitleColor(cds.textPrimary)
            setLeadingIconColor(cds.textPrimary)
            setTrailingIconColor(cds.textMuted)
        }
    }

    /**
     * Exports the bank and hands it to the system share sheet.
     *
     * The export used to end at a Toast printing the path, which made it
     * impossible to actually get the file off the phone — the whole point of
     * exporting a memory bank. Sharing also needs a FileProvider root that
     * covers the storage dir; see `res/xml/file_paths.xml`.
     */
    private fun exportAndShare() {
        val file = MemoryStore.exportMarkdown()
        if (file == null) {
            Toast.makeText(this, "没有可导出的记忆", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            // Means file_paths.xml no longer covers MemoryStore.storageDir().
            // Loud rather than silent: the export file exists but is unreachable.
            android.util.Log.e("MemoryActivity", "export file not shareable: ${e.message}", e)
            Toast.makeText(this, "导出成功，但无法分享：$e", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "PokeClaw 记忆库导出")
            putExtra(Intent.EXTRA_TEXT, "PokeClaw 记忆库导出，共 ${MemoryStore.count()} 条。")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "分享记忆库导出"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可分享的应用", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The fingerprints themselves, not just a count.
     *
     * A number tells you nothing about what is currently blocked, so a
     * suppression that is working looks identical to one that is silently
     * eating a legitimate memory. Listing the text makes it checkable, and
     * gives a per-item way back out — deleting the wrong thing should not cost
     * you the whole suppression bank.
     */
    private fun showSuppressionListDialog() {
        val traces = MemoryStore.suppressions()
        if (traces.isEmpty()) {
            Toast.makeText(this, "当前没有抑制记录", Toast.LENGTH_SHORT).show()
            return
        }

        val layout = CdsDialog.column(this)
        layout.addView(
            CdsDialog.message(this, "这些内容已被删除，Agent 不会自动写回相似内容。")
        )
        traces.forEach { t ->
            val blocked = if (t.blocked > 0) "挡过 ${t.blocked} 次" else null
            layout.addView(
                CdsDialog.optionRow(
                    context = this,
                    label = t.text.take(40),
                    tag = listOfNotNull(MemoryKind.labelOf(t.kind), blocked).joinToString(" · "),
                ) { showSuppressionItemDialog(t) }
            )
        }

        CdsDialog.show(
            context = this,
            title = "遗忘抑制（${traces.size}）",
            content = layout,
            confirmText = null,
            cancelText = getString(R.string.common_cancel),
            neutralText = "全部清除",
            onNeutral = { showClearSuppressionDialog() },
        )
    }

    private fun showSuppressionItemDialog(trace: MemorySimilarity.Trace) {
        val blocked = if (trace.blocked > 0) "\n已挡下 ${trace.blocked} 次 Agent 写回。" else ""
        CdsDialog.show(
            context = this,
            title = trace.text,
            content = CdsDialog.message(
                this,
                "分类：${MemoryKind.labelOf(trace.kind)}$blocked\n\n" +
                    "「恢复记忆」把这条放回记忆库并解除抑制；「仅解除抑制」只让 Agent 可以重新学到，" +
                    "记忆库本身不变。"
            ),
            confirmText = "恢复记忆",
            cancelText = getString(R.string.common_cancel),
            neutralText = "仅解除抑制",
            onConfirm = {
                MemoryStore.restoreSuppression(trace.id)
                Toast.makeText(this, "已恢复这条记忆", Toast.LENGTH_SHORT).show()
                renderAll()
                showSuppressionListDialog()
            },
            onNeutral = {
                MemoryStore.clearSuppression(trace.id)
                Toast.makeText(this, "已解除这条抑制", Toast.LENGTH_SHORT).show()
                renderAll()
                showSuppressionListDialog()
            }
        )
    }

    private fun showClearSuppressionDialog() {
        val n = MemoryStore.suppressionCount()
        if (n == 0) {
            Toast.makeText(this, "当前没有抑制记录", Toast.LENGTH_SHORT).show()
            return
        }
        CdsDialog.show(
            context = this,
            title = "清除遗忘抑制？",
            content = CdsDialog.message(
                this,
                "将清除 $n 条抑制指纹。清除后，Agent 可以重新自动记住你之前删掉的内容。"
            ),
            confirmText = "清除",
            cancelText = getString(R.string.common_cancel),
            danger = true,
            onConfirm = {
                val cleared = MemoryStore.clearSuppressions()
                Toast.makeText(this, "已清除 $cleared 条抑制指纹", Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    private fun showDeleteDialog(entry: MemoryEntry) {
        CdsDialog.show(
            context = this,
            title = "删除这条记忆？",
            content = CdsDialog.message(
                this,
                entry.content +
                    "\n\n删除后会留下抑制指纹，Agent 之后不会自动写回相似内容" +
                    "（可在「遗忘抑制」里清除）。"
            ),
            confirmText = "删除",
            cancelText = getString(R.string.common_cancel),
            danger = true,
            onConfirm = {
                MemoryStore.delete(entry.id)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    private fun showClearDialog() {
        CdsDialog.show(
            context = this,
            title = "清空记忆库？",
            content = CdsDialog.message(
                this,
                "将删除全部 ${MemoryStore.count()} 条记忆，Agent 会从零开始重新认识你。"
            ),
            confirmText = "清空",
            cancelText = getString(R.string.common_cancel),
            danger = true,
            onConfirm = {
                val n = MemoryStore.clear()
                Toast.makeText(this, "已清空 $n 条", Toast.LENGTH_SHORT).show()
                renderAll()
            }
        )
    }

    private fun showEditDialog(existing: MemoryEntry?) {
        // CDS dialog (CdsDialog) — same frame as the page that opened it. The
        // old version hardcoded #333333 field text because the system dialog
        // is light-themed; that hack is gone with the frame that needed it.
        val layout = CdsDialog.column(this)

        layout.addView(CdsDialog.label(this, "记忆内容（一条一个事实，写成能独立成立的短句）"))
        val contentInput = CdsDialog.field(
            this,
            hint = "例：用户的通勤方式是地铁，单程约 40 分钟",
            preset = existing?.content,
            multiLine = true,
        )
        layout.addView(contentInput)

        layout.addView(CdsDialog.label(this, "分类"))
        val kindGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val kindButtons = HashMap<String, RadioButton>()
        for (kind in MemoryKind.all) {
            val button = RadioButton(this).apply {
                text = MemoryKind.labelOf(kind)
                isChecked = (existing?.kind ?: MemoryKind.FACT) == kind
                setTextColor(cds.textPrimary)
                typeface = CdsUi.fontSans()
                textSize = 14f
            }
            kindButtons[kind] = button
            kindGroup.addView(button)
        }
        layout.addView(kindGroup)

        layout.addView(CdsDialog.label(this, "标签（逗号分隔，可选）"))
        val tagsInput = CdsDialog.field(this, hint = "device,permissions", preset = existing?.tags?.joinToString(","))
        layout.addView(tagsInput)

        val pinnedCheck = CheckBox(this).apply {
            text = "置顶（每次都注入，不参与衰减）"
            isChecked = existing?.pinned ?: false
            setTextColor(cds.textPrimary)
            typeface = CdsUi.fontSans()
        }
        layout.addView(pinnedCheck)

        CdsDialog.show(
            context = this,
            title = if (existing == null) "添加记忆" else "编辑记忆",
            content = layout,
            confirmText = "保存",
            cancelText = "取消",
            dismissOnConfirm = false,
            onConfirm = { handle ->
            // Validate on click, not in the builder callback: the builder
            // callback runs after the dialog is dismissed, so rejecting empty
            // content there discarded everything the user had typed.
            val content = contentInput.text.toString().trim()
            if (content.isEmpty()) {
                Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show()
                return@show
            }
            val kind = kindButtons.entries.firstOrNull { it.value.isChecked }?.key
                ?: MemoryKind.FACT
            val tags = tagsInput.text.toString()
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val saved = MemoryStore.remember(
                content = content,
                kind = kind,
                tags = tags,
                pinned = pinnedCheck.isChecked,
                id = existing?.id
            )
            val message = when (saved) {
                is MemoryStore.RememberResult.Suppressed ->
                    "与被删除过的记忆太像，已作为手动添加保存"

                is MemoryStore.RememberResult.Stored -> when {
                    saved.merged -> "已合并到另一条记忆（共 ${MemoryStore.count()} 条）"
                    saved.conflicts.isNotEmpty() ->
                        "已保存，但与 ${saved.conflicts.size} 条已有记忆矛盾（列表里有 ⚠ 标记）"

                    else -> "已保存，共 ${MemoryStore.count()} 条"
                }
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            renderAll()
            handle.dismiss()
        }
    )
    }
}