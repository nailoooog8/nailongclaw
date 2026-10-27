// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.tool.impl.mobile;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import io.agents.nailongclaw.service.ClawAccessibilityService;
import io.agents.nailongclaw.tool.BaseTool;
import io.agents.nailongclaw.tool.ToolParameter;
import io.agents.nailongclaw.tool.ToolResult;
import io.agents.nailongclaw.utils.ChatNoiseFilterUtils;
import io.agents.nailongclaw.utils.XLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the conversation visible in an open chat screen straight from the
 * accessibility node tree — one call replaces the screenshot → look → swipe →
 * screenshot loop. Messages are attributed by horizontal position (left of
 * centre = "them", right = "me"), the same heuristic the auto-reply pipeline
 * uses.
 *
 * When older history is needed, pass scroll_back: the tool scrolls the
 * conversation list semantically (ACTION_SCROLL_FORWARD), waits for the
 * content to settle, re-reads, and merges. Each pass covers one screenful;
 * later passes hold strictly older messages and are PREPENDED, so the merged
 * output always reads oldest → newest regardless of how far back it scrolled.
 */
public class ReadConversationTool extends BaseTool {

    private static final String TAG = "ReadConversation";
    private static final int MAX_MESSAGE_LENGTH = 500;

    @Override
    public String getName() {
        return "read_conversation";
    }

    @Override
    public String getDisplayName() {
        return "Read Conversation";
    }

    @Override
    public String getDescriptionEN() {
        return "Read the messages of the chat screen that is currently open, directly from the accessibility tree — "
                + "no screenshot needed. Returns lines like 'them: ...' (incoming, left-aligned) and 'me: ...' "
                + "(outgoing, right-aligned), oldest first. Open the chat first (or reply via notification context). "
                + "Use scroll_back=N to also load N screens of older history via semantic scrolling.";
    }

    @Override
    public String getDescriptionCN() {
        return "Read the messages of the currently open chat screen directly from the accessibility node tree, "
                + "no screenshot needed. Returns 'them: ...' (the other party, left-aligned) / 'me: ...' (this side, right-aligned), "
                + "ordered from oldest to newest. The chat must be open first. Use scroll_back=N to additionally load "
                + "N screens of earlier history via semantic scrolling.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("max_messages", "integer",
                        "Maximum number of messages to return, keeping the most recent (default 15)", false),
                new ToolParameter("scroll_back", "integer",
                        "How many screens of older history to load via semantic scrolling (default 0 = visible only)",
                        false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        int maxMessages = optionalInt(params, "max_messages", 15);
        int scrollBack = optionalInt(params, "scroll_back", 0);

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return ToolResult.error("No active window. Open the chat first, then call read_conversation.");
        }

        // One pass per screenful, internally top-to-bottom (oldest→newest).
        // Later passes contain strictly older messages and go to the FRONT.
        List<List<String>> passes = new ArrayList<>();
        List<String> firstPass = new ArrayList<>();
        int visibleCount = readPass(root, firstPass);
        passes.add(firstPass);

        int scrollsDone = 0;
        for (int i = 0; i < scrollBack; i++) {
            AccessibilityNodeInfo scrollable = findLargestScrollableNode(root);
            if (scrollable == null) {
                XLog.d(TAG, "No scrollable conversation container — stopping after " + scrollsDone + " scrolls");
                break;
            }
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                XLog.d(TAG, "ACTION_SCROLL_FORWARD returned false — reached the top of the history");
                break;
            }
            scrollsDone++;
            service.awaitUiStable(2500, 450);
            root = service.getRootInActiveWindow();
            if (root == null) break;
            List<String> olderPass = new ArrayList<>();
            readPass(root, olderPass);
            passes.add(0, olderPass);
        }

        // Merge in order (oldest pass first), deduplicating overlap between
        // adjacent passes by the rendered line itself.
        Set<String> seen = new HashSet<>();
        List<String> lines = new ArrayList<>();
        for (List<String> pass : passes) {
            for (String line : pass) {
                if (seen.add(line)) lines.add(line);
            }
        }

        if (lines.isEmpty()) {
            return ToolResult.success("No chat messages detected on the current screen. "
                    + "This tool needs an open chat conversation (with message bubbles), not a chat list.");
        }

        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - maxMessages);
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }

        XLog.i(TAG, "Read " + visibleCount + " visible messages, " + scrollsDone + " history scrolls, "
                + lines.size() + " merged, returning last " + (lines.size() - from));
        // Another app's chat history: someone else wrote every line, and that
        // someone may be trying to talk to the agent through it. Fence as data.
        return ToolResult.success(
                io.agents.nailongclaw.agent.ContextFence.wrap("conversation", sb.toString().trim()));
    }

    /**
     * Reads the visible messages of one screenful into [out] as
     * "them: ..." / "me: ..." lines in top-to-bottom (oldest→newest) order.
     * Returns how many message lines the pass produced.
     */
    private int readPass(AccessibilityNodeInfo root, List<String> out) {
        List<AccessibilityNodeInfo> textNodes = new ArrayList<>();
        collectTextNodes(root, textNodes);

        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int midX = rootBounds.centerX();

        int count = 0;
        for (AccessibilityNodeInfo node : textNodes) {
            CharSequence text = node.getText();
            if (text == null || text.length() == 0 || text.length() > MAX_MESSAGE_LENGTH) continue;

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            // Skip the toolbar (top) and the composer/IME area (bottom).
            if (bounds.top < 300 || bounds.top > rootBounds.height() - 250) continue;

            String msg = text.toString();
            if (ChatNoiseFilterUtils.isLikelyNonMessageLabel(bounds, rootBounds, msg)) continue;

            String speaker = bounds.centerX() < midX ? "them" : "me";
            out.add(speaker + ": " + msg);
            count++;
        }
        return count;
    }

    /**
     * Collects visible text nodes WITHOUT recycling them: the collected nodes
     * are read (and scrolled) after this walk returns, and on API 28-32 a
     * recycled AccessibilityNodeInfo can throw on later access. The
     * no-recycle convention matches SendMessageTool/AutoReplyManager.
     */
    private void collectTextNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.isVisibleToUser() && node.getText() != null && node.getText().length() > 0) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTextNodes(child, result);
            }
        }
    }

    /** The scrollable node with the largest on-screen area — the conversation list. */
    private AccessibilityNodeInfo findLargestScrollableNode(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> scrollables = new ArrayList<>();
        collectScrollableNodes(root, scrollables);
        AccessibilityNodeInfo best = null;
        long bestArea = 0;
        for (AccessibilityNodeInfo node : scrollables) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            long area = (long) bounds.width() * bounds.height();
            if (area > bestArea) {
                bestArea = area;
                best = node;
            }
        }
        return best;
    }

    /** See [collectTextNodes]: no recycle — the winner is acted on afterwards. */
    private void collectScrollableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.isVisibleToUser() && node.isScrollable()) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectScrollableNodes(child, result);
            }
        }
    }
}
