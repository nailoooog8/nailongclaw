# 汉化补丁状态（交接文档）

> 2026-10-07 更新。装机+权限+MCP e2e+MCP 管理 UI+**Skills 二期**（YAML recipe+捕获+管理 UI，已装机验证）全部完成（见下）。剩余硬编码英文串见下方「待办」第 1 条（2026-11-13 复核后重写）。
> MCP 测试服务器已入库：`scripts/mcp_test_server.py`（python 直接跑，端口 8765，工具 echo/server_uptime）。
> （原 `.tmp_scan_ui.py` 一次性扫描脚本未入库；重跑可用 `grep -rn '"[A-Z][a-zA-Z ]\{6,\}"' app/src/main/java/io/agents/nailongclaw/ui/`。）

## 已完成（2026-10-07 实测验证）

- **图标修复**：foreground/monochrome 两层 inset 56/72dp → 21dp，图标正常显示
- **装机**：构建 + `pm uninstall -k` 保数据 + shell 全新安装（受限设置标志随之清除），数据完整保留（mmkv/API Key 都在，无需重填）
- **无障碍**：`ClawAccessibilityService` 已绑定（capabilities=161，稳定不回弹）——注意 adb 全路线已证伪（settings put 秒回写、appops set 静默丢弃），必须 UI 手动拨开关
- **通知监听**：`ClawNotificationListener` 已启用
- **MCP e2e 全链路**：测试服务器(8765) → `adb reverse tcp:8765 tcp:8765` → `mcp_servers.json` 推到 `/sdcard/Android/data/io.agents.nailongclaw/files/` → 重启 app → logcat 实锤 `Registered 2 MCP tools from 'e2e' (registry size: 30)`；**agent 循环内实调 echo 工具成功**（服务器日志见 tools/call，云端走 OpenAI 格式 + 中转站 GLM/DeepSeek）——2026-10-07 全部实测通过，USB 隧道不依赖 WiFi

## 已完成（编译通过，APK 已产出）

- **LlmConfigActivity.kt**：31 处全部汉化 ✓（API Key 输入、模型选择、下载状态、云端连接状态、存储统计）
- **SettingsActivity.kt**：2026-10-24 已重写为纯导航枢纽（见 `BACKLOG.md`「第三轮」），原 24 项平铺菜单拆到 `ModelSettingsActivity` / `ChannelsActivity` / `MonitorActivity` / `ToolsActivity` / `AboutActivity`；当前文件无硬编码英文串。本条原有的「建议装机后目视复核」随页面重写作废。
- 另有少量多行对话框正文（reportBug 的 AlertDialog message）未汉化

## 待办（新会话接手）

1. 剩余硬编码英文串（2026-11-13 复核，原「13 处」清单已不准）：
   - `ui/chat/TaskFlowController.kt` 10 处（:187 Enable Accessibility Service… / :206-224 四条 accessibility 连接提示 / :251 is disconnected / :264 Configure LLM in Settings first）
   - `ui/chat/ComposeChatActivity.kt` 1 处（:191 Image upload coming soon）
   - `ui/settings/ChannelConfigActivity.kt` 3 处（:126/:134/:146 Bot Token）
   - `ui/chat/ChatScreen.kt` 与 `ui/settings/ThemeActivity.kt` **已无**硬编码串（原清单里的 4+1 处已清）
2. ~~MCP tools/call 实调验证~~ ✅ 2026-10-07 完成（agent 循环实调 echo 成功）
3. ~~MCP 服务器管理 UI~~ ✅ 2026-10-07 完成（`ui/settings/McpServersActivity`：设置页功能组入口，列表/添加/编辑/长按删除/启停，实时连接状态 StateFlow；`McpConnectionManager` 扩展 saveConfigs/connectOne/removeServer + 状态跟踪；`ToolRegistry` 加 unregister/unregisterByPrefix。装机实测：列表显示 "e2e · 2 tools"，编辑保存→落盘→重连→重注册全通。弹窗已迁到 CdsDialog（原系统 AlertDialog 白底问题随迁移消失））
4. ~~Skills 层二期（YAML recipe）~~ ✅ 2026-10-07 完成（见文末"Skills 二期"）

## Skills 二期（2026-10-07 本轮完成，已装机验证）

- **YAML recipe 技能**：`agent/skill/UserSkillLoader.kt`（SnakeYAML 2.2，新依赖）解析外部目录 `getExternalFilesDir(null)/skills/*.yaml`（与 mcp_servers.json 同目录策略）；启动加载（`ClawApplication`），`mcp_*` 工具允许后注册，其余未知工具整文件拒载；`Skill` 加 `source` 字段，`SkillRegistry` 加 `unregister`/`unregisterBySource`
- **捕获"保存为技能"**：`agent/skill/ToolCallRecorder.kt` 挂 `ToolRegistry.executeTool` 钩子，agent 循环（runAgentLoop 顶部 begin）记录工具调用，finish 成功/纯文本完成时 markSuccess 快照；生成 YAML 自动剔除 finish/get_screen_info/take_screenshot
- **管理 UI**：`ui/settings/UserSkillsActivity`（设置 → Tools → 自定义技能）：配方列表（点击编辑 YAML、长按删除）、从上次任务创建（无捕获时置灰）、手动新建（内置模板）、保存即注册生效
- **装机 e2e**（2026-10-07 实测）：`adb push scripts/sample_skill.yaml` → 重启 → logcat `User skills: 1 loaded, 0 error(s)`；管理页截图正常（配方"打开设置 · 2 步"）
- 格式文档 `docs/design/SKILL_FORMAT.md`，示例 `scripts/sample_skill.yaml`
- 未做（可选增强）：配方自动触发仅 PipelineTier2 正则（继承内置逻辑）；技能面板 Workflows 卡片由 `getUserFacing()` 自动带出，未单独截图验证；MCP 管理 UI 的「测试连接」按钮仍未做（`WebSearchActivity` 有一个不同实现的同名按钮可参考）。**工具来源标记已交付**——`ToolsActivity` 按 builtin / MCP / 技能 / 影子屏分组展示。
- 坑：Kotlin 块注释支持嵌套——KDoc 里写 `/skills/*.yaml` 的 `/*` 会开启嵌套注释吃掉整个文件（"Unclosed comment"），注释里写路径带 `*` 要注意

## 相关文件

- `docs/design/MCP_INTEGRATION_PLAN.md` — MCP/Skills 集成设计（已完成编码部分见此文档）
