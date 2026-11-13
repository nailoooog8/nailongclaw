// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.mobile;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;
import io.agents.nailongclaw.service.ClawAccessibilityService;
import io.agents.nailongclaw.tool.BaseTool;
import io.agents.nailongclaw.tool.ToolParameter;
import io.agents.nailongclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TapTool extends BaseTool {

    @Override
    public String getName() {
        return "tap";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_tap);
    }

    @Override
    public String getDescriptionEN() {
        return "Tap at the specified screen coordinates (x, y).";
    }

    @Override
    public String getDescriptionCN() {
        return "Tap at the specified screen coordinates (x, y).";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("x", "integer", "X coordinate on screen", true),
                new ToolParameter("y", "integer", "Y coordinate on screen", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        int x = requireInt(params, "x");
        int y = requireInt(params, "y");
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        // Loop tripwire (same discipline as the shadow tools): the gesture can
        // be dispatched "successfully" onto a screen that then does not move.
        // Compare content versions around the action and say so, instead of
        // reporting a success that invites a blind retry.
        long before = service.getContentVersion();
        boolean success = service.performTap(x, y);
        if (!success) {
            return ToolResult.error("Failed to tap at (" + x + ", " + y + ")");
        }
        service.awaitUiStable(2000, 400);
        String message = "Tapped at (" + x + ", " + y + ")";
        if (service.getContentVersion() == before) {
            return ToolResult.success(message + noChangeWarning("the tap"));
        }
        return ToolResult.success(message);
    }
}
