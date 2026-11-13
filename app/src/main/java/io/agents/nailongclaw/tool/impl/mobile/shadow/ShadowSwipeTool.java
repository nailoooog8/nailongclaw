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
 * Swipes on the SHADOW display, display-scoped like shadow_tap_node. Same
 * loop tripwire: a swipe that changes nothing on the shadow display is
 * reported as such instead of being silently "successful".
 *
 * Risk note: unclassified → DESTRUCTIVE (fail-closed), same tier as swipe.
 */
public class ShadowSwipeTool extends BaseTool {

    @Override
    public String getName() {
        return "shadow_swipe";
    }

    @Override
    public String getDisplayName() {
        return "影子屏滑动";
    }

    @Override
    public String getDescriptionEN() {
        return "Swipe on the shadow (virtual) display. Coordinates are in the shadow display's space (see shadow_get_screen_info).";
    }

    @Override
    public String getDescriptionCN() {
        return "Swipe on the shadow (virtual) display. Coordinates are in the shadow display's space (see shadow_get_screen_info).";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("x1", "integer", "Start X (shadow display space)", true),
                new ToolParameter("y1", "integer", "Start Y (shadow display space)", true),
                new ToolParameter("x2", "integer", "End X (shadow display space)", true),
                new ToolParameter("y2", "integer", "End Y (shadow display space)", true),
                new ToolParameter("duration_ms", "integer", "Gesture duration in ms", false)
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
        int x1 = requireInt(params, "x1");
        int y1 = requireInt(params, "y1");
        int x2 = requireInt(params, "x2");
        int y2 = requireInt(params, "y2");
        long durationMs = optionalLong(params, "duration_ms", 300L);

        long before = service.getShadowContentVersion();
        boolean ok = service.performSwipeOnDisplay(displayId, x1, y1, x2, y2, durationMs);
        if (!ok) {
            return ToolResult.error("Failed to swipe on shadow display");
        }
        service.awaitShadowUiStable(4000, 500);
        long after = service.getShadowContentVersion();
        String message = "Swiped on shadow display (" + x1 + "," + y1 + ") → (" + x2 + "," + y2 + ")";
        // Mika's watchMainScreen, pull-based — see ShadowTapNodeTool.
        String escape = service.shadowEscapeWarning();
        if (escape != null) {
            message = message + "\n" + escape;
        }
        if (after == before) {
            return ToolResult.success(message + "\nWARNING: shadow content did not change after the swipe — " +
                    "re-read with shadow_get_screen_info instead of blind-retrying.");
        }
        return ToolResult.success(message);
    }
}
