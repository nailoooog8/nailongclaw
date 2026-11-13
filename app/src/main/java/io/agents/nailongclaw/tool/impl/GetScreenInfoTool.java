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
            // A null root used to be reported straight as SYSTEM_DIALOG_BLOCKED,
            // which DefaultAgentService turns into an immediate task kill. But
            // the root is also null for a beat during every window switch —
            // those transitions were killing healthy tasks. One settle-retry
            // clears the transition; what survives it is a real block.
            service.awaitUiStable(600, 200);
            tree = useFullTree ? service.getScreenTreeFull() : service.getScreenTree();
        }
        if (tree == null) {
            // dsh-Go's hidden-vs-unreadable window split: no windows at all
            // means the foreground went away (transition or background) and is
            // worth a retry; windows present but unreadable is the genuine
            // blocking case that deserves the user-notification flow.
            if (service.hasNoWindows()) {
                return ToolResult.error("No active window right now (the screen may be transitioning, or the app "
                        + "went to the background). Retry get_screen_info shortly — do not finish the task.");
            }
            return ToolResult.error(SYSTEM_DIALOG_BLOCKED);
        }
        return ToolResult.success(tree);
    }
}
