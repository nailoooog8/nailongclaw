# 调研:手机 Agent 的"截图-滑动"感知回路与用户意图断裂,社区与大厂怎么解决

调研时间:2026-10-25。方法:四路并行网络调研(开源框架 / 海外大厂 / 国产厂商 / 回消息专项)+ PokeClaw 代码现状核对。

## 0. 问题定位

用户的两个体感,对应两个结构性问题:

- **痛点 A:感知回路慢。** "回消息要截图→看→滑动→再截图" = 每一步都走一次「截图 → VLM → 坐标动作」循环。滑动这个动作本身就是为了弥补"视觉一次只能看到一屏"的缺陷。
- **痛点 B:意图断裂。** Agent 只在任务开始时拿到一次世界快照;任务跑着跑着,用户已经跳去别的页面/别的 app。Agent 既不知道用户现在看什么,也不知道用户想法变了——它没有"持续在场"的上下文通道。

### PokeClaw 现状(代码事实)

| 已有能力 | 位置 | 说明 |
|---|---|---|
| 节点树感知 | `GetScreenInfoTool` → `service.getScreenTree()` | 压缩树 + `[n3]` node id,`tap_node` 直接点节点 |
| 语义发送 | `SendMessageTool` | 开 app→树里找联系人→底部 EditText `ACTION_SET_TEXT`→结构化找 send→Enter 兜底,全程免截图 |
| 通知读取 | `GetNotificationsTool` | NotificationListenerService 直接读文本(不开通知栏),**但只用来看,没有用通知自带的回复 action** |
| 监控回复 | `AutoReplyTool` / `AutoReplyManager` / `MonitorTargetSpec` | 按联系人+app 监听自动回 |
| 截图 | `TakeScreenshotTool` | 视觉通道 |

结论:**PokeClaw 的"树通道"和"语义动作通道"已经存在**,问题在任务模板/提示词让模型默认走了最贵的视觉通道,以及通知这条免费通道没打通。

## 1. 开源社区怎么做(2025-2026)

### 1.1 感知:树优先,截图兜底

- **mobile-mcp**:明确 "no vision model, no image tokens"——原生无障碍树快照 + 元素 ref 点击,截图仅兜底(github.com/mobile-next/mobile-mcp)。
- **DroidRun(现 mobilerun)**:默认 XML 树 + 元素 index;`--vision` 才混合,`--vision-only` 供无树场景;FastAgent 走 XML tool-calling 免规划开销(github.com/droidrun/mobilerun)。Portal 端还提供运行时高亮(等于设备端 Set-of-Mark)。
- **AppAgent**:截图 + uiautomator XML dump 结合,给可交互元素打数字标签(SoM)降 token(github.com/tencentqqgylab/appagent)。
- **android-use**:发降采样截图 + 编号元素表,**只有最新一步发全尺寸**,历史步省 token(github.com/Pegoku/android-use)。
- **UI-TARS-1.5**:高频截图当"连续视频信号"处理,模型原生输出动作而非 API 轮询(seed.bytedance.com/en/ui-tars)。
- 对照组:Mobile-Agent-v2、Open-AutoGLM(智谱 2025.12 开源)是纯"adb 截图→VLM→坐标"循环,是社区里被优化掉的对象,不是目标形态。

### 1.2 执行:语义动作优先,手势兜底

- **android-use**:文本直接 `ACTION_SET_TEXT` 到焦点字段(失败才回退 input/剪贴板);`scroll_to` 语义滚动=持续滚动直到目标文本上屏;`batch`/`fill_form` 一回合执行多动作,减少 LLM 往返。
- **mobile-use**:tap 三级 fallback:坐标 → resource_id → text 选择器;坐标兜底保证游戏等无树场景。

### 1.3 动作后验证:事件驱动是空白 = 机会

主流框架仍是"每步重新 dump";**没有任何主流框架订阅 `TYPE_WINDOW_CONTENT_CHANGED` 做事件驱动等待**。最接近的是 android-use 的"等 UI 稳定而非固定 sleep"(轮询树收敛)+ DroidRun Portal 的通知事件 WebSocket 推送。Mobile-Agent-v2 的反思 agent 每步比对操作前后两帧,分"错误操作(回退)/无效操作(页面没变)/正确"三类,只有正确步才写入历史。

### 1.4 用户干预/界面突变后的再同步

- **android-use "Steer"**:任务运行中用户随时在聊天框/通知栏发指令,agent 下一步前读取并**优先于原任务**;元素 id 屏幕位移自动重映射。
- **Open-AutoGLM `Take_over`**:是模型的一级动作;登录/验证码场景挂起等人完成回调续跑;检测到敏感页自动请求人工接管。
- **Mobile-Agent-v3(GUI-Owl)**:plan + progress management + reflection + memory 四件套纠偏长任务。
- **android-use 模型分层**:日常步走快模型(~2s/回合),检测到"动作失败/自我重复/回合无变化"才让主模型接管——这就是对状态失同步的运行时检测信号。

## 2. 海外大厂怎么做

两类可提炼的模式:

### (a) 系统级官方通道替代 UI 抓取

- **Apple App Intents / Assistant Schemas**:开发者声明式暴露动作,Siri/快捷指令直接执行,不抓屏;Apple Intelligence 的 Personal Context 用设备端语义索引(邮件/信息/照片)供检索,而非中心化画像(developer.apple.com/documentation/appintents)。
- **Google Android 16 AppFunctions**(实验性):app 用 `@AppFunction` 注解函数,OS 索引 schema,agent 经 `AppFunctionsManager.execute` 带参调用,需 `EXECUTE_APP_FUNCTIONS` 权限——相当于**系统级、本地执行的 MCP**(developer.android.com/ai/appfunctions)。
- **Google Assist 数据通道**:助手实现 `VoiceInteractionService`,会话回调 `onHandleAssist(data, structure, content)`——**系统直接把当前屏幕视图层级(AssistStructure)+ app 主动提供的结构化数据递给助手**,这是官方的"知道用户在看什么"通道(developer.android.com/training/articles/assistant)。
- 纠偏一条流传说法:**OpenAI Operator 主要靠截图+坐标**,CDP/DOM 之说不成立(openai.com/index/computer-using-agent);真正纯结构化路线是 chrome-devtools-mcp 的 a11y 树快照。
- 对照组:Anthropic computer use 官方最佳实践就是"每步动作后截图评估"的逐帧循环(platform.claude.com/docs/.../computer-use-tool)——通用但慢,PokeClaw 现状同款。

### (b) "实时观看 + 对话"维持意图连续性

- **Microsoft Copilot Vision**:共享屏幕/窗口,语音持续对话,实时对所见作答(support.microsoft.com)。
- **Google Gemini Live / Astra**:实时屏幕/摄像头共享连续对话;Mariner 并入 Gemini agent mode(deepmind.google/models/project-astra)。
- **OpenAI ChatGPT Agent**:Watch Mode——发邮件等关键任务要求用户实时注视下执行;随时打断、接管浏览器;敏感/不可逆动作前必须确认(openai.com/index/introducing-chatgpt-agent)。
- 共同点:**用"一次显式授权持续看"换"连续上下文"**,在敏感节点回退人工确认,而不是让 agent 盲轮询猜用户。

## 3. 国产厂商怎么做

行业公开资料(21经济网调研)总结为三条路线的组合:①意图框架(app 经 SDK 接入,系统智能体直接调用);②GUI 视觉路线(截屏多模态 + 无障碍,无需 app 适配);③混合。

- **荣耀 YOYO**:纯 GUI 路线代表,"像人一样看懂屏幕",无需 App 适配;官方口径约 900 项任务,新一代长任务 100+ 步、130+ 执行动作;点咖啡流程=先弹对比卡片让用户选,再逐屏走,商品不确定即停问,支付留人工。
- **华为小艺**:鸿蒙多模态智能体框架 HMAF,分层=应用与智能体层/协议层/平台层,系统级中控调度领域智能体;开发者经"意图框架"接入分发。
- **小米**:意图框架+一方应用直连为主,打车类辅以无障碍;HyperOS 4 超级小爱 2.0 主打任务编排,交互改为"超级岛"悬浮态——**任务后台跑、思考时用户可继续用手机,完成自动弹出**。
- **vivo PhoneGPT**:蓝心 3B 端侧模型驱动(未开源;开源的只有 BlueLM-GUI 多模态 GUI 模型)。
- **智谱 AutoGLM 2.0**:专属**云手机** 24h 异步执行、不占本机,官方称每个动作可回放/审计/干预;Open-AutoGLM 已开源(github.com/zai-org/Open-AutoGLM,含 50+ 中文 App demo)。
- **共识**:支付/发送类敏感操作不全自动,发送前二次确认;微信自动化因合规被普遍回避(智谱开源版明确放弃,仅荣耀/OPPO 实测能走通"发微信"全流程)。

对痛点 B 的直接回应:**小米超级岛和 AutoGLM 云手机都是"任务不抢用户前台"**——要么后台执行完成浮出,要么放到云端设备上跑,用户的手机随便用。

## 4. "回消息"专项

### 4.1 通知 RemoteInput 直回复(性价比最高)

全程不打开 app:在 `onNotificationPosted` 里遍历 `sbn.getNotification().actions`,找 `action.getRemoteInputs()` 非空的回复 action(.resultKey 常为 "reply");构造空 `Intent` + `Bundle`,`putCharSequence(remoteInput.getResultKey(), 回复文本)`,用 `RemoteInput.Builder` 重建后 `RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)`,最后 `action.actionIntent.send(context, 0, intent)`。这就是当年 Google Assistant "read my messages" 和 Android Auto 语音回消息的同一通道;Tasker/MacroDroid/Join 的"通知回复"插件同理。

- 出处:Stack Overflow 经典问答 stackoverflow.com/questions/40369508;实战详解 robj.me/how-to-reply-to-android-app-notifications...;官方文档 developer.android.com/develop/ui/views/notifications(direct reply 章节)。
- **限制**:微信/QQ 的自定义通知没有标准 RemoteInput action,此路失效——它们只能走无障碍树。

### 4.2 平台官方 API

- Telegram:个人号可走 MTProto(Telethon)无 UI 收发;Bot API 只能回应用户先发起的会话。
- WhatsApp:Cloud API 仅限企业号,个人号无官方 API。
- 短信:`SmsManager.sendTextMessage` 直接收发,配通知监听完全免 UI。

### 4.3 无障碍树读聊天记录(微信/QQ 的正解)

`getRootInActiveWindow()` 遍历节点 `getText()`/`getContentDescription()` **一次拿整屏可见消息(含发送者)**,免截图免 OCR;列表节点 `performAction(ACTION_SCROLL_FORWARD)` 语义滚动到更早历史;监听 `TYPE_WINDOW_CONTENT_CHANGED` 做增量去重(developer.android.com/guide/topics/ui/accessibility/service)。

### 4.4 意图同步的学术/工程参照

- Horvitz《Principles of Mixed-Initiative User Interfaces》(CHI'99):中断时机 + 不确定性时降级交还用户,至今仍是设计原则。
- LangGraph `interrupt` 人机接管机制;computer-use agent 人类监督策略实证研究(arxiv.org/html/2604.04918v1)。

## 5. 给 PokeClaw 的落地建议(按性价比排序)

> **落地记录(2026-10-25)**:P0-1/2/3 与 P1-4/6 已实现——`reply_via_notification`、`read_conversation` 两个新工具 + 注册与风险分类(HIGH_IMPACT / READ_ONLY)、`awaitUiStable` 内容版本等待、SendMessageTool 四处固定 sleep 替换、任务预热通知摘要与快照过期提示、AutoReplyManager 通知直回复快速路径(锁屏可用、微信/QQ 自动回退 UI 路径)、AgentConfig 与 LOCAL_TASK_PROMPT 提示词更新。QA 用例见 `QA_CHECKLIST.md` H51–H56(C2/C5 预期流程同步更新)。P1-5 Steer 未动。
>
> **P2 落地(2026-10-25)**:①悬浮窗超级岛化——运行胶囊实时显示当前动作(工具名),完成/失败浮出带摘要的结果胶囊、点按回聊天、可随时点停(QA H58/H59);②AppFunctions 探针落在 `get_device_info(category="app_functions")`(全反射,QA H57)。**重要查证**:AOSP 中 `EXECUTE_APP_FUNCTIONS` protectionLevel 为 `internal|role`(仅 ASSISTANT 角色预装应用可得),平台 `AppFunctionManager` 无公开发现 API——第三方 app 当前既不能发现也不能执行他人函数,故只做探针;待 API 37(CINNAMON_BUN)的 `DISCOVER_APP_FUNCTIONS` 落地后探针结论自动翻转,再建执行路径。

**P0 — 提示词与工具调度层(小改动,直接消灭大部分截图)**

1. **树优先策略**:任务模板里把"回消息/找元素/读屏幕"的默认链路改成 `get_screen_info`(+`tap_node`),`take_screenshot` 仅在树拿不到(canvas/游戏/空 WebView)或需要视觉判断(图片内容)时使用。社区共识(mobile-mcp/DroidRun)就是这条。
2. **`reply_via_notification` 工具**:复用现有 `ClawNotificationListener`,补上 4.1 的 RemoteInput 直回复——WhatsApp/Telegram/短信全程零 UI、零截图。配合现有 auto_reply 监控,新消息来了直接从通知回,连 app 都不打开。
3. **读会话用树不用眼**:读聊天记录的工具走 4.3(聚合列表节点文本),需要历史用语义滚动,微信/QQ 也走这条。

**P1 — 事件驱动与用户在环(中等工作量,社区空白=差异点)**

4. **等 UI 稳定代替固定 sleep + 事件驱动验证**:`ClawAccessibilityService` 维护一个"内容版本号"(TYPE_WINDOW_CONTENT_CHANGED 计数),工具执行后等版本收敛而非 `Thread.sleep`;长任务里用"版本没变"作为"动作无效"的检测信号(Mobile-Agent-v2 反思的廉价版)。
5. **Steer(用户随时插话)**:任务运行中,用户新消息(聊天框/频道/通知栏快捷输入)注入下一步并优先于原任务;登录/验证码类障碍挂起等人(Open-AutoGLM Take_over 模式)。
6. **任务开场自带"用户此刻在哪"**:任务启动时自动注入前台 app + 最近通知摘要(相当于自建 AssistStructure),模型不再假设用户还停在原来那屏;执行发送类动作前二次确认(行业共识)。

**P2 — 方向性**

7. **Android 16 AppFunctions**:关注生态成熟度,可先做 `AppFunctionsManager` 的探测与降级逻辑,有函数可调就绕过 UI。
8. **交互形态**:已有 `FloatingCircleManager` 悬浮球——参考小米超级岛:任务后台执行 + 进度浮窗 + 完成浮出,不绑架用户前台;这正是"用户已经跳去别的网页"的正解之一(另一条是 AutoGLM 式云手机,PokeClaw 短期做不了,但"后台跑不抢屏"可以)。

## 附:信息可信度备注

- Open-AutoGLM、mobile-mcp、DroidRun、android-use、AppAgent、Mobile-Agent 系均为 GitHub 可查的开源实现。
- "vivo PhoneGPT 开源"与"AutoGLM-Routh"两个流传说法均查无实据,引用时注意。
- "Operator 用 CDP/DOM 不用截图"的说法不成立,OpenAI 官方口径是截图为主。
