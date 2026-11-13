// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.mobile.shadow;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.nailongclaw.service.ClawAccessibilityService;
import io.agents.nailongclaw.service.ShadowDisplayManager;
import io.agents.nailongclaw.tool.BaseTool;
import io.agents.nailongclaw.tool.ToolParameter;
import io.agents.nailongclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Types text into an editable node on the SHADOW display. The shadow screen
 * could already be tapped and swiped but not written into, which left every
 * search box unreachable — this is the first rung of Mika's four-way input
 * chain (SET_TEXT → clipboard paste → injected ASCII → long-press menu).
 *
 * Discipline borrowed from dsh-Go's MainScreenDriver, and the reason this tool
 * looks nothing like InputTextTool's retry loops:
 * - one actual SET_TEXT request, no blind retry (a refusal is reported, the
 *   model decides what to try next);
 * - the write is VERIFIED by reading the same node back — only the exact
 *   requested text counts as success, polled because some apps apply it
 *   asynchronously;
 * - the clipboard is never touched (dsh-Go: "never overwrite the clipboard to
 *   probe paste support").
 *
 * Known limit: apps whose UI is not backed by the accessibility node tree
 * (some Flutter/React-Native text fields) refuse SET_TEXT outright; the error
 * says so instead of pretending the text landed.
 *
 * Risk note: unclassified → DESTRUCTIVE (fail-closed), same tier as the other
 * shadow tools until the shadow surface passes its security review (R4-B1).
 */
public class ShadowInputTextTool extends BaseTool {

    /** Read-back poll: 100ms interval, 1.5s deadline (dsh-Go's numbers). */
    private static final int READ_BACK_ATTEMPTS = 15;
    private static final long READ_BACK_INTERVAL_MS = 100L;

    @Override
    public String getName() {
        return "shadow_input_text";
    }

    @Override
    public String getDisplayName() {
        return "影子屏输入文本";
    }

    @Override
    public String getDescriptionEN() {
        return "Type text into an editable node on the shadow (virtual) display. node_id comes from shadow_get_screen_info. Verified by reading the node back; refuses instead of blind-retrying.";
    }

    @Override
    public String getDescriptionCN() {
        return "Type text into an editable node on the shadow (virtual) display. node_id comes from shadow_get_screen_info. Verified by reading the node back; refuses instead of blind-retrying.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("node_id", "string", "Editable node id from the latest shadow_get_screen_info result, e.g. \"n3\"", true),
                new ToolParameter("text", "string", "Text to set on that node", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        Integer displayId = ShadowDisplayManager.getDisplayIdOrNull();
        if (displayId == null) {
            return ToolResult.error("No shadow display. Create it first (Tools page entry) and retry.");
        }
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String nodeId = requireString(params, "node_id");
        String text = requireString(params, "text");

        // A fresh walk, not a stored reference: ids reproduce only while the
        // tree is unchanged, and a stale node would silently write elsewhere.
        AccessibilityNodeInfo node = service.findShadowNodeById(nodeId);
        if (node == null) {
            return ToolResult.error("Unknown shadow node " + nodeId + " — the tree changed; refresh with shadow_get_screen_info");
        }
        try {
            if (!node.isEditable()) {
                return ToolResult.error("Shadow node " + nodeId + " is not a text field (no \"edit\" marker in shadow_get_screen_info)");
            }
            if (!node.refresh()) {
                return ToolResult.error("Shadow node " + nodeId + " went stale before the write — refresh with shadow_get_screen_info");
            }
            // Focus first: SET_TEXT on an unfocused field is refused by some
            // apps. A prerequisite, not a retry.
            if (!node.isFocused()) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return ToolResult.error("SET_TEXT refused by shadow node " + nodeId
                        + " — the app may not support accessibility text input. Do not blind-retry.");
            }

            // Read-back verification: only the exact requested text on THIS
            // node counts. Poll, because some apps apply the text a beat later.
            for (int attempt = 0; attempt < READ_BACK_ATTEMPTS; attempt++) {
                if (!node.refresh()) {
                    break; // node went stale mid-verification
                }
                CharSequence current = node.getText();
                if (current != null && text.contentEquals(current)) {
                    return ToolResult.success("Set text on shadow node " + nodeId + " (verified by read-back)");
                }
                try {
                    Thread.sleep(READ_BACK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return ToolResult.error("Text was sent to shadow node " + nodeId + " but did not read back within 1.5s — "
                    + "the app may have ignored or transformed it. Re-read with shadow_get_screen_info before acting again.");
        } finally {
            node.recycle();
        }
    }
}
