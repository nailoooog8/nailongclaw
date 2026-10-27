# PokeClaw 攻防实验台 · 红队 Runbook（授权自测）

> **授权边界**：只针对本人拥有、本机/本人设备上的 `io.agents.pokeclaw`。不碰第三方系统。
> **方法论**：攻击面分类参考 [SnailSploit/Claude-Red](https://github.com/SnailSploit/Claude-Red) 的 `offensive-mobile`；
> 验证纪律参考 [cloudflare/security-audit-skill](https://github.com/cloudflare/security-audit-skill) 的 `DESKTOP-MOBILE-AND-LOCAL-IPC`。
> **本轮状态**：静态走查 + 只读真机侦察已完成；**动态 PoC 未执行**——因现网设备是陈旧包（见 §0），
> 动态项留作重刷当前 HEAD 后的执行清单。诚实边界：可利用性结论是"代码路径成立"，非"真机复现"。

---

## 0. 前置：设备是陈旧包，先重刷当前 HEAD

现网设备 `10AG4J1THX00524`（V2502DA）上装的 `io.agents.pokeclaw`：

| 事实 | 值 | 含义 |
|---|---|---|
| `versionName/versionCode` | 0.8.3 / 55 | — |
| `lastUpdateTime` | 2026-10-25 10:42:14 | 早于关键修复 |
| 修复提交 `1b51c9d`（debug 接收器移出 release 包） | 2026-10-26 19:30 | **设备包早于它** |
| `flags` | `0x0`（无 `DEBUGGABLE`） | 是 release 构建 |
| 合并后组件里竟含 `.debug.TaskTriggerReceiver` / `.debug.DebugTaskReceiver`（导出、带 intent-filter） | 存在 | 因为包旧 |

**结论**：设备是 §6.5 之前的 release 包，所以带着两个导出的 debug 接收器。**当前 HEAD 不是这样**——
`app/src/main/AndroidManifest.xml` 对 debug 接收器引用数为 0，声明只在 `app/src/debug/AndroidManifest.xml`，
且无 `app/src/release/` manifest 回加。故这是"设备陈旧"，**不是当前代码回归**。
**动态 PoC 必须打在重刷后的 HEAD 包上，否则验的是旧代码。**

```bash
# 重刷（在 Windows 侧，pokeclaw 仓库）
./gradlew :app:assembleRelease        # 或installRelease（需签名配置）
adb install -r app/build/outputs/apk/release/*.apk
adb shell dumpsys package io.agents.pokeclaw | grep -iE "lastUpdateTime|versionName"
# 复核导出面：预期 release 里 .debug.* 接收器为 0
adb shell dumpsys package io.agents.pokeclaw | grep -iE "debug.TaskTriggerReceiver|debug.DebugTaskReceiver" || echo "OK: no debug receivers in release"
```

---

## 1. 攻击面地图（只读侦察，任何时候安全）

```bash
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"   # WSL 里用 adb-win（见 lab 脚本）
PKG=io.agents.pokeclaw

# 1.1 全部导出组件 + intent-filter + 权限（最终合并 manifest 的真相）
"$ADB" shell dumpsys package $PKG | grep -iE "exported|Activity Resolver|Receiver Resolver|Service Resolver|Permission \[" 

# 1.2 是否 debuggable / allowBackup
"$ADB" shell dumpsys package $PKG | grep -iE "DEBUGGABLE|ALLOW_BACKUP"

# 1.3 LAN 控制台端口 9527 是否在听（0x2537）
"$ADB" shell "cat /proc/net/tcp | awk '{print \$2}' | grep -i 2537"   # 有输出=在听

# 1.4 无障碍/通知监听是否启用
"$ADB" shell settings get secure enabled_accessibility_services
```

**当前 HEAD 的导出攻击面**（源码 + 合并 manifest 交叉确认）：

| 组件 | 导出 | 触发 | 门控 | 渠道天花板 |
|---|---|---|---|---|
| `.ui.splash.SplashActivity` | ✅ LAUNCHER | `am start … --es task "…"` | **无令牌、无开关**（见 §3，红队新发现） | EXTERNAL（只读） |
| `.automation.ExternalAutomationReceiver` | ✅ | `am broadcast -a …RUN_TASK/RUN_CHAT` | 配对令牌 + 开关 | EXTERNAL（只读） |
| `.automation.ExternalAutomationActivity` | ✅ | `am start -a …RUN_TASK/RUN_CHAT` | 配对令牌 + 开关 | EXTERNAL（只读） |
| `.service.BootReceiver` | ✅ | `BOOT_COMPLETED` | 仅启自身前台服务 | — |
| `com.blankj.utilcode.util.MessengerUtils$ServerService` | ✅ | action `io.agents.pokeclaw.messenger` | 三方库自带（见 §5） | — |
| `.debug.TaskTriggerReceiver` / `.debug.DebugTaskReceiver` | ✅ **仅 debug 构建** | action `…TASK` / `…DEBUG_TASK` | **仅 `BuildConfig.DEBUG`** | LOCAL/无门（仅 debug） |
| `ClawAccessibilityService` / `ClawNotificationListener` | 系统绑定 | — | BIND_* 权限 | — |

---

## 2. 红队 PoC · 外部自动化广播门（负路径，安全）

令牌模型（`automation/ExternalAutomationGuard.kt`）：Crockford base32、定长 32 字符、160-bit；
`BruteForceGuard`：**5 次失败 → 锁 30s，逐次翻倍至 30min 封顶**；锁键 = 调用方自报 `return_package`，缺省用共享键 `external_automation`。

**安全探针协议**（避免误锁用户自己正在用的宏）：
- `NO_TOKEN`（从未配过令牌）路径**不累加**失败计数——设备若没配过令牌，随便打，零副作用。
- 若已配令牌：`MISSING_TOKEN`/`INVALID_TOKEN` 才累加。**不带 `return_package` 的探针打的是共享键**，
  连打 5 次会锁共享键 30s——但这**锁不到**用户按自己 `return_package` 键控的那条宏。
- 结论：负路径探针**不带 `return_package`、每次换着打、总计 ≤4 次**，最坏只是共享键一次 30s 暂停，安全。

```bash
PKG=io.agents.pokeclaw

# 2.1 无令牌（预期：NO_TOKEN 拒绝；若设备没配令牌则零副作用）
adb shell am broadcast -a io.agents.pokeclaw.RUN_TASK -p $PKG --es task "ping"

# 2.2 空 token extra（预期：MISSING_TOKEN，累加一次失败）
adb shell am broadcast -a io.agents.pokeclaw.RUN_TASK -p $PKG --es task "ping" --es token ""

# 2.3 错令牌（预期：INVALID_TOKEN，累加一次失败；常数时间比较，无时序泄漏）
adb shell am broadcast -a io.agents.pokeclaw.RUN_TASK -p $PKG --es task "ping" \
  --es token "00000000000000000000000000000000"

# 2.4 回调 fail-closed：return_action 指向一个未安装的包（预期：不发任何回调，防"代播任意广播"）
adb shell am broadcast -a io.agents.pokeclaw.RUN_TASK -p $PKG --es task "ping" \
  --es return_action "com.evil.X" --es return_package "com.not.installed"
```

**预期（当前 HEAD）**：2.1–2.3 全部被 `ExternalAutomationEntrypoint` 在"任何可观测动作之前"拒绝，
`ExternalAutomationGuard.verify` 先于"功能是否开启"查询（避免用两种错误差异探开关）。2.4 无回调。
**若任一正路径放行 → 高危回归。**

**红队已确认的护栏强度（静态）**：
- 令牌 160-bit，在线爆破不可行；锁定只是纵深，不是主控（绕过锁定对攻击者无意义）。
- 锁定按调用方自报身份键控 → 轮换身份可绕锁定，但**对 160-bit 令牌无增益**（文档已如实记录，非漏洞）。
- `return_action` 必须配一个**已安装**的 `return_package` 且 `setPackage(target)` 显式发送 → 不是广播代理（R3-01 修复保持）。

---

## 3. 红队 PoC · LAUNCHER 门（新发现，R4-R1）

**这是 R3 漏掉的一类**。`SplashActivity` 是导出且 LAUNCHER 声明的 activity（app 图标那道门），
**无令牌、无 enabled 校验**；它把 `task` extra 转发给 `ComposeChatActivity` 时**打了 `EXTRA_EXTERNAL_SOURCE=true`**
（`ui/splash/SplashActivity.kt:51-54`），于是 `ComposeChatActivity.kt:361-363` 记 `Channel.EXTERNAL`，
`ToolRiskPolicy` 给只读天花板。**所以最坏的一半（拨号/发送/外发/剪贴板/mcp_*）被挡住**——
但"任何 app 能无门驱动一次只读 agent 任务"这件事本身没被门控。

```bash
# 任何已安装 app（或 adb）都能打，无需令牌、无需开外部自动化开关：
adb shell am start -n io.agents.pokeclaw/.ui.splash.SplashActivity \
  --es task "take a screenshot, then list my installed apps and read my last 20 notifications"
```

**影响（诚实定量）**：
- 会跑：`take_screenshot`/`get_screen_info`/`get_notifications`/`read_conversation`/`get_installed_apps`/
  `calendar_read`/`kb_read`/`memory_recall`/`web_search`（全是 READ_ONLY）。
- **不会**外泄回攻击者：此门不转发 `return_action`（`SplashActivity` 只转发 `task`+标记），无回调；
  且 EXTERNAL 天花板禁掉一切 `send_*`。故是**隐私观测 + 烧用户 LLM 配额**，非机密外泄。
- 但它是**无提示、无门**的：用户没点任何东西，另一个 app 就能让 pokeclaw 截图/读通知/过一遍屏幕内容并花用户的钱。

**判定**：`established`（源码 + 真机导出面双证），但**影响被天花板压住** → 定 **Medium（隐私/配额；非外泄）**。
对应 cloudflare 的 "exported component overreach" 类。

**修复（已落地，真机校正过）**：`SplashActivity` 转发 `task` 前改**纯配对令牌门控**。注意：初版想用 `getReferrer()` 认 adb shell，**实测 `adb shell am start` 的 referrer 是 null**（不是 `com.android.shell`），且 referrer 可被伪造——故去掉 referrer 判定，只认令牌。代价：裸 `adb am start SplashActivity --es task`（无令牌）不再驱动任务；QA 请用 `am broadcast io.agents.pokeclaw.TASK`（debug 接收器）或带令牌的 ExternalAutomationActivity。**真机已验证（vc57）**：无令牌/错令牌均丢弃、ExternalAutomation 无令牌均拒。

---

## 4. 红队 PoC · LAN 控制台 ConfigServer:9527（需 app 运行 + 服务开）

静态结论见蓝队报告；动态探针在 app 运行、控制台开启后执行。全部打 **localhost/本机**，**不抽取真实凭据**：

```bash
IP=127.0.0.1; PORT=9527

# 4.1 无 PIN（预期 401；debug 端点 release 403）
curl -s -o /dev/null -w "%{http_code}\n" http://$IP:$PORT/api/config

# 4.2 错 PIN（预期 401；BruteForceGuard 5 次锁 30s 起——只打 1-2 次）
curl -s -o /dev/null -w "%{http_code}\n" -H "X-Config-PIN: 000000" http://$IP:$PORT/api/config

# 4.3 Host 校验（预期：空 Host / 外网 Host fail-closed）
curl -s -o /dev/null -w "%{http_code}\n" -H "Host: evil.example.com" http://$IP:$PORT/api/config

# 4.4 cleartext 观测（预期：HTTP 明文，bot token/LLM key 过空——F5 残留，被动泄露面）
curl -s -D - -o /dev/null http://$IP:$PORT/ | head -20
```

**预期（当前 HEAD）**：主动面收敛良好（401/403、Host 校验、爆破锁定都在）；**明文 HTTP 被动泄露仍在**（F5，NSC 全局 cleartext 放行，受用户可配 http 端点约束，属已知取舍）。

---

## 5. 红队 PoC · 三方库导出服务 `MessengerUtils$ServerService`

合并 manifest 里有个导出服务 `com.blankj.utilcode.util.MessengerUtils$ServerService`（action `io.agents.pokeclaw.messenger`），
来自 AndroidUtilCode。需确认它到底暴露什么、是否可被外部 app 绑定干私活：

```bash
# 反编译看这个 Service 的实现与 intent-filter
jadx -d /tmp/pc app-release.apk >/dev/null 2>&1
grep -rn "MessengerUtils" /tmp/pc/sources/ | head
# 尝试绑定/发消息（只读探测，勿发敏感 payload）
adb shell dumpsys activity services io.agents.pokeclaw | grep -i messenger
```

**留给蓝队/后续**：判定其是否构成"外部可调用执行本应 app 自己做"的面。

---

## 6. 动态工具（需 rooted / 模拟器；授权范围内）

```bash
# Frida hook 令牌校验，确认 160-bit 常量时间比较无旁路（研究性质）
frida -U -f io.agents.pokeclaw -l hook_guard.js
# drozer 枚举导出组件可接受 intent（Leak/attack surface）
drozer console connect --server 127.0.0.1
run app.activity.info -a io.agents.pokeclaw
run app.broadcast.info -a io.agents.pokeclaw
```

---

## 7. 汇总

红队本轮净结论：
- **R4-R1（新，Medium）**：LAUNCHER 门无门驱动只读观测 + 烧配额（§3，天花板挡住外泄）。
- 外部自动化广播门：令牌 + 天花板 + 回调 fail-closed 在**当前 HEAD 均成立**（§2 负路径应全拒）。
- 陈旧设备带着 debug 接收器（§0）——重刷 HEAD 后消失，非当前代码问题。
- ConfigServer 主动面收敛、明文被动面仍在（§4）。
- `MessengerUtils$ServerService` 待定（§5）。

配套报告：`docs/SECURITY_AUDIT_R4.md`（红蓝合报）、`docs/SECURITY_AUDIT_R4_BLUE.md`（蓝队审计）。
