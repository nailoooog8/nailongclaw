# 网络搜索（web_search）设计取舍

> 2026-10-25 立项。本文记录的是**取舍与理由**，不是功能说明：为什么选博查+Tavily、为什么
> 拒绝其他方案、远程通道为什么放行。实现细节在代码里；QA 用例在 `QA_CHECKLIST.md` §AC。

## 0. 问题

agent 此前的"搜索"只有一条路径：设备自动化（打开 App → input_text 打字 → 读屏）。
`InAppSearchGuard` 专门防它没打字就谎报完成。这条路径对"在某 App 里搜"是对的，但对
"查个知识/事实"是错的交换：慢、脆、token 贵（UI 树转储）、还要过权限与风控。
缺的是一条 API 直连的信息检索通道。

## 1. 方案空间与取舍

| 方案 | 结论 | 理由 |
|---|---|---|
| **DeepSeek Search API**（内置，2026-10-25 加入，**默认**） | ✅ 默认 | 官方自研搜索引擎 API：$0.75/千次（博查的 1/7），**仅成功请求计费**，与 DeepSeek 模型共用同一把 API Key（国内版的"key 复用"路径）。注：DeepSeek App 早期联网搜索也曾外包给博查（36氪 2025-04），后自研并开放 API——先买后造再外卖的完整样本 |
| **博查 Bocha**（内置） | ✅ 备选 | 国内直连稳定、中文结果质量好、~¥0.036/次；数据不出海，契合主要用户群 |
| **Tavily**（内置） | ✅ 海外选项 | 海外 agent 社区口碑最好（AI-native 返回格式）、免费 1000 credits/月；国内直连不稳 |
| Serper | ❌ | 裸 Google SERP JSON——token 噪音自理，中文覆盖差，省的钱在 token 上亏回去 |
| Brave Search API | ❌ | 国内不可达，直接出局 |
| 自托管 SearXNG | ❌ | r/LocalLLaMA 主流方案，但用户设备是手机，没有自托管的前提 |
| GLM 原生 web_search_prime | ⏸ 后续 | 需要 bigmodel 直连 provider；当前云端入口只有 OpenAI/Anthropic/Google/Custom，透传不了智谱私有 tools 形态。加智谱 provider 时顺带（Phase 6） |
| 托管搜索 MCP | ✅ 零代码补充 | 项目已有 MCP 客户端与用户配置界面；MCP 服务器页加模板入口（You.com 免费·免Key / Tavily MCP）。海外端点，定位为进阶玩法 |

内置三家而非一家：搜索质量与可达性强依赖用户所在网络，单一供应商会把中文用户
或海外用户其中一边做成二等公民；DeepSeek 加入后又多了一条「已有 DeepSeek 模型 key 的用户
零成本复用」的路径。服务商抽象在 `SearchProvider` enum，加第四家是加一个
enum 项 + 一段解析函数。

## 2. 关键决策

**D1：`web_search` 进 READ_ONLY 白名单，远程通道放行。**
`ToolRiskPolicy.riskOf()` fail-closed，不加白名单远程通道一律拒绝。而"通道里问知识题"
恰是本工具的主要用例。风险面：花费被用户自己的 Key 限额封顶、只读公开数据、无外发副作用
——与 `make_call` 那类"对世界动手"的工具不同类。白名单注释里写明了这条论证。

**D2：结果自律截断，不依赖框架。**
`ToolResult` 无内建大小上限，整串 data 直接进 LLM 上下文。格式化函数自带两级封顶：
单条摘要 200 字符、总输出 ~4KB（先例：`GetNotificationsTool` 的 120 字符截断）。
搜索 API 按次计费 + 项目有 `TokenMonitor`/`TaskBudget`——省 token 不是优化项，是约束。

**D3：不加硬路由，不接 TaskClassifier。**
"知识题走搜索"只写进系统提示词（纯聊天例外区 + Chat/Question 技能 + LOCAL_TASK_PROMPT
的工具选择表），由模型按工具描述自选。与项目现有哲学一致：guard 只管窄模式（如 InAppSearchGuard
的正则），不做泛化意图分类。`TaskClassifier`（LLM 意图分类）在仓库里本就是未接线状态，
维持原样。`InAppSearchGuard` 零改动——"在某 App 里搜"的行为有 AC4 回归项兜底。

**D4：未配置时软失败。**
无 Key 时工具返回指路设置页的错误文案，并提示模型凭自身知识兜底作答——而不是把工具
从注册表里摘掉。工具始终可见（ToolsActivity 列表、LLM 工具规格），配置与否只影响运行时。

**D5：解析/格式化写成纯函数。**
`WebSearchClient` 的伴生对象（parseBocha/parseTavily/extractError/formatHits）不碰
Android 类，纯 JUnit 可测（`WebSearchClientTest`，9 例）。HTTP 半径只留在 `search()`
一个方法里，超时照抄 `McpStreamableHttpClient` 先例（15s/30s）——agent 执行器单线程
阻塞在此是安全的（`Thread.sleep(wait_after)` 与 MCP 确认门早有先例）。

## 3. 后续路线（本轮不做）

- **Phase 5 — agent 浏览器**：升级孤儿 `WebActivity`（现无调用方）为 `web_open`/`web_read`
  工具组：evaluateJavascript + readability 式正文抽取 + 截断；cookie 持久化持有登录态；
  `web_click`/`web_fill` 是写操作，须过 ToolRiskPolicy 确认门（复用 MCP 确认门机制）。
  本质：搜索是"听转述"，浏览器是"亲自到场读原文+办事"。
- **Phase 6 — 智谱 provider**：直连 bigmodel 端点后顺带原生 `web_search_prime`，
  免搜索 Key，代价是锁死在一家厂商的额度与质量。

## 4. 已知代价

- 通道用户（微信/QQ/...）触发的是机主配的付费 Key——机主配 Key 即授权，但 Phase 5 若加
  "web 上办事"类工具，须重新过风险分级，不能沿用 READ_ONLY。
- Tavily/博查的响应字段（summary/snippet 语义）以 2026-10 官方文档为准，两家若改字段，
  `WebSearchClientTest` 的样例 JSON 要同步更新。
