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
深浅色跟随系统，也可用左下角按钮切换。
