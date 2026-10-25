# 记忆库设计决策 —— 社区调研与取舍

> 调研日期：2026-10-17。记录的是**取舍与理由**，不是功能说明。
> 当前实现（机制、字段、算法、文件格式）见 [`MEMORY_BANK.md`](MEMORY_BANK.md)，本文不重复。

---

## 0. 为什么有这份文档

`MEMORY_BANK.md` 回答「记忆库现在是怎么工作的」。本文回答另外两个问题：

1. 社区在记忆 / 向量检索 / 记忆沉淀上收敛到了什么方案？
2. 我们**不**跟哪些，以及为什么？

写下来是为了避免以后重复调研，以及避免被「该上向量库了」这类直觉反复推动。

---

## 1. 社区现状（2026-10）

2026 年的 agent memory 已经从 prompt 技巧变成独立赛道。四条主流路线：

| 方案 | 核心抽象 | 量级 | 最适合 | 对我们 |
|---|---|---|---|---|
| **Mem0** | 抽取流水线逐轮决定 ADD/UPDATE/DELETE | ~48–55k star | 快速个人化、多租户、通用 agent | ❌ 面向云端 / 向量库 |
| **Zep / Graphiti** | 时序知识图谱，事实带**有效期区间** | ~24–27k star | 事实会随时间失效、需审计 | 🟡 部分对 |
| **Letta / MemGPT** | OS 式分层记忆，agent 自管 | ~15–23k star | 长跑自主 agent | ❌ 需要常驻 runtime |
| **LangMem** | episodic / semantic / procedural 三分 | LangChain 内置 | 已在 LangGraph 栈内 | ❌ 我们不用 LangGraph |

**市场本身不稳定**：有横评直接预测 2027 Q1 之前这四家至少两家合并或转向，理由是「碎片化到养不起四套互相竞争的方案」。
→ **不要把架构押在任何一家的 API 形状上。**

### ⚠️ 1.1 benchmark 数字不能横向比较

流传最广的几个数字**测的不是同一件事**，引用它们当目标会得出错误结论：

| 数字 | 出处 |
|---|---|
| mem0 「准确率 +26%」 | 相对 OpenAI 内置记忆，非绝对分 |
| LangMem ~58% (LOCOMO) | 且 **p95 延迟 ~60s** |
| Graphiti 63.8% / Zep | GPT-4o 条件下 |
| Letta 83.2% | 另一套 harness |

横评文章自己在开头就承认双方对「对方用了多少上下文」各执一词。
**结论：这些数字只用于判断量级，不能用于自测验收。**

### ⚠️ 1.2 社区最想要的能力，恰是商业化最重的部分

- Mem0 的知识图谱（实体解析、多跳查询）**锁在 $249/mo Pro 层**，免费/OSS 只有向量检索
- Zep **已弃用 Community Edition**

**对我们的含义**：市面上的免费开源方案，没有一个提供我们已有的东西——
「**删除后禁止 agent 写回**」（抑制指纹）、「**矛盾显式暴露给用户裁决**」、「**淡出但永不自动删除**」（保留度曲线）。
这三项是规则算出来的、可单元测试的、不依赖任何云服务。这是真差异点，不是「还没升级到向量库」。

---

## 2. 决策：暂不引入向量检索

社区的端侧向量方案**技术上已经可行**，有实测数据：

| 指标 | 实测值 |
|---|---|
| 全链路 p95 延迟 | ~140ms（Pixel 7a） |
| 总体积 | 38MB（含 INT8 embedding 模型） |
| 向量检索（1 万条，暴力） | 2–8ms |
| sqlite-vec 编译体积 | ~100KB |

`litertlm-android` 我们已经依赖，ONNX Runtime 那条路是通的。**但仍然不做**，三条理由按权重排序：

1. **语料只有 200 条上限**（`MemoryStore.MAX_ENTRIES = 200`，[MemoryStore.kt:42](app/src/main/java/io/agents/pokeclaw/agent/memory/MemoryStore.kt#L42)）。对 200 条做暴力检索是**亚毫秒级**。用 22MB 模型换亚毫秒，净亏。

2. **embedding 出端即破定位**。社区自己讲得很直白：embedding「看起来是匿名数字，但仍包含含义」，可暴露主题、意图，甚至被再识别。这与本项目「数据不出手机」的核心定位直接冲突，也会与 `SECURITY_AUDIT` 的叙事自相矛盾。

3. **保留度曲线是向量库管不了的东西**。`MemoryRetention` 回答的是**治理**问题——被用得多所以留得久、淡出但不删除、删了不许写回。向量检索只解决「哪块文本相关」，与这三项正交。

### 2.1 该借鉴的是那个**分工判断**

社区最有价值的一条洞察不是技术，是分层：

> **RAG 知识库**（大、多用户、读多写少）适合托管服务；
> **私有记忆**（小、单用户、每轮读写）应该留在端上。
> 「agent memory 一直感觉没被解决，是因为它被悄悄塞进了错误的层。」

我们**已经在对的一侧**。不要因为别人往云上搬就跟着搬。

---

## 3. 决策：记忆写入**不需要**异步化（并记录一次误判）

> **本节是一次自我纠正的留档。** 最初的结论是「记忆沉淀必须挪出热路径」，理由是 LangMem 的 p95 ~60s。**该结论错误**，原因见下。

### 3.1 误判是怎么产生的

那 60 秒来自 LangMem 的**抽取流水线**：每轮额外调用一次 LLM 去决定「这轮该抽出什么记忆」。我们**没有这条链路**：

| 环节 | 我们的实现 | 成本 |
|---|---|---|
| 判断「要不要记」 | 模型在**它本来就要跑的工具循环里**决定调不调 `memory_save` | 无额外 LLM 调用 |
| 执行写入 | `MemorySaveTool.execute` → `MemoryStore.remember()`（[MemoryStore.kt:176](app/src/main/java/io/agents/pokeclaw/agent/memory/MemoryStore.kt#L176)）→ 纯本地 JSON 写盘 | 毫秒级，同步无阻塞 |
| 读入提示 | `MemoryStore.briefing()`（[DefaultAgentService.kt:485](app/src/main/java/io/agents/pokeclaw/agent/DefaultAgentService.kt#L485)）→ 纯本地打分 | 亚毫秒级 |

**抽取即决策、由同一轮生成顺带完成**——这正是我们相对 Mem0 / LangMem 的**优势**，不是需要修掉的缺陷。
引入异步队列只会增加「记忆什么时候落盘」的不确定性，而换不来延迟收益。

### 3.2 唯一真实的代价（量级很小，不必现在动）

模型决定调 `memory_save` 会在工具循环里多插一个 round-trip。代价约等于一次工具调用，远小于一次生成。
若日后实测成为问题，正确做法是**把沉淀移到任务结束后的收尾阶段**，而不是引入并发队列。**暂记，不作为待办。**

---

## 4. 决策（2026-10-25 实施）：矛盾的时间维度——只借「有效期」那一半

> 2026-10-17 预研时本节结论是「暂不做，除非明确接受偶尔的错误静默」。2026-10-25 用户点名
> 参照 [getzep/graphiti](https://github.com/getzep/graphiti) 改记忆库，即构成那句「明确接受」。
> 以下记录为什么这次做、做了哪一半、用什么抵消「错误静默」的风险。

Zep 相对 Mem0 领先的那 ~15 个点（63.8% vs 49%），差距几乎全在时间维度。graphiti 给每条事实一个
**有效期区间**而非覆盖写，并区分**有效期**（事实何时为真）与**录入时间**（系统何时知道）：

```
active(f, t) = 1   if  t_from ≤ t < t_to
               1   if  t_from ≤ t 且 t_to 未知
               0   otherwise
```

旧事实被顶替时**作废而非删除**，历史保留可查。

### 4.1 落地的部分（2026-10-25）

- **时效接替**：`memory_save` 命中矛盾判定 → 旧条目 `invalidAt` 打戳作废、新条目接替
  （`supersedes`/`supersededBy` 链）。简报与默认召回只见仍有效的事实——不再两条并存等裁决。
- **双时态轻量版**：`validAt`/`invalidAt`（事实何时为真）与 `createdAt`/`updatedAt`
  （系统何时知道）分开存。复燃/恢复时 `validAt` 刷新。
- **历史可查**：`memory_recall` 的 `include_history` 返回作废条目（带「被 #谁 接替」标注）。
  graphiti 的任意时点查询（"上个月你以为…？"）在 200 条本地库里用「时间戳 + 历史列表」表达，
  不需要图索引。
- **淘汰让位**：超上限时已作废条目先淘汰（死历史再进不了简报，最该先让路）。

### 4.2 没借的部分，以及为什么

| graphiti 组件 | 不借的理由 |
|---|---|
| 双时态图索引（Neo4j/FalkorDB） | 为共享知识图谱设计；我们是单人单机 + 200 条上限，链式指针够用 |
| LLM 实体抽取 / 实体消解 | 违反「抽取即决策」（§3）——不让记忆写入引入额外 LLM 调用 |
| embedding 混合检索 | §2 的三条理由一条没变 |
| episodes（原始对话留档） | 隐私定位下不留可回放的对话流；事实的一句话自足即可 |

### 4.3 「错误静默」用什么抵消

预研时的担忧是：自动裁决猜错时用户看到「它把我以前说的话忘了」，比多问一句糟糕。2026-10-25
接受这个取舍，同时上了四道对冲，把"静默"拿掉：

1. **工具结果如实报告**作废了哪几条，注入规则要求模型转告用户（不隐瞒、不重试）。
2. **恢复是方向互换**：管理页点已作废条目一键恢复，接替它的那条反而作废——用户裁决旧的是对的。
3. **置顶豁免**：pin 是用户显式决定，时间逻辑无权作废它（与「手动添加豁免抑制指纹」同一条原则）。
4. **事件豁免**：kind=event 保留旧的 ⚠ 双标。这是 LLM 抽取缺席的补偿——词法分不开
   「同一主张换值」和「同模板的不同事件」（"5月去东京"/"6月去东京"），事件会累积、状态才会替换，
   按 kind 分流是纯规则能做的最接近正确的切分。

**遗留**：fact/preference 类里同模板并列事实（"第3件事"/"第5件事"）仍会被误接替——可逆、有报告，
接受。改述式矛盾照旧判不出（词法边界，同旧版）。

---

## 5. 确认的实现缺口

以下是**代码事实**，不是调研意见。

### 5.1 导出无法分享（潜伏 bug）

`file_paths.xml` 只声明两个 root：

````xml
<external-files-path name="downloads" path="Download" />
<cache-path name="cache" path="." />
````

而 `MemoryStore.storageDir()`（[MemoryStore.kt:562](app/src/main/java/io/agents/pokeclaw/agent/memory/MemoryStore.kt#L562)）写入的是 `getExternalFilesDir(null)` **根目录**，
即 `memories-export.md` 落在 `<ext-files>/`，**既不在 `Download` 下、也不在 cacheDir 下** → 两个 root 都不覆盖。
另外 `storageDir()` 会 fallback 到 `ctx.filesDir`（内部目录），而 `file_paths.xml` **完全没有 `<files-path>`**。

Debug 报告之所以没暴露这个问题：它写进 `cacheDir/debug_reports/`（[DebugTaskReceiver.kt:312](app/src/main/java/io/agents/pokeclaw/debug/DebugTaskReceiver.kt#L312)），正好被 `cache-path` 覆盖。

**当前状态**：尚未崩溃——导出只弹一个 Toast 显示路径，没有走 `FileProvider`。
**将来谁照着 `SettingsActivity.shareReportFile()` 加分享按钮，`getUriForFile` 会直接抛 `IllegalArgumentException`。**
修法：补 `<external-files-path path="." />` + `<files-path path="." />`。

### 5.2 导入完全缺失

全包 grep 无 `import` / `merge` / `loadFrom` / `ingest`。只有导出，没有回灌。

导入**必须走 `remember()` / `restoreSuppression()`，不能直接写文件**，否则会静默破坏三条不变量：

| 字段 / 行为 | 导入时怎么处理 | 理由 |
|---|---|---|
| `hits` / `lastUsedAt` | **清零** | 这是**设备本地**的强化信号。跨设备继承会伪造熟悉度，直接架空保留度曲线——「谁被召回过」会变成「谁被导入过」 |
| `pinned` | **带走** | 用户的显式决定，跨设备应一致 |
| 抑制指纹 | **必须同行** | 否则 A 上删掉的事实会在 B 上被重新学回来 |
| 与本地已有冲突 | 复用现有矛盾检测 | 不重写 |

调用时 `fromAgent = false`，理由与 `restoreSuppression()` 内的注释一致：**用户显式导入，不是 agent 自己要学**。
另需设条数上限防呆（参考 `deleteByContent` 的 `q.length < 3` 教训：别让外来文件冲爆 200 条上限）。

### 5.3 ~~`MemoryStore` 本身零单测~~ → 已补（`storageDirOverride` / `clock` / `enabledOverride` 测试缝）

> 2026-10-25 更新：`MemoryStoreTest` 已存在并覆盖下列全部顺序敏感不变量；时效接替落地时
> 又补了 `enabledOverride` 缝（`briefing()` 的启用开关读 MMKV，JVM 测试里不存在）。
> 本节保留作为"为什么这些不变量必须有测试"的清单：

- 抑制检查**必须**在合并判定**之前**（否则被忘的事实被合并回来，正是抑制要防的唯一结果）
- ~~`conflictsWith` 必须**双向**维护~~ → 时效接替后：接替链两侧一致、删除时修复对端、
  作废时清掉旧 ⚠（见 §4.1）
- `evictLocked` **刻意不留指纹**——容量淘汰不是用户不要了（作废条目被淘汰同样不留）
- `id` 由 normalized content 哈希而来 → 改内容即换 id；作废后原样重述**复用原条目**，
  不允许同 id 双行

---

## 6. 一个反面参照

[`electricpipelines/barq`](https://github.com/electricpipelines/barq)（产品名 Dabarqus）在本轮被作为对照对象看过。
它是本地 RAG 引擎，**GitHub 仓库内无任何源码**（`contents` 只有 `LICENSE` / `README.md` / `examples` / `.github`，`language: null`，
许可为 `NOASSERTION`），靠闭源二进制分发。

它有两个值得记的对比：

- **它没有的东西，我们有**：矛盾暴露、抑制指纹、保留度曲线，且全部有单测。
- **它的 `examples/` 有真 bug**：`while ingestions["status"] != "complete"` 无 sleep 无超时，服务端挂了就死循环；
  `app.py` 把异常吞成 `[None]` / `["Default"]`。这是「示例当营销素材维护」的必然结果。

**教训**：本项目的安全审计与测试是 fork 的叙事资产。**不要为了跟进度而把代码收敛成不可审计的形状**——
审计不可验证，等于审计不存在。

---

## 7. 决策：关联由模型产出，不用相似度算

> 实现见 [`MEMORY_BANK.md`](MEMORY_BANK.md)「关联（relatedIds）」。本节只记**为什么**。

### 7.1 社区的答案是「让模型连」，不是「算相似度」

查 mem0 v3 源码，三处指向同一个结构（[prompts.py](https://github.com/mem0ai/mem0/blob/main/mem0/configs/prompts.py) / [main.py](https://github.com/mem0ai/mem0/blob/main/mem0/memory/main.py)）：

1. `ADDITIVE_EXTRACTION_PROMPT` 里的 `## Memory Linking` 段，要求模型在新记忆上填 `linked_memory_ids`，
   并明确「Do NOT link memories that merely share a vague theme」。
2. v3 已改成 **ADD-only**——不再让模型改写旧记忆，因此链接天然是**单向**的：新记忆指向旧记忆。
3. 真正需要双向图谱的地方（entity store）另起一层，`_remove_memory_from_entity_store`
   专门负责删除时把 `memory_id` 从所有实体的 `linked_memory_ids` 里摘掉。

**关联和向量检索是两回事**：社区并没有用 embedding 算「像不像」，而是让模型顺手填一个字段。
我们的矛盾检测之所以用词法，是因为它要**可单元测试**；关联不打算可测，所以允许模型参与。

### 7.2 关键实测：词法相似度**分不开**相关和矛盾

在本仓库跑了一次性探针（`MemorySimilarity.similarity`，CJK bigram Dice）：

| 句子对 | Dice | 性质 |
|---|---|---|
| 用户是摄影师 ⟷ 用户用 Lightroom 修图 | **0.111** | 相关 |
| 用户用 Anki 背单词 ⟷ 用户每天背 20 个单词 | **0.222** | 相关 |
| 用户的手机是 Pixel ⟷ 用户的手机壳是蓝色的 | 0.421 | 相关 |
| 用户在杭州出差 ⟷ 用户在上海出差 | **0.500** | **矛盾** |
| 用户的手机是 Pixel ⟷ 用户的手机是小米 | **0.588** | **矛盾** |
| 用户养了一只橘猫 ⟷ 用户养了一只黑猫 | **0.714** | **矛盾** |

矛盾对全部落在相关对区间的**上沿之上**。任何单一阈值要么漏掉真关联，要么把矛盾算成关联——
**加重权改变不了这件事**，因为「矛盾 ⟂ 关联」是**正交关系，不是程度关系**。

这条实测推翻了我最初的设计（`relatedIds = f(bigram Dice, 标签重合, 保留度)`），
也让「本地实体中介」这条路只能接住一部分：实体能接住「Pixel/手机壳」，接不住「摄影/Lightroom」。

### 7.3 落到我们这里的三条

1. **关联由模型填**，`memory_save` 加 `related_ids` 参数。**零额外 LLM 调用**——
   模型本来就在工具循环里，多填一个字段不产生新的 round-trip，
   因此不违反 §3「抽取即决策」那条结论。
2. **矛盾优先于关联**：写入时若一条 `related_ids` 命中 `contradicts()`，直接丢掉。
   这正是 7.2 那条实测的直接用途——词法不擅长分，就用一条明确的规则分。
3. **单向**，与 mem0 v3 一致，也因为语义更对：
   「用户用 Lightroom 修富士照片」关联「用户的相机是富士」，
   反向并不等价（后者并不是因为前者才成立）。

### 7.4 代价，明确承认

- **关联的正确性无法靠单测保证**。`MemoryLinkTest` 能保证的是「错误的连线不会被存下来」
  （未知编号、自指、矛盾三种情况），**不能**保证模型连得准。这部分只能靠真机 QA 观察。
- **模型可能不连线**。宁可少连，不可错连。
- **导出 Markdown 丢关联**（`exportMarkdown()` 只写正文/标签/置顶）。
  和 `conflictsWith` 一样丢，一并等 BACKLOG 里那条无损 `exportJson()` 解决。

---

## 8. 资料来源

| 来源 | 用途 |
|---|---|
| https://github.com/mem0ai/mem0 · `mem0/configs/prompts.py` · `mem0/memory/main.py` | 关联由模型产出、单向链接、entity store 分层（第 7 节） |
| https://github.com/electricpipelines/barq | 对照对象（第 6 节） |
| https://github.com/letta-ai/letta · mem0ai/mem0 · getzep/graphiti · langchain-ai/langmem | 四条路线的能力边界 |
| https://adityakarnam.com/awesome-agentic-memory | star 量级与框架定位横评 |
| https://vectorize.io/articles/mem0-vs-zep | 时效建模与 LongMemEval 差异 |
| https://dreaming.press/posts/on-device-vector-search-agent-memory.html | 端侧向量检索分层判断 |
| https://mvpfactory.io/blog/building-a-local-rag-pipeline-on-mobile-vector-search-with-sqlite-on-device | 端侧实测延迟与体积 |
| https://fewshotacademy.com/blog/agent-memory-first-class | LangMem p95 延迟数据的出处 |
| https://maidul-haque.vercel.app/blog/agent-memory-architectures-2026 | 2027 合并预测 |

> 星数与定价随时变化，引用前请复核。
