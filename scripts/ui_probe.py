#!/usr/bin/env python3
"""Dump the uiautomator tree and print labelled nodes ready to tap.

This is the cheap way to see the screen. A full 1260x2800 screenshot costs
roughly 4,700 image tokens to look at and still cannot answer "where is the
Settings row"; this prints the same answer in about a thousand tokens of text,
and it never mis-reads a small CJK label the way OCR does. Reach for
scripts/pngpix.js when the question is about colour rather than position.

Windows PowerShell mangles non-ASCII in inline one-liners, so this lives in a
file with an explicit encoding declaration instead. On this workstation only
the `py` launcher is on PATH (there is no `python3`), so invoke it as
`py scripts/ui_probe.py ...`.

Usage:
  py scripts/ui_probe.py [TERM ...]        # labelled nodes, tap-ready
  py scripts/ui_probe.py --tap "设置"       # tap the first real match
  py scripts/ui_probe.py --surface         # 133ms: which window is focused
  py scripts/ui_probe.py --file ui.xml     # re-parse a saved dump, skip 2.2s
  py scripts/ui_probe.py --all             # include hidden [0,0][0,0] nodes
  py scripts/ui_probe.py --box             # print full bounds, not centres
  py scripts/ui_probe.py --json            # machine-readable
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

# uiautomator reports bounds as "[x1,y1][x2,y2]" — bracketed, not plain CSV.
NUM = re.compile(r"-?\d+")

# Where adb lives when PATH is unhelpful. The npm shim works again as of
# 2026-10-25, but the SDK binary stays the reliable direct path.
def _sdk_adb() -> str:
    roots = [
        os.environ.get("ANDROID_SDK_ROOT"),
        os.environ.get("ANDROID_HOME"),
        os.environ.get("LOCALAPPDATA") and os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk"),
    ]
    for root in roots:
        # Only trust an absolute root: an empty LOCALAPPDATA would otherwise
        # build a relative path that resolves against whatever the cwd happens
        # to be, and could match a file inside the repo.
        if root and os.path.isabs(root):
            candidate = os.path.join(root, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
            if os.path.exists(candidate):
                return candidate
    return ""


def adb() -> str:
    """Prefer the SDK adb, fall back to whatever is on PATH."""
    sdk = _sdk_adb()
    if sdk:
        return sdk
    found = shutil.which("adb")
    if found:
        return found
    raise SystemExit("adb not found: set ANDROID_SDK_ROOT or put adb on PATH")


def shell(args: list[str]) -> str:
    """Run adb and return stdout. For read commands only — a write command
    must go through write(), which reports whether adb actually accepted it."""
    return run(args).stdout.decode("utf-8", errors="replace")


def run(args: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run([adb(), *args], capture_output=True, check=False)


def write(args: list[str], what: str) -> int:
    """Send a write command once. adb accepts `input` with empty stdout and exit
    0, so the exit code is the only signal — report it rather than claiming a
    tap landed. Verify the effect with a fresh dump, never by retrying."""
    result = run(["shell", *args])
    if result.returncode != 0:
        err = result.stderr.decode("utf-8", errors="replace").strip()
        print(f"{what} FAILED (exit {result.returncode}): {err}", file=sys.stderr)
        return 1
    return 0


def center(bounds: str):
    """(cx, cy) from a "[x1,y1][x2,y2]" bounds string, or None if unusable."""
    nums = [int(v) for v in NUM.findall(bounds or "")]
    if len(nums) != 4:
        return None
    x1, y1, x2, y2 = nums
    # Compose reports [0,0][0,0] for nodes it has not laid out yet; those cannot
    # be tapped, and silently averaging them produces a tap at the screen origin.
    if x1 == 0 and y1 == 0 and x2 == 0 and y2 == 0:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def box(bounds: str):
    nums = [int(v) for v in NUM.findall(bounds or "")]
    return tuple(nums) if len(nums) == 4 else None


def dump(path: str | None) -> str:
    """uiautomator XML, from a saved file or a fresh ~2.2s dump."""
    if path:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    # A fixed remote path is a trap: a failed dump leaves the previous file
    # behind and the next read mistakes it for success. Unique name, read back,
    # then remove.
    remote = f"/sdcard/ui_probe_{os.getpid()}.xml"
    try:
        shell(["shell", "uiautomator", "dump", remote])
        return shell(["shell", "cat", remote])
    finally:
        shell(["shell", "rm", "-f", remote])


def surface() -> int:
    """Which window has focus — the cheapest possible 'where am I' check."""
    text = shell(["shell", "dumpsys", "window", "windows"])
    if not text.strip():
        # An empty dumpsys means adb never reached a device; say so rather than
        # printing nothing and looking like "no focused window".
        print("no output from dumpsys — is a device attached?", file=sys.stderr)
        return 1
    hits = [
        line.strip()
        for line in text.splitlines()
        if "mCurrentFocus" in line or "mFocusedApp" in line
    ]
    for line in hits:
        print(line)
    if not hits:
        print("no mCurrentFocus/mFocusedApp line in dumpsys window", file=sys.stderr)
        return 1
    return 0


def collect(root: ET.Element) -> tuple[list[dict], list[dict]]:
    """Split nodes into (tappable, hidden). Parse the tree rather than regexing
    the raw XML: a regex happily pairs a node's text with the NEXT node's
    bounds, which silently yields (0,0) taps."""
    live: list[dict] = []
    hidden: list[dict] = []
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        label = text or desc
        if not label:
            continue
        bounds = node.get("bounds")
        point = center(bounds)
        entry = {
            "label": label,
            "id": (node.get("resource-id") or "").split("/")[-1] or None,
            "clickable": node.get("clickable") == "true",
            "bounds": box(bounds),
            "center": point,
        }
        (live if point else hidden).append(entry)
    # Screen reading order, so the output matches what a person would scan.
    live.sort(key=lambda e: (e["center"][1], e["center"][0]))
    return live, hidden


def dedupe(nodes: list[dict]) -> list[dict]:
    """Collapse nodes sharing a label and centre — they are the same target for
    tapping purposes, and repeating them costs tokens for no new information.
    Keyed across the whole list, not just adjacent entries: the same transcript
    line legitimately appears at the top and again further down the scroll."""
    merged: dict[tuple, dict] = {}
    order: list[tuple] = []
    for entry in nodes:
        key = (entry["label"], entry["center"])
        if key in merged:
            merged[key]["count"] += 1
            continue
        merged[key] = dict(entry, count=1)
        order.append(key)
    return [merged[key] for key in order]


def render(nodes: list[dict], use_box: bool) -> list[str]:
    lines = []
    for entry in nodes:
        if use_box and entry["bounds"]:
            x1, y1, x2, y2 = entry["bounds"]
            where = f"[{x1},{y1}][{x2},{y2}]"
        elif entry["center"]:
            where = f"[{entry['center'][0]},{entry['center'][1]}]"
        else:
            # Malformed bounds parse to None; never unpack them.
            where = "(no bounds)"
        suffix = f" ×{entry['count']}" if entry.get("count", 1) > 1 else ""
        ident = f" #{entry['id']}" if entry["id"] else ""
        dead = "" if entry["center"] else "  [hidden]"
        lines.append(f"{where} {entry['label']}{ident}{suffix}{dead}")
    return lines


def main(argv: list[str]) -> int:
    flags = {"--all", "--box", "--json", "--surface", "--tap", "--file"}
    path = None
    if "--file" in argv:
        i = argv.index("--file")
        if i + 1 >= len(argv):
            print("--file needs a path", file=sys.stderr)
            return 2
        path = argv[i + 1]
        argv = argv[:i] + argv[i + 2 :]

    if "--surface" in argv:
        return surface()

    terms = [a for a in argv if a not in flags]
    # Anything else starting with -- is a typo, not a search term: silently
    # treating `--bogus` as a substring turns a mistake into "no matching nodes".
    for arg in terms:
        if arg.startswith("--"):
            print(f"unknown flag {arg}", file=sys.stderr)
            return 2

    try:
        xml = dump(path)
    except OSError as exc:
        print(f"could not read dump: {exc}", file=sys.stderr)
        return 1
    if "<hierarchy" not in xml:
        # uiautomator can exit 0 and still produce nothing — the file is the
        # only reliable success signal, so say so rather than print an empty tree.
        print("empty dump (UI mid-animation, or the surface is not idle yet)", file=sys.stderr)
        return 1
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as exc:
        print(f"parse error: {exc}", file=sys.stderr)
        return 1

    live, hidden = collect(root)
    show_hidden = "--all" in argv
    use_box = "--box" in argv

    if terms:
        wanted = [e for e in live if any(t in e["label"] for t in terms)]
        if not wanted and show_hidden:
            wanted = [e for e in hidden if any(t in e["label"] for t in terms)]
        if not wanted:
            # A label that only exists on a hidden node is the classic
            # false-positive: say which list it came from.
            ghost = [e["label"] for e in hidden if any(t in e["label"] for t in terms)]
            if ghost:
                print(f"only hidden (no bounds) nodes match: {ghost}", file=sys.stderr)
            else:
                print("no matching nodes", file=sys.stderr)
            return 1
        live = wanted

    if "--tap" in argv:
        if not terms:
            # Tapping an arbitrary first node is never what anyone means, and a
            # misfire here is a real action on the device.
            print("--tap needs a TERM to match", file=sys.stderr)
            return 2
        for entry in live:
            if not entry["center"]:
                continue
            cx, cy = entry["center"]
            if write(["input", "tap", str(cx), str(cy)], f"tap {entry['label']!r}") != 0:
                return 1
            print(f"tapped {entry['label']!r} at ({cx},{cy})")
            print("verify with a fresh dump — a tap that adb accepted can still miss", file=sys.stderr)
            return 0
        print("nothing tappable matched", file=sys.stderr)
        return 1

    nodes = dedupe(live)
    if show_hidden:
        # --all must actually append them: a flag that only suppresses the
        # warning below would look like it worked while showing nothing new.
        nodes = nodes + dedupe(hidden)
    if "--json" in argv:
        print(json.dumps(nodes, ensure_ascii=False))
        return 0

    for line in render(nodes, use_box):
        print(line)

    if hidden and not show_hidden:
        labels = sorted({e["label"] for e in hidden})
        print(
            f"— {len(hidden)} hidden node(s) with no bounds, labels: {labels}",
            file=sys.stderr,
        )
        print("   (pass --all to list them; a label here can shadow a real row)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
