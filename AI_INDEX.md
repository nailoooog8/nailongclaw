# NailongClaw AI Index

This is the repo map for coding agents. Keep canonical information in existing files; do not create new root docs when one of these files already owns the topic.

## Canonical Root Docs

| File | Owns |
|------|------|
| `README.md` | Public product direction, roadmap, platform constraints, benchmark claims, changelog |
| `CLAUDE.md` | Agent/project working rules |
| `work/QA_CHECKLIST.md` | QA methodology, release gate, test cases, debug changelog |
| `work/BACKLOG.md` | Prioritized bugs, features, QA gaps, ideas |
| `docs/ops/RELEASING.md` | Release signing, tag workflow, stable APK publishing |
| `docs/design/ARCHITECTURE_RECONSTRUCTION.md` | Historical architecture reconstruction plan and refactor guardrails |
| `CLA.md` | Contributor license agreement |
| `AI_INDEX.md` | This repo map |

## Directory Map

| Path | Purpose |
|------|---------|
| `app/src/main/java/io/agents/nailongclaw/` | Android app source |
| `app/src/main/assets/playbooks/` | Built-in playbooks used by the agent harness |
| `app/src/test/` | JVM/unit regression tests |
| `work/` | Working ledgers: `QA_CHECKLIST.md` (QA methodology, release gate, test cases, debug changelog) + `BACKLOG.md` (prioritized bugs, features, QA gaps, ideas) |
| `scripts/` | QA and automation scripts |
| `.auto/` | QA 运行时产物：**证据按轮分批入库**（BACKLOG/QA_CHECKLIST 引用到的截图与门禁日志）；构建草稿与中间失败日志可清；**敏感内容（配对令牌/密钥/MMKV 转储）打码或删除后才入库**——入库前先看图 |
| `.github/workflows/` | Build + unit tests, CLA/DCO check, emulator-matrix QA, Firebase Test Lab, signed release |
| `docs/` | GitHub Pages site and public site assets |
| `docs/design/` | Design docs: `MEMORY_BANK.md` (how the memory bank works: retention curve, suppression fingerprints, contradiction detection, optional on-device semantic ranking (EmbeddingGemma), file format), `MEMORY_DESIGN.md` (why the memory bank is built this way: community survey, rejected alternatives, known gaps — §2 records the 2026-11-13 reversal of the no-vector-retrieval decision), `ARCHITECTURE_DECISIONS.md`, `WEB_SEARCH_DESIGN.md`, `SKILL_FORMAT.md`, `MCP_INTEGRATION_PLAN.md`, `ARCHITECTURE_RECONSTRUCTION.md` |
| `docs/security/` | Security audit reports R1–R5 with red-team runbook and blue-team review (`SECURITY_AUDIT*.md`) |
| `docs/ops/` | Ops runbooks: `AI_AGENT_PLAYBOOK.md`, `RELEASING.md`, `SEC-LAB_KALI_SETUP.md`, `OEM_COVERAGE_TIERS.md`, `community-issue-replies.md`, `LOCALIZATION_STATUS.md` |
| `docs/archive/` | Archived one-off docs: `AGENT_PERCEPTION_RESEARCH.md`, `RELEASE_NOTES_WEB_SEARCH.md` |
| `docs/screenshots/` | Screenshot assets used by the landing page |
| `demo/` | Legacy demo GIF assets |
| `Screenshots/` | Legacy screenshot assets |
| `prototype/` | Historical UI prototypes |
| `mockup/` | Early interactive mockups |
| `signatures/` | CLA signature state |

## Direction Rules

- NailongClaw is a generic Android mobile-agent harness with a product shell on top.
- Prefer fixing deterministic harness/runtime/device problems before tuning one stochastic task.
- Keep prompts, tools, skills, and playbooks generic.
- Treat Cloud/Local exploratory task success as a repeated-trial metric, not a single-run truth.
- For GPU/local runtime reports, collect logs and keep CPU fallback truthful before changing backend selection.
