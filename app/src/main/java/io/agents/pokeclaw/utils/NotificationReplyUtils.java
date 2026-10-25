// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.service.ClawNotificationListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Replies to a messaging notification through its own direct-reply action
 * (RemoteInput), without opening the app or touching the UI at all.
 *
 * This is the same channel Android Auto and the old Google Assistant
 * "read my messages" flow used: the posting app's notification carries a
 * PendingIntent that accepts reply text, so sending it needs no accessibility
 * service, no unlocked screen, and no screenshots.
 *
 * Limits: only apps that attach a standard RemoteInput reply action to their
 * notifications can be reached this way (WhatsApp, Telegram, Google Messages,
 * Signal, SMS...). WeChat/QQ ship custom notifications without one and must
 * stay on the accessibility path.
 */
public final class NotificationReplyUtils {

    private static final String TAG = "NotifReplyUtils";

    private NotificationReplyUtils() {
    }

    /** True when the notification exposes a usable direct-reply action. */
    public static boolean hasDirectReplyAction(StatusBarNotification sbn) {
        return findReplyAction(sbn) != null;
    }

    /**
     * Finds the most recent active notification that (a) belongs to
     * [packageName], (b) carries a direct-reply action, and (c) — when
     * contact aliases are given — has a title matching that contact.
     * Falls back to the newest replyable notification from the package when
     * no title matches.
     */
    public static StatusBarNotification findReplyableNotification(
            String packageName,
            LinkedHashSet<String> normalizedAliases,
            LinkedHashSet<String> digitAliases
    ) {
        StatusBarNotification[] active = ClawNotificationListener.getActiveNotificationList();
        if (active == null || packageName == null) return null;

        List<StatusBarNotification> replyable = new ArrayList<>();
        for (StatusBarNotification sbn : active) {
            if (!packageName.equals(sbn.getPackageName())) continue;
            if (!hasDirectReplyAction(sbn)) continue;
            replyable.add(sbn);
        }
        if (replyable.isEmpty()) return null;

        replyable.sort(Comparator.comparingLong(StatusBarNotification::getPostTime).reversed());

        if (normalizedAliases != null && !normalizedAliases.isEmpty()) {
            for (StatusBarNotification sbn : replyable) {
                String title = notificationTitle(sbn);
                if (ContactMatchUtils.matchesCandidate(title, normalizedAliases, digitAliases)) {
                    return sbn;
                }
            }
            // A contact was requested but no notification matches it — do not
            // silently reply to a different conversation.
            return null;
        }
        return replyable.get(0);
    }

    /**
     * Sends [message] through the notification's direct-reply action.
     *
     * @return null on success, a human-readable failure reason otherwise.
     */
    public static String sendReply(StatusBarNotification sbn, String message) {
        Notification.Action replyAction = findReplyAction(sbn);
        if (replyAction == null) {
            return "notification has no direct-reply action";
        }

        Context context = ClawApplication.Companion.getInstance();
        if (context == null) {
            return "application context unavailable";
        }

        Intent replyIntent = new Intent();
        Bundle results = new Bundle();
        android.app.RemoteInput[] originals = replyAction.getRemoteInputs();
        android.app.RemoteInput[] rebuilt = new android.app.RemoteInput[originals.length];
        for (int i = 0; i < originals.length; i++) {
            results.putCharSequence(originals[i].getResultKey(), message);
            rebuilt[i] = new android.app.RemoteInput.Builder(originals[i].getResultKey())
                    .setAllowFreeFormInput(true)
                    .build();
        }
        android.app.RemoteInput.addResultsToIntent(rebuilt, replyIntent, results);

        try {
            replyAction.actionIntent.send(context, 0, replyIntent);
        } catch (PendingIntent.CanceledException e) {
            XLog.w(TAG, "Reply PendingIntent canceled for " + sbn.getPackageName(), e);
            return "reply action was canceled by the app (notification may be stale)";
        }
        return null;
    }

    /** A short, human-readable description of the notification's conversation. */
    public static String describe(StatusBarNotification sbn) {
        String title = notificationTitle(sbn);
        String text = notificationText(sbn);
        if (!title.isEmpty() && !text.isEmpty()) return title + ": " + text;
        if (!title.isEmpty()) return title;
        return sbn.getPackageName();
    }

    /**
     * Best-effort recent conversation straight from the notification's
     * MessagingStyle extras (the "android.messages" array) — the same data
     * Android Auto and Wear render. Gives the direct-reply fast path real
     * context without ever opening the app. Empty when the app did not
     * attach message history.
     */
    public static String conversationContext(StatusBarNotification sbn) {
        try {
            Notification notification = sbn != null ? sbn.getNotification() : null;
            if (notification == null || notification.extras == null) return "";
            Parcelable[] messages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (messages == null || messages.length == 0) return "";

            StringBuilder sb = new StringBuilder();
            int from = Math.max(0, messages.length - 8);
            for (int i = from; i < messages.length; i++) {
                if (!(messages[i] instanceof Bundle)) continue;
                Bundle message = (Bundle) messages[i];
                CharSequence text = message.getCharSequence("text");
                if (text == null || text.length() == 0) continue;
                CharSequence sender = message.getCharSequence("sender");
                // MessagingStyle semantics: a null/empty sender means the
                // message came from the user themself.
                String speaker = (sender == null || sender.length() == 0) ? "me" : "them";
                sb.append(speaker).append(": \"").append(text).append("\"\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            XLog.w(TAG, "Failed to read MessagingStyle history", e);
            return "";
        }
    }

    private static Notification.Action findReplyAction(StatusBarNotification sbn) {
        Notification notification = sbn != null ? sbn.getNotification() : null;
        if (notification == null || notification.actions == null) return null;
        for (Notification.Action action : notification.actions) {
            if (action == null || action.actionIntent == null) continue;
            android.app.RemoteInput[] remoteInputs = action.getRemoteInputs();
            if (remoteInputs != null && remoteInputs.length > 0) {
                return action;
            }
        }
        return null;
    }

    private static String notificationTitle(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return "";
        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        return title != null ? title.toString() : "";
    }

    private static String notificationText(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return "";
        CharSequence big = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (big != null && big.length() > 0) return big.toString();
        CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        return text != null ? text.toString() : "";
    }
}
