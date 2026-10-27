// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;
import io.agents.nailongclaw.service.ClawAccessibilityService;
import io.agents.nailongclaw.tool.BaseTool;
import io.agents.nailongclaw.tool.ToolParameter;
import io.agents.nailongclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GetScreenInfoTool extends BaseTool {

    @Override
    public String getName() {
        return "get_screen_info";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_get_screen_info);
    }

    @Override
    public String getDescriptionEN() {
        return "Get the current screen's UI elements. Each element has a node ID (e.g. [n3]) that can be used with tap_node. Do not cache this result — node IDs change on each call.";
    }

    @Override
    public String getDescriptionCN() {
        return "Get the current screen's UI elements. Each element has a node ID (e.g. [n3]) that can be used with tap_node. Do not cache this result — node IDs change on each call.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    public static final String SYSTEM_DIALOG_BLOCKED = "__SYSTEM_DIALOG_BLOCKED__";

    /**
     * Switch to full node tree mode (includes all nodes and all attributes, for debugging).
     * false = compact mode (default, saves tokens); true = full mode.
     */
    public static boolean useFullTree = false;

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String tree = useFullTree ? service.getScreenTreeFull() : service.getScreenTree();
        if (tree == null) {
            return ToolResult.error(SYSTEM_DIALOG_BLOCKED);
        }
        return ToolResult.success(tree);
    }
}
