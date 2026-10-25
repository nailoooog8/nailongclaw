// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import android.service.notification.StatusBarNotification;

import io.agents.pokeclaw.service.ClawNotificationListener;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;
import io.agents.pokeclaw.utils.ContactMatchUtils;
import io.agents.pokeclaw.utils.NotificationReplyUtils;
import io.agents.pokeclaw.utils.XLog;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Reply to a messaging notification through its own direct-reply action.
 *
 * This is the fastest possible reply path: no app is opened, nothing is
 * tapped, the screen can even be off. It only works when a recent
 * notification from the conversation is still active AND the app attached a
 * standard RemoteInput reply action (WhatsApp, Telegram, Google Messages,
 * Signal, SMS do; WeChat/QQ do not). On failure, fall back to send_message.
 */
public class ReplyViaNotificationTool extends BaseTool {

    private static final String TAG = "ReplyViaNotif";

    @Override
    public String getName() {
        return "reply_via_notification";
    }

    @Override
    public String getDisplayName() {
        return "Reply via Notification";
    }

    @Override
    public String getDescriptionEN() {
        return "Reply to a recent messaging notification directly, WITHOUT opening the app or touching the UI. "
                + "Much faster and quieter than send_message. Requires a still-active notification from that "
                + "conversation whose app supports direct reply (WhatsApp, Telegram, Messages, Signal, SMS). "
                + "If it fails (no replyable notification), fall back to send_message.";
    }

    @Override
    public String getDescriptionCN() {
        return "Reply to the most recent messaging notification directly through the notification's own reply action, "
                + "without opening the app or operating the UI, and works even when the screen is off. "
                + "Requires that a notification from that conversation is still active and the app supports direct reply "
                + "(WhatsApp, Telegram, Messages, Signal, SMS). On failure, fall back to send_message.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("message", "string", "The reply text to send", true),
                new ToolParameter("contact", "string",
                        "Contact name of the conversation (matched against the notification title)", true),
                new ToolParameter("app", "string",
                        "Messaging app name (default: WhatsApp)", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        if (!ClawNotificationListener.isConnected()) {
            return ToolResult.error("Notification Access is not enabled. Ask the user to enable it in Settings, "
                    + "or use send_message instead.");
        }

        String message = requireString(params, "message");
        String contact = requireString(params, "contact");
        String app = params.containsKey("app") ? params.get("app").toString().trim() : "WhatsApp";

        String packageName = OpenAppTool.resolveAppNameStatic(app);
        if (packageName == null) packageName = app;

        LinkedHashSet<String> normalizedAliases = ContactMatchUtils.buildNormalizedAliases(contact);
        LinkedHashSet<String> digitAliases = ContactMatchUtils.buildDigitAliases(contact);

        StatusBarNotification sbn = NotificationReplyUtils.findReplyableNotification(
                packageName, normalizedAliases, digitAliases);
        if (sbn == null) {
            return ToolResult.error("No active replyable notification for '" + contact + "' on " + app + ". "
                    + "The conversation has no recent notification, or the app does not support direct reply "
                    + "(WeChat/QQ do not). Use send_message instead.");
        }

        XLog.i(TAG, "Replying to " + NotificationReplyUtils.describe(sbn) + " via direct-reply action");

        String error = NotificationReplyUtils.sendReply(sbn, message);
        if (error != null) {
            return ToolResult.error("Direct reply failed: " + error + ". Use send_message instead.");
        }

        // Dismiss only the notification we replied through — the app's other
        // unread conversations must survive this tool call.
        ClawNotificationListener.dismissNotification(sbn);
        return ToolResult.success("Replied to '" + contact + "' via notification direct reply (app was not opened): "
                + message);
    }
}
