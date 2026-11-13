// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.mobile;

import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.nailongclaw.ClawApplication;
import io.agents.nailongclaw.R;
import io.agents.nailongclaw.service.ClawAccessibilityService;
import io.agents.nailongclaw.tool.BaseTool;
import io.agents.nailongclaw.tool.ToolParameter;
import io.agents.nailongclaw.tool.ToolResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Scroll-to-find tool: automatically scrolls the current page and finds elements containing the specified text.
 * Returns the element's coordinate info when found, avoiding the LLM loop of repeatedly calling get_screen_info + swipe.
 */
public class ScrollToFindTool extends BaseTool {

    @Override
    public String getName() {
        return "scroll_to_find";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_scroll_to_find);
    }

    @Override
    public String getDescriptionEN() {
        return "Scroll the screen to find an element containing the specified text. "
                + "Automatically scrolls in the given direction and searches after each scroll. "
                + "Returns the element's bounds and center coordinates if found. "
                + "Much more efficient than manually calling swipe + get_screen_info in a loop.";
    }

    @Override
    public String getDescriptionCN() {
        return "Scroll the screen to find an element containing the specified text. Automatically scrolls in the given direction and searches after each scroll."
                + " Returns the element's bounds and center coordinates when found. Much more efficient than manually looping swipe + get_screen_info.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("text", "string", "The text to search for on the screen", true),
                new ToolParameter("direction", "string",
                        "Scroll direction: 'up' or 'down' (default 'down'). 'down' means content moves up to reveal lower content.", false),
                new ToolParameter("max_scrolls", "integer",
                        "Maximum number of scrolls to attempt (default 10, max 20)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = requireString(params, "text");
        String direction = optionalString(params, "direction", "down");
        int maxScrolls = optionalInt(params, "max_scrolls", 10);
        maxScrolls = Math.min(Math.max(maxScrolls, 1), 20);

        // Get screen size
        int[] screenSize = getScreenSize();
        int screenWidth = screenSize[0];
        int screenHeight = screenSize[1];

        // Scroll parameters: scroll in the middle region of the screen, avoiding the top status bar and bottom nav bar
        int centerX = screenWidth / 2;
        int scrollStartY, scrollEndY;
        if ("up".equals(direction)) {
            // Scroll up (content moves down): swipe from top to bottom
            scrollStartY = (int) (screenHeight * 0.3);
            scrollEndY = (int) (screenHeight * 0.7);
        } else {
            // Scroll down (content moves up): swipe from bottom to top
            scrollStartY = (int) (screenHeight * 0.7);
            scrollEndY = (int) (screenHeight * 0.3);
        }

        // First search on current screen (no scroll)
        ToolResult found = findElement(service, text);
        if (found != null) {
            return found;
        }

        // Loop: scroll → find
        for (int i = 0; i < maxScrolls; i++) {
            // Bottom/top detection rides the content version, not a second
            // full tree pull: a swipe that produced no accessibility event
            // moved no content. The old shape pulled the whole tree twice per
            // scroll (once before the swipe, once after) just to compare
            // strings — that is where this tool's minutes went.
            long beforeScroll = service.getContentVersion();
            // Perform swipe
            boolean swiped = service.performSwipe(centerX, scrollStartY, centerX, scrollEndY, 400);
            if (!swiped) {
                return ToolResult.error("Swipe failed at scroll #" + (i + 1));
            }

            // Wait for page to settle (event-driven: returns as soon as the
            // screen has been quiet, instead of a blind 500ms)
            service.awaitUiStable(2000, 400);

            // Search for target
            found = findElement(service, text);
            if (found != null) {
                return found;
            }

            // Detect if we reached the bottom/top (screen content no longer changes)
            if (service.getContentVersion() == beforeScroll) {
                return ToolResult.error("Element with text \"" + text + "\" not found. "
                        + "Reached the " + ("up".equals(direction) ? "top" : "bottom")
                        + " after " + (i + 1) + " scroll(s).");
            }
        }

        return ToolResult.error("Element with text \"" + text + "\" not found after " + maxScrolls + " scroll(s).");
    }

    /**
     * Search for an element containing the specified text on the current screen. Returns ToolResult if found, null if not found.
     */
    private ToolResult findElement(ClawAccessibilityService service, String text) {
        List<AccessibilityNodeInfo> nodes = service.findNodesByText(text);
        if (nodes.isEmpty()) {
            return null;
        }
        try {
            // Take the first visible node
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isVisibleToUser()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    int centerX = bounds.centerX();
                    int centerY = bounds.centerY();
                    StringBuilder sb = new StringBuilder();
                    sb.append("Found element with text \"").append(text).append("\"");
                    sb.append("\n  bounds=").append(bounds.toShortString());
                    sb.append("\n  center=(").append(centerX).append(", ").append(centerY).append(")");
                    sb.append("\n  clickable=").append(node.isClickable());
                    if (node.getClassName() != null) {
                        sb.append("\n  class=").append(node.getClassName());
                    }
                    return ToolResult.success(sb.toString());
                }
            }
            // All nodes are out of bounds
            return null;
        } finally {
            ClawAccessibilityService.recycleNodes(nodes);
        }
    }

    // getScreenSize() is provided by BaseTool
}
