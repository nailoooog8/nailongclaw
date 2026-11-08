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
 * Launches an app onto the shadow display (虚拟屏) so automation runs off the
 * real screen. Refuses outright when no shadow display exists — silently
 * falling back to the default display would race the user's own screen.
 *
 * Risk note: left unclassified on purpose, which ToolRiskPolicy turns into
 * DESTRUCTIVE (fail-closed, local interactive use only) until the shadow
 * screen-read surface has been through a security review (R4-B1 follow-up).
 */
public class ShadowOpenAppTool extends BaseTool {

    @Override
    public String getName() {
        return "shadow_open_app";
    }

    @Override
    public String getDisplayName() {
        return "影子屏打开应用";
    }

    @Override
    public String getDescriptionEN() {
        return "Launch an app on the shadow (virtual) display, keeping the real screen untouched.";
    }

    @Override
    public String getDescriptionCN() {
        return "Launch an app on the shadow (virtual) display, keeping the real screen untouched.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("package_name", "string", "Package name of the app to launch, e.g. com.ss.android.ugc.aweme", true)
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
        String packageName = requireString(params, "package_name");
        // Mika's anti-disturb gate, borrowed: the user holding this app on the
        // real screen must not get it yanked to the shadow display mid-use.
        // Unscannable main screen refuses too — fail closed, matching the
        // DESTRUCTIVE-until-reviewed stance in the class KDoc.
        Boolean inUse = service.mainScreenUsage(packageName);
        if (inUse == null) {
            return ToolResult.error("Cannot inspect the real screen right now — refusing to launch "
                    + packageName + " to avoid disturbing the user. Retry shortly.");
        }
        if (inUse) {
            return ToolResult.error("Refused: " + packageName + " is open on the real screen and may be in use."
                    + " Pick a different app, or tell the user to close it first.");
        }
        String error = service.openAppOnDisplay(packageName, displayId);
        if (error != null) {
            return ToolResult.error("Failed to launch " + packageName + " on shadow display " + displayId + ": " + error);
        }
        // Wait briefly for the shadow UI to appear before returning, so the
        // first get_screen_info call right after does not race the cold start.
        service.awaitShadowUiStable(8000, 600);
        return ToolResult.success("Launched " + packageName + " on shadow display " + displayId);
    }
}
