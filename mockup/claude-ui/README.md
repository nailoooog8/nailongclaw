# Claude UI 表面（PokeClaw）

这是应用的**主界面**（Claude UI 设计语言）—— 2026-10-24 起由 Splash 直接落地
（此前是经设置进入的第二聊天界面，`ComposeChatActivity` 退居外部 `task` 契约接收方）。

- **接真实后端** —— 由 `WebChatSink` 实现 `ChatSink`，驱动的是与旗舰聊天页
  同一个 `TaskFlowController`。composer 经 `PokeClawChat.send` 回调 Kotlin，
  消息经 `evaluateJavascript` 推回页面。
- 同一份 HTML 在纯浏览器里打开时**仍然是完整设计稿** —— `cds.js` 检测不到
  桥就退回本地模拟，并把静态控件与示例 turn 全部显示出来；检测到桥
  （`enterLiveMode`）则清空示例 turn，只走真实数据。

> ⚠️ **本目录确实会进 APK**，通过 `app/build.gradle.kts` 的 `syncClaudeUiMockup`
> 任务在 `preBuild` 阶段同步到 `build/generated/claudeUiAssets/claude-ui/`，
> 再由 `assets.srcDir` 挂进打包。只有 `index.html` / `cds.css` / `cds.js`
> 三个运行时文件同步；9 张 `preview-*.png`（约 740 KB）不参与。
> 验证方式：**解包 APK 看实际路径**，不要只看 `build/` 里的中间产物 ——
> 中间目录正确不代表最终 APK 正确。
>
> 入口：设置 → 关于 →「Claude UI 聊天」。曾是「P1 发布前必删」的临时入口，
> 现在是有产品功能的入口，故那条判据已作废（见 `BACKLOG.md`）。
>
> **三类示例 turn 都已接真实数据**：**工具卡片**（2026-10-19，QA H23，2026-10-22
> 修复历史恢复后丢失，QA H26 —— 「生产侧从未被赋值」是过时结论）；**MCP 确认卡**
> （2026-10-24，QA H27：`mcp_*` 工具执行前停在界面上等人点头，`PokeClawChat.decideMcp`
> 把决定送回 Kotlin，泊在 `ToolRegistry.executeTool` 里的 agent 线程才继续）；
> **记忆 chips**（2026-10-24）：`memory_recall` 的结果文本由 `MemoryRecallFormat`
> 解析成 `#编号 + 内容`，随工具步骤的 `memories` 字段进页面渲染 `.chips`，
> 恢复历史时从落盘的 summary 文本重新解析，chips 不丢。
>
> 入口随之在 2026-10-24 二次改名：「Claude UI 实验版」→「**Claude UI 聊天**」——
> 「实验版」的依据（三类卡全是静态示例）已全部失效。真机验收 QA H28 ①②③④ PASS
> （chips 渲染 / 与落盘 summary 同源 / force-stop 恢复后仍是 chips / 无匹配回退工具卡）；
> 首开清空示例 turn 也在真机确认。
>
> 真机已验证：2026-10-18 配色跨进程存活（QA H13-b）；
> 2026-10-19 用户确认「能用了」（QA H18 / H20 / H21）；
> 2026-10-22 补完 H13 / H14 / H15 / H19，并修掉工具卡恢复丢失的缺陷；
> 2026-10-24 MCP 确认回路（QA H27 ①③⑤，真机走的是旗舰聊天页那一侧）。
>
> **2026-10-24 升格主界面，侧栏按参考实现的逻辑落地**：≤760px 时侧栏从
> `display:none` 改为**覆盖抽屉**（汉堡开关 + 遮罩点按 / Esc 关闭），桌面宽度
> 仍常驻。参考 [Caho1/open-claude-ui](https://github.com/Caho1/open-claude-ui)
> 的侧栏规格（288px 常驻、头部新建按钮 + 分组会话列表 + 脚部功能行）：本目录
> 保留自己的 264px 网格与「今天 / 最近 / 远程渠道」分组，手机上把同一份内容
> 收进抽屉而不是丢弃。**脚部新增「设置」行**（`#settingsBtn`）—— 参考实现
> 的侧栏是布局里唯一的导航出口，本页面在手机宽度同样如此，而 LLM / MCP /
> 无障碍全在原生设置里，经 `PokeClawChat.openSettings` 桥进入 SettingsActivity。
> 参考实现的 pin / 拖拽重排（4px 死区 / Alt+↑↓）需要会话模型支撑，**刻意未抄**
> —— 真实模式下会话列表仍是死控件（`data-live` 隐藏规则不变）。
>
> **同日第二轮：设置收敛进页面**。抽屉「设置」行不再直达原生设置页，而是打开
> **页内设置面板**（`#settingsView`，弹层 sheet 形态，同一套 CDS 令牌）：分组列出
> 模型与预算 / 渠道 / MCP / 技能 / 记忆 / 主题，每行经
> `PokeClawChat.openSettingsPage(section)` 打开对应**原生编辑页**——配置行为仍然
> 只在原生一处，面板是纯呈现；「全部设置」行进原生完整页（权限/监控/关于）。
> 抽屉的「记忆库」「技能管理」从死行接为直达行（`enterLiveMode` 抹掉
> 「· 128 条」示例计数）。参考实现没有提供设置页组件（30 个组件全是会话/产物侧），
> 面板形态取自 Claude 客户端通行的「设置 = 从侧栏唤起的弹层」，令牌与行样式同源。
> BACK 键先关面板再退应用（页面经 `notifySettingsView` 上报开合）。
>
> **同日第三轮：原生编辑页换装 CDS（QA H31）**。面板行背后的 7 个原生 Activity
> 从 ember ThemeManager 观感迁到同一套令牌的 View 侧实现（`ui/cds/CdsUi.kt`）：
> clay 浅/深调色板、`--cds-panel` 分组卡、`.group-label` 段落标题、
> `.settings-topbar` 化的返回栏。深浅跟随系统 night mode（与 live 模式同信号）。
> 面板的「渠道接入」行同时修为直达完整设置页——`ChannelConfigActivity` 需要
> per-channel extra，裸开会立即 finish（47 上的死行）。
>
> **同日晚间第四轮：设置分区化 + composer 两个档位键活化 + 主题三轴合并
> （QA H32–H42，用户反馈七连）**。
> - **面板四区九行直达分区页，「全部设置」层删除**：配置（模型与任务 / 渠道接入 /
>   监听）、功能（工具 / MCP / 技能 / 记忆库）、外观（主题）、系统（关于）。
>   原生 `SettingsActivity` 从七组平铺重写成纯导航枢纽；新分区页
>   `ModelSettingsActivity` / `ChannelsActivity` / `MonitorActivity` /
>   `ToolsActivity` / `AboutActivity`。侧边栏脚部的「记忆库」「技能管理」快捷行
>   移入面板「功能」区——同一功能不在侧栏和设置里各挂一个入口。
> - **「高努力」「允许操作」两个死 pill 变真档位**：努力档位换档器
>   （低/中/高/最大 = 15/30/60/150 轮，`EffortLevel` → `AgentConfig.maxIterations`）
>   与敏感操作策略（每次询问 / 自动允许 → `McpConfirmationCoordinator`）。
>   KVUtils 是唯一事实源：`PokeClawChat.readTaskPrefs / setEffort / setAutoAllow`
>   桥读写，localStorage 降级为无桥预览缓存。
> - **主题三处入口合并为一套三轴**：侧边栏独立「深浅色」行删除，主题 popover 顶部
>   加「深浅」分段控件（参考项目 `SegmentedControl` 形态，原生侧同形态的
>   `widget/CdsSegmented.kt` 供主题页与模型与任务页共用）；原生主题页重写为
>   深浅 × 底色 × 强调色。`readThemePrefs / writeThemePrefs` 桥 + onResume
>   指纹比对推送（`syncPrefsToPage`）保证两边一致。
> - **WebView 蓝框**：`* { -webkit-tap-highlight-color: transparent }` +
>   全局 `:focus { outline: none }`（桌面 Chromium 看不到，一直漏到真机）。
> - **popover 管理器**：模型 / 努力 / 策略 / 主题四个 popover 共用一套互斥开关
>   （`registerPopover`），点外部或 Esc 全关——原来是各写一遍还会互相打架。
>
> **2026-10-25 第五轮：popover 定位实测 + 交互弹窗口径（QA H43–H48）**。
> - **主题面板完整在屏**：`placePopover()` 在每次开合时实测——触发器上方放不下
>   面板高度就翻转向下（`.pop[data-flip="down"]`），右缘出视口就贴回包含块右缘，
>   resize 时重量。修前手机 live 模式（会话列表整段隐藏、主题行被顶到靠近顶部）
>   向上弹 520px，前 12 行裁在屏幕外；composer 三个 popover 同管理器受益。
> - **`.pop` 显式收拢**：`width: max-content` + `max-width: calc(100vw - 32px)`。
>   真机上 auto 宽曾被拉到 590px（包含块只有 343px），item 的 tag 被裁、文档还
>   获得横向滚动；根因未查明（桌面 Chromium 无此行为），兜底已验。
> - **本页自身的弹窗口径**：设置弹层（`#settingsView`）仍是纯呈现——配置行为只在
>   原生一处；原生侧全部编辑/确认弹窗（任务预算 / 提示词 / 渠道 / 配对 / QR 等）
>   已收口到 `widget/CdsDialog.kt`（面板 + 衬线标题 + accent/danger 确认键 +
>   单选行），删除的 `AlertDialog` / `InputDialog` / `ConfirmDialog` 不再有调用方。

**2026-10-25 第六轮：任务账本（只读任务页，M1-部二）**。

侧栏脚部新增「**任务**」入口（`#tasksBtn`，待处理计数徽标只数真实数据里的
运行中 + 待确认），打开 `#tasksView`——与设置面板同构的全屏 sheet，两级：

- **列表**：任务卡 = 状态点 + 标题 + 状态标 + `渠道 · N 步 · 耗时/完成时间`
  + 最近一步摘要。整表重建（同 `renderLive` 的取舍），运行中卡的「已耗时」
  按 `createdAt` 现算、30s 一跳——**没有进度条**：任务步数不可预知，任何
  百分比都是编的。
- **详情**：步骤账本（编号 / 工具名 / 时间 / 摘要，失败行整行染危险色——
  真正的 error 必须在设备上看得到）+ 原始请求 + 状态 meta。**只读**：
  确认与操作留在聊天线程里，M2 的卡才进这里。
- **终态形态抄 open-claude-ui 的 terminal card**：完成=emerald 系淡染边框、
  失败=红系淡染边框（40% 混入边框色），pill 带 ✓/✕ 字形、终态不画状态点
  （参考实现的终态卡头里也没有点，pill 即状态）；详情的失败原因/最终答案
  是两个标签行 + 深色等宽 console 块（参考实现的终态输出面，两个 mode 下
  都是深色——「机器输出」与页面 chrome 由此分层）；详情 meta 带起止耗时。
  运行中/待确认/已取消不染边框——前两者有动态状态点，后者两者都不占。

数据只走桥，页面自己不存账本（每次打开重读；`__cds.renderTasks` /
`renderTaskDetail` 推送只做「开着时的刷新」，关着时下次开自然最新）：

```
PokeClawChat.readTaskList()            -> JSON 数组（摘要，新→旧）
PokeClawChat.readTaskDetail(id)        -> JSON 对象（摘要 + steps），找不到 "null"
PokeClawChat.notifyTasksView(level)    -> "closed" | "list" | "detail"（BACK 镜像）
window.__cds.renderTasks(jsonArray)    <- 账本变化时 Kotlin 推
window.__cds.renderTaskDetail(json)    <- 当前详情变化时推
```

列表项字段：`id / title / state / channel / createdAt / finishedAt /
stepCount / lastStep{name,summary} / error / answer`；详情追加 `origin`
（TaskRecord 的 originActivity）、`prompt`、`steps[{name,summary,success,at}]`。
时间戳一律毫秒；`state` 五个值（RUNNING / WAITING_CONFIRM / SUCCEEDED /
FAILED / CANCELLED），未知值渲染成灰点 + 原样标签，不崩不猜。`error` /
`answer` 是终态载荷：失败原因在卡片末行与详情块双重呈现（Failed 事件
在账本里不产步骤，原因只活在 error 字段），结果只进详情块。

**接缝是两个类：`ui/web/TaskLedgerBridge.kt`（读）+ `WebTaskLedgerSink.kt`
（推）**。桥把 `TaskRecordStore.list()/get(id)` 投影成页面契约
（`status`→五态、`channel`→displayName、`originActivity`→短标签、
`taskText`→title/prompt、终态 `error`/`answer` 落到卡片末行与详情块），
读失败降级为空账本并落 logcat——页面分不出「空」和「读坏」，logcat 分得清。
sink 实现部一的 `TaskSink`，在 `ClaudeUiPreviewActivity` 注册，账本每次
变化（步骤落账、状态翻转）经 `runOnUiThread` 推全量快照给 `renderTasks`；
页面关着时只更新徽标。映射是纯函数，由 `TaskLedgerBridgeTest` 12 例钉住
（部二/部一字段漂移的警报器——漂移的 symptoms 是说谎的卡片，不是崩溃）。
Compose 侧入口在 `ActiveTaskBar` 行尾（`onOpenLedger` →
`ClaudeUiPreviewActivity` 带 `show_tasks` extra 直接翻开任务页），BACK 在
任务页按 详情→列表→关页 三级消费（层级由页面经 `notifyTasksView` 镜像，
见 Activity 里 `tasksLevel`）。

已在浏览器（无桥预览）验证：四种状态卡 / 徽标 / 详情账本与失败行 /
详情→列表→关闭的 BACK 层级 / 空账本与坏 JSON 均落空态 / 详情缺失态 /
桥数据与推送刷新。真机 QA 条目见 `QA_CHECKLIST.md` AD 组（待设备；
`DUMP_DOM` 广播已带 `tasksOpen/tasksLevel/taskCards` 三个探针，
uiautomator 看不见 WebView 内部，这是任务页的真机取证口）。

## 来源

设计令牌与交互物理抄自 **[Caho1/open-claude-ui](https://github.com/Caho1/open-claude-ui)**（MIT © Caho1）。

- `cds.css` 的令牌值（35 阶灰度 / 陶土橙标尺、表面 0-3 阶梯、圆角矩阵、贝塞尔曲线、
  15 阶按钮弹簧、高分屏发丝线补偿、CJK 字体回退链）**逐值复制**，以保证观感一致
- `cds.js` 是从该项目 React hook 行为**用原生 JS 重写**的（滚动钉住、自动增高、
  输入法合成守卫、模型切换 popover），无构建步骤、无依赖

## 交互逻辑抄了什么

对应参考项目的四个 hooks（`code-examples/`）：

| 参考实现 | 本目录 | 关键机制 |
|---|---|---|
| `useClaudeScrollPinning.ts` | `cds.js` 滚动钉住 | 40px 容差 / 150ms 用户输入冷却 / 程序滚动守卫 / >120px 显示「跳至最新」 |
| `useRateLimitBackoff.ts` | `cds.js` 限流退避 | `min(max(base,5000) × 2^(n-1) × jitter, 600000)`，±15% 抖动，3 次后强制手动 |
| `useClaudeRovingFocus.ts` | `cds.js` 漫游焦点 | 方向键 / Home / End / PageUp / PageDown，`loop` 回绕，**Tab 立即交还**全局焦点流 |
| `ChatComposer.tsx` | `cds.js` 输入框 | 自动增高（封顶 220px）、CJK 合成守卫（`isComposing` + `keyCode 229` 双判） |

### 抄交互时踩到的三个坑

**1. 悬浮按钮不写 `z-index` 就点不动。**
`.jump` 是 `position: absolute`，而 `.turn` 是常规流内元素且排在前面。
按钮**看得见**（`data-show="true"` 打开了 `pointer-events`），
但点击被 `.turn` / `<pre>` 截走 —— Playwright 直接报
`subtree intercepts pointer events`。必须显式 `z-index: 10`。

**2. 只监听 `scroll` 会误判。**
`scroll` 事件滞后一帧，用户快速滚轮时锁定的释放是迟的。
参考项目额外监听 `wheel` / `touchstart` / `keydown`，用户一动手就立即解锁。

**3. 限流退避写完是死代码。**
`triggerBackoff()` 实现了但没人调用，测的时候才发现根本触发不了。
现已接到 `window.__cds.rateLimit()`，并在首次打开时演示一次。

### 调试入口

```js
__cds.rateLimit()        // 手动触发一次 429
__cds.resetRateLimit()   // 清空退避计数
__cds.state()            // { pinned, showJump, backoff }
```

## 抄的过程中发现的两个问题

**1. 参考主题用了自己没定义的变量。**
`cds-theme.css` 引用了 `--cds-bg-neutral`、`--cds-bg-neutral-hover`、`--cds-pad-lg/md`、
`--cds-dur-fast/slow`、`--cds-text-tint-blue`、`--cds-radius`，但 `:root` 里一个都没声明，
在那边全部落空。`--cds-radius` 落空尤其隐蔽：`.bubble` 的圆角公式
`calc(var(--cds-radius) + …)` 拿不到初值，退化成 `0 + 4px` 而非预期的 `8 + 4px`。
本目录 §2 已把它们的值补齐。

**2. 纵向 flex 容器会把消息压扁而不是撑出滚动条。**
`.feed` 是 `display:flex; flex-direction:column`，子项默认 `flex-shrink:1`。
而 `.main` 作为 grid item 默认 `min-height:auto`，会被内容撑高而不是收缩，
于是 `.feed` 的 `overflow-y:auto` 根本不触发，输入框被顶出视口。
修法是 `.main { min-height: 0 }` + `.turn { flex: none }`。

## 想把它变成真的 UI 的话

本目录**不能**直接落地。Android 端是 Kotlin/Compose，拿不到 `.tsx`，
也没有 npm/webpack 工具链。可行的两条路：

1. **只搬令牌**（推荐）—— 把 `cds.css` 的颜色、圆角、时长曲线翻译成
   `res/values/colors.xml` + Compose `MaterialTheme`。这是纯 Kotlin 内部工作，
   但属于「UI 改动」，按 `CLAUDE.md` 必须连真机跑 E2E（当前 **没有设备连接**）
2. **搬到 WebView** —— 只有 `app/src/main/assets/web/index.html` 一个位置，
   但那是局域网配置页，不是聊天页，语义不匹配

在接上真机之前，上面两条都属于未验证改动。

## 本地预览

直接用浏览器打开 `index.html`（无需构建、无需服务器）。
深浅色在侧边栏「主题」面板顶部切换（底色 / 强调色同面板）；纯浏览器预览下
主题与两个档位存 localStorage，有 Kotlin 桥时（真机）以 MMKV 为唯一事实源。
