// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.mobile.shadow;

import android.graphics.Bitmap;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.service.ClawAccessibilityService;
import io.agents.nailongclaw.tool.BaseTool;
import io.agents.nailongclaw.tool.ToolParameter;
import io.agents.nailongclaw.tool.ToolResult;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads the SHADOW display's UI tree (node ids are shadow-scoped — they feed
 * shadow_tap_node, never tap_node). Optional PNG capture of the shadow
 * display via the same screenshot pipeline as take_screenshot.
 *
 * Risk note: deliberately unclassified → DESTRUCTIVE (fail-closed). Reading a
 * hidden display is a wider screen-read surface than get_screen_info; it must
 * not ride the remote READ_ONLY allowlist before the R4-B1 fencing work.
 */
public class ShadowGetScreenInfoTool extends BaseTool {

    @Override
    public String getName() {
        return "shadow_get_screen_info";
    }

    @Override
    public String getDisplayName() {
        return "影子屏读取界面";
    }

    @Override
    public String getDescriptionEN() {
        return "Read the UI tree of the shadow (virtual) display. Set screenshot=true to also save a PNG of it. Node ids returned here are used by shadow_tap_node only.";
    }

    @Override
    public String getDescriptionCN() {
        return "Read the UI tree of the shadow (virtual) display. Set screenshot=true to also save a PNG of it. Node ids returned here are used by shadow_tap_node only.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("screenshot", "boolean", "Also save a PNG of the shadow display and return its path", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String tree = service.getShadowScreenTree();
        if (tree == null || tree.isEmpty()) {
            // An EMPTY shadow screen is the classic escape shape (the task
            // surfaced on the real screen and took its window away), so the
            // watchdog must run on this path too — with the check only on the
            // success path, the warning could never fire exactly when the
            // escape happened (found on emulator QA 2026-11-15: removing the
            // shadow-side task silenced the warning and returned a plain
            // "no readable window" error).
            String escape = service.shadowEscapeWarning();
            String message = "Shadow display has no readable window. Launch an app onto it first (shadow_open_app).";
            return escape != null ? ToolResult.error(message + "\n" + escape) : ToolResult.error(message);
        }

        StringBuilder result = new StringBuilder(tree);
        if (optionalBoolean(params, "screenshot", false)) {
            Bitmap bitmap = service.takeShadowScreenshot(5000);
            if (bitmap == null) {
                result.append("\n(screenshot failed)");
            } else {
                try {
                    Bitmap soft = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    if (soft != null) {
                        bitmap.recycle();
                        bitmap = soft;
                    }
                    File dir = new File(ClawApplication.Companion.getInstance().getCacheDir(), "screenshots");
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, System.currentTimeMillis() + "_shadow.png");
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                    io.agents.nailongclaw.tool.impl.ScreenshotCache.prune(dir);  // 24h / 20 files (R5 sec5 P3)
                    result.append("\n(screenshot: ").append(file.getAbsolutePath()).append(")");
                } catch (Exception e) {
                    result.append("\n(screenshot failed: ").append(e.getMessage()).append(")");
                } finally {
                    bitmap.recycle();
                }
            }
        }
        // Escape watchdog (Mika's watchMainScreen): reading the shadow tree is
        // also an action, and an app that spilled to the real screen makes the
        // next tap a no-op loop — say so before the model acts on this tree.
        String escape = service.shadowEscapeWarning();
        if (escape != null) {
            result.append("\n").append(escape);
        }
        return ToolResult.success(result.toString());
    }
}
