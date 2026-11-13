// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.mobile.shadow;

import io.agents.nailongclaw.service.ClawAccessibilityService;
import io.agents.nailongclaw.service.ShadowDisplayManager;
import io.agents.nailongclaw.tool.BaseTool;
import io.agents.nailongclaw.tool.ToolParameter;
import io.agents.nailongclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Taps a node from the shadow tree (ids come from shadow_get_screen_info) on
 * the SHADOW display. The tap is dispatched with a display-scoped gesture —
 * routing these coordinates through the default display is the Mika-style
 * loop trap: nothing would change on the shadow screen and the caller would
 * retry forever.
 *
 * Loop tripwire: if the tap succeeds but the shadow content version does not
 * move, the result says so explicitly — wrong-display routing, a dead shadow
 * app, or a tap on a no-op area. Callers (and StuckDetector, at task level)
 * get a hard signal instead of silently retrying.
 *
 * Risk note: unclassified → DESTRUCTIVE (fail-closed), same tier as tap.
 */
public class ShadowTapNodeTool extends BaseTool {

    @Override
    public String getName() {
        return "shadow_tap_node";
    }

    @Override
    public String getDisplayName() {
        return "影子屏点按节点";
    }

    @Override
    public String getDescriptionEN() {
        return "Tap a node on the shadow (virtual) display. node_id comes from shadow_get_screen_info, never from get_screen_info.";
    }

    @Override
    public String getDescriptionCN() {
        return "Tap a node on the shadow (virtual) display. node_id comes from shadow_get_screen_info, never from get_screen_info.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("node_id", "string", "Node id from the latest shadow_get_screen_info result, e.g. \"n3\"", true)
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
        int[] coords = service.getShadowNodeCoordinates(nodeId);
        if (coords == null) {
            return ToolResult.error("Unknown shadow node " + nodeId + " — refresh with shadow_get_screen_info");
        }
        long before = service.getShadowContentVersion();
        boolean ok = service.performTapOnDisplay(displayId, coords[0], coords[1], 100);
        if (!ok) {
            return ToolResult.error("Failed to tap shadow node " + nodeId + " at (" + coords[0] + ", " + coords[1] + ")");
        }
        service.awaitShadowUiStable(4000, 500);
        long after = service.getShadowContentVersion();
        String message = "Tapped shadow node " + nodeId + " at (" + coords[0] + ", " + coords[1] + ")";
        // Mika's watchMainScreen, pull-based: the target may have surfaced on
        // the real screen (background service / notification jump), in which
        // case this tap hit a shadow screen that no longer shows it.
        String escape = service.shadowEscapeWarning();
        if (escape != null) {
            message = message + "\n" + escape;
        }
        if (after == before) {
            return ToolResult.success(message + "\nWARNING: shadow content did not change after the tap — " +
                    "possible dead shadow app or wrong-display routing. Do not blind-retry; re-read with shadow_get_screen_info.");
        }
        return ToolResult.success(message);
    }
}
