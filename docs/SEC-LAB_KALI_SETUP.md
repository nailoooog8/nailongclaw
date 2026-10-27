# PokeClaw 攻防实验台 · Kali WSL 工具基线（授权自测）

> 目标：把 `kali-linux` WSL 变成能对 **io.agents.pokeclaw**（本人 fork 的 Android 助手）做授权红队自测的实验台。
> 授权边界：只测本机/本人设备上本人拥有的 app；不碰任何第三方系统。
> 运行环境：`wsl -d kali-linux`，用户 `nailoog`（在 sudo 组）。adb 走 Windows 侧 platform-tools，
> 或在本脚本里装 `adb` 后用 `adb connect`/USB。设备通过 Windows 的 adb 管理，WSL 用 `/mnt/c` 复用同一 adb。

## 一键安装（在 Kali 里跑）

```bash
wsl -d kali-linux -- bash /home/nailoog/sec-lab/setup-kali.sh
```

脚本内容见 `setup-kali.sh`，覆盖：
- **静态 APK 分析**：`apktool` `jadx` `dexlib2`（反编译、看合并后的 manifest/导出组件/字符串/端点）
- **导出组件/IPC 攻击**：`drozer`（`android intent launch_activity` / `app.activity.info` 等）
- **动态插桩**：`frida` `frida-tools` `objection`（SSL pinning 绕过、hook 令牌校验——需 rooted/模拟器）
- **网络面**：`nmap`（LAN 扫 9527 ConfigServer）、`curl`/`python3`（认证边界探测）
- **Web/注入**（可选，攻 web_search/provider 面）：`sqlmap`
- **复用宿主 adb**：软链 `/mnt/c/.../platform-tools/adb` 到 PATH，WSL 与 Windows 共享同一台设备

## 说明
- 本脚本只装工具、不发动任何攻击。真正打之前先读 `docs/SECURITY_AUDIT_R4_RED_RUNBOOK.md`。
- 动真机前务必先用当前 HEAD 重刷设备（现网设备是 §6.5 修复前的陈旧包，见 R4 报告）。
