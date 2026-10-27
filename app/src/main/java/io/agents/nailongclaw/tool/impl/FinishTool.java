// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;
import io.agents.nailongclaw.tool.BaseTool;
import io.agents.nailongclaw.tool.ToolParameter;
import io.agents.nailongclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FinishTool extends BaseTool {

    @Override
    public String getName() {
        return "finish";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_finish);
    }

    @Override
    public String getDescriptionEN() {
        return "Signal that the current task is complete. Call this when you have successfully accomplished the user's request. Provide a summary of what was done.";
    }

    @Override
    public String getDescriptionCN() {
        return "Mark the current task as complete. Call this tool when the user's request has been successfully fulfilled. Provide a summary of what was accomplished.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("summary", "string", "A brief summary of what was accomplished", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String summary = requireString(params, "summary");
        return ToolResult.success("Task completed: " + summary);
    }
}
