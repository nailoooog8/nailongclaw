// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.mobile;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;
import io.agents.nailongclaw.service.ClawAccessibilityService;
import io.agents.nailongclaw.tool.BaseTool;
import io.agents.nailongclaw.tool.ToolParameter;
import io.agents.nailongclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Tap a UI element by its node ID (e.g. "n3") from get_screen_info output.
 * More reliable than coordinate-based tap — IDs are assigned per screen refresh.
 */
public class TapNodeTool extends BaseTool {

    @Override
    public String getName() {
        return "tap_node";
    }

    @Override
    public String getDisplayName() {
        return "Tap Node";
    }

    @Override
    public String getDescriptionEN() {
        return "Tap a UI element by its node ID (e.g. \"n3\") from the screen info. More reliable than raw coordinates.";
    }

    @Override
    public String getDescriptionCN() {
        return "Tap a UI element by its node ID (e.g. \"n3\") from the screen info. More reliable than raw coordinates.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("node_id", "string", "Node ID from screen info, e.g. n3", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String nodeId = requireString(params, "node_id");
        if (nodeId == null || nodeId.isEmpty()) {
            return ToolResult.error("node_id is required");
        }
        // Normalize: strip brackets if user passes "[n3]"
        nodeId = nodeId.replace("[", "").replace("]", "").trim();

        int[] coords = service.getNodeCoordinates(nodeId);
        if (coords == null) {
            return ToolResult.error("Node " + nodeId + " not found. Call get_screen_info first to refresh node IDs.");
        }
        int x = coords[0];
        int y = coords[1];
        String boundsError = validateCoordinates(x, y);
        if (boundsError != null) return ToolResult.error(boundsError);
        boolean success = service.performTap(x, y);
        return success ? ToolResult.success("Tapped node " + nodeId + " at (" + x + ", " + y + ")")
                : ToolResult.error("Failed to tap node " + nodeId + " at (" + x + ", " + y + ")");
    }
}
