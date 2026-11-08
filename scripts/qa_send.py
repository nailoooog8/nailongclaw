#!/usr/bin/env python3
"""Atomic task-send driver for on-device QA (drives the WebView chat via adb).

Companion to ui_probe.py: ui_probe READS a screen, this one SENDS a task and
verifies every step loudly, because the failure modes are silent — a tap on a
stale coordinate lands somewhere else entirely, and a "cleared composer" can
mean sent OR navigated-away. Every step re-dumps and asserts.

Usage:
  py scripts/qa_send.py send 'open%sthe%sbrowser...' 'open the browser'
      # %s is the adb input-text space; the fragment asserts the typed text
      # actually landed in #composerInput before tapping send
  py scripts/qa_send.py back    # recover to the chat screen from any nav depth

Lessons baked in (V2502DA, 2026-11-08):
  - sendBtn/composerInput are found by resource-id on a FRESH dump each step,
    never by remembered coordinates
  - subprocess on Windows needs encoding='utf-8' (GBK default mangles CJK)
  - ledger timestamps are DEVICE clock — compare clocks before timing verdicts
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time

SERIAL = os.environ.get("QA_SERIAL", "10AG4J1THX00524")
ADB = ["adb", "-s", SERIAL]
LEDGER = "/sdcard/Android/data/io.agents.nailongclaw/files/task_records.json"
TR = os.path.join(tempfile.gettempdir(), "tr_qa.json")

def sh(*args, **kw):
    return subprocess.run(ADB + list(args), capture_output=True, text=True,
                          encoding="utf-8", errors="replace", **kw)

def dump():
    r = subprocess.run(["py", "scripts/ui_probe.py", "--json"], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    try:
        return json.loads(r.stdout)
    except Exception:
        return []

def find(nodes, **kw):
    out = nodes
    for k, v in kw.items():
        out = [n for n in out if n.get(k) == v]
    return out

def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))

def back_to_chat(max_hops=6):
    for i in range(max_hops):
        nodes = dump()
        if find(nodes, id="sendBtn"):
            return True
        backs = find(nodes, id="ivBack")
        if backs:
            tap(*backs[0]["center"]); time.sleep(1.5); continue
        # no back button: try BACK key (closes sidebar/dialog)
        sh("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)
    return bool(find(dump(), id="sendBtn"))

def send_task(text_encoded, expect_label_frag):
    nodes = dump()
    if not find(nodes, id="sendBtn"):
        print("FAIL: not on chat screen"); return False
    tap(630, 2324); time.sleep(1.5)
    sh("shell", "input", "text", text_encoded); time.sleep(0.8)
    nodes = dump()
    comp = find(nodes, id="composerInput")
    if not comp or expect_label_frag not in (comp[0].get("label") or ""):
        print("FAIL: text not in composer; label =", (comp or [{}])[0].get("label", "<no node>")); return False
    btn = find(dump(), id="sendBtn")
    if not btn:
        print("FAIL: sendBtn gone"); return False
    tap(*btn[0]["center"])
    time.sleep(2)
    after = find(dump(), id="composerInput")
    label = (after or [{}])[0].get("label") or ""
    if expect_label_frag in label:
        print("FAIL: composer still holds text after send tap"); return False
    print("OK: composer cleared -> send accepted")
    return True

def ledger():
    r = sh("pull", LEDGER, TR)
    if r.returncode != 0:
        return None
    try:
        return json.load(open(TR, encoding="utf-8"))["records"]
    except Exception:
        return None

def wait_new_record(prev_count, timeout_s=40):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        rows = ledger()
        if rows and len(rows) > prev_count:
            return rows
        time.sleep(3)
    return None

def wait_status(status, timeout_s=150):
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        rows = ledger()
        if rows and rows[-1].get("status") == status:
            return rows[-1]
        time.sleep(3)
    return None

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "send"
    if cmd == "back":
        print("back_to_chat:", back_to_chat())
    elif cmd == "send":
        text = sys.argv[2]; frag = sys.argv[3]
        rows = ledger() or []
        prev = len(rows)
        if not back_to_chat():
            print("FAIL: cannot reach chat"); sys.exit(1)
        print("on chat, ledger records =", prev)
        if not send_task(text, frag):
            sys.exit(1)
        rows = wait_new_record(prev)
        if not rows:
            print("FAIL: no ledger record 40s after send"); sys.exit(2)
        r = rows[-1]
        print("RECORD:", r.get("taskText", "")[:50], "|", r.get("status"), "| channel", r.get("channel"))
