// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel

import io.agents.pokeclaw.channel.discord.DiscordChannelHandler
import io.agents.pokeclaw.channel.douyin.DouyinChannelHandler
import io.agents.pokeclaw.channel.qq.QqChannelHandler
import io.agents.pokeclaw.channel.telegram.TelegramChannelHandler
import io.agents.pokeclaw.channel.wechat.WeChatChannelHandler
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

enum class Channel(val displayName: String) {
    DISCORD("Discord"),
    TELEGRAM("Telegram"),
    WECHAT("WeChat"),
    QQ("QQ"),
    DOUYIN("Douyin"),
    LOCAL("Local"),

    /**
     * A task driven by another app through the external-automation broadcast
     * (Tasker, MacroDroid, an `adb shell am broadcast`).
     *
     * This is deliberately *not* [LOCAL]: a broadcast has no human in front of
     * the screen even when the user enabled the feature, and it used to be
     * recorded as LOCAL — which handed every automation task the unrestricted
     * LOCAL tier in [io.agents.pokeclaw.tool.ToolRiskPolicy] (R3-01). High
     * impact tools are refused for this channel exactly as for Telegram.
     *
     * There is no handler for it in [ChannelManager.handlers]; outbound sends
     * are no-ops, and the result travels back to the caller through the
     * automation callback broadcast instead.
     */
    EXTERNAL("External Automation"),

    /**
     * A task fired by the app's own alarm
     * ([io.agents.pokeclaw.task.ScheduleDispatcher]).
     *
     * Also deliberately *not* [LOCAL], for the same reason as [EXTERNAL]: the
     * alarm goes off with nobody in front of the screen, so the unrestricted
     * local tier is the wrong one to hand it. A scheduled task therefore gets
     * the READ_ONLY ceiling in [io.agents.pokeclaw.tool.ToolRiskPolicy] — it
     * may observe and answer, and the answer is delivered as a notification.
     * Anything that acts on the world is refused with the reason, and the
     * model is expected to say so rather than pretend it did the thing.
     *
     * This used to be recorded as LOCAL, which gave every fired task full
     * local tool privileges — including the tools that dial, type, and press
     * the key that approves system dialogs, at an hour nobody chose to be
     * awake for.
     *
     * There is no handler for it in [ChannelManager.handlers]; outbound sends
     * are no-ops, and the result travels as a notification instead.
     */
    SCHEDULED("Scheduled"),
}

object ChannelManager {

    private const val TAG = "ChannelManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient()

    private val handlers = mutableMapOf<Channel, ChannelHandler>()
    private var messageListener: OnMessageReceivedListener? = null

    /**
     * Callback interface for received messages
     */
    interface OnMessageReceivedListener {
        fun onMessageReceived(channel: Channel, message: String, messageID: String, senderId: String = "")
    }

    @JvmStatic
    fun setOnMessageReceivedListener(listener: OnMessageReceivedListener?) {
        this.messageListener = listener
    }

    /**
     * Initialize all channels. Call this in Application.onCreate().
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        discordBotToken: String? = null,
        telegramBotToken: String? = null,
        wechatBotToken: String? = null,
        wechatApiBaseUrl: String? = null,
        qqWsUrl: String? = null,
        qqBotToken: String? = null,
        douyinGatewayUrl: String? = null,
        douyinBotToken: String? = null
    ) {
        handlers[Channel.DISCORD] = DiscordChannelHandler(
            scope,
            discordBotToken?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.TELEGRAM] = TelegramChannelHandler(
            scope, httpClient,
            telegramBotToken?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.WECHAT] = WeChatChannelHandler(
            scope,
            wechatBotToken?.takeIf { it.isNotEmpty() } ?: "",
            wechatApiBaseUrl?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.QQ] = QqChannelHandler(
            scope,
            qqWsUrl?.takeIf { it.isNotEmpty() } ?: "",
            qqBotToken?.takeIf { it.isNotEmpty() } ?: "",
        )
        handlers[Channel.DOUYIN] = DouyinChannelHandler(
            scope,
            douyinGatewayUrl?.takeIf { it.isNotEmpty() } ?: "",
            douyinBotToken?.takeIf { it.isNotEmpty() } ?: "",
        )

        handlers.values.forEach { it.init() }
        XLog.i(TAG, "ChannelManager initialized")
    }

    /**
     * Re-read config from MMKV and re-initialize all channels (call after user saves config)
     */
    @JvmStatic
    fun reinitFromStorage() {
        handlers.values.forEach { it.reinitFromStorage() }
    }

    /**
     * Reconnect only channels that are not connected (call on network recovery; does not affect channels already running)
     */
    @JvmStatic
    fun reconnectIfNeeded() {
        handlers.forEach { (channel, handler) ->
            if (!handler.isConnected()) {
                XLog.i(TAG, "Reconnecting ${channel.displayName} channel")
                handler.reinitFromStorage()
            }
        }
    }

    /**
     * Whether any channel currently holds a live connection.
     * Used by the foreground service to decide keep-alive.
     */
    @JvmStatic
    fun hasConnectedChannel(): Boolean = handlers.values.any { it.isConnected() }

    @JvmStatic
    fun reinitDiscordFromStorage() {
        handlers[Channel.DISCORD]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitTelegramFromStorage() {
        handlers[Channel.TELEGRAM]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitWeChatFromStorage() {
        handlers[Channel.WECHAT]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitQQFromStorage() {
        handlers[Channel.QQ]?.reinitFromStorage()
    }

    @JvmStatic
    fun reinitDouyinFromStorage() {
        handlers[Channel.DOUYIN]?.reinitFromStorage()
    }

    @JvmStatic
    fun disconnectAll() {
        handlers.forEach { (channel, handler) ->
            if (handler.isConnected()) {
                XLog.i(TAG, "Disconnecting ${channel.displayName} channel")
                handler.disconnect()
            }
        }
    }

    @JvmStatic
    fun sendMessage(channel: Channel, content: String, messageID: String) {
        val trimmedContent = content.trim('\n', '\r')
        if (trimmedContent.isBlank()) {
            XLog.w(TAG, "sendMessage skipping empty message [${channel.displayName}]")
            return
        }
        XLog.d(TAG, "sendMessage [${channel.displayName}]: ${trimmedContent.take(120)}")
        handlers[channel]?.sendMessage(trimmedContent, messageID)
    }

    @JvmStatic
    fun sendImage(channel: Channel, imageBytes: ByteArray, messageID: String) {
        handlers[channel]?.sendImage(imageBytes, messageID)
    }

    @JvmStatic
    fun sendFile(channel: Channel, file: java.io.File, messageID: String) {
        XLog.i(TAG, "sendFile: ${file.name} via ${channel.displayName}")
        handlers[channel]?.sendFile(file, messageID)
    }

    /**
     * Immediately flush pending messages in the specified channel's buffer. Call when a task ends.
     */
    @JvmStatic
    fun flushMessages(channel: Channel) {
        handlers[channel]?.flushMessages()
    }

    /**
     * Restore the routing context for the specified channel. Call before a scheduled task executes.
     */
    @JvmStatic
    fun restoreRoutingContext(channel: Channel, targetUserId: String) {
        handlers[channel]?.restoreRoutingContext(targetUserId)
    }

    /**
     * Get the identifier of the most recent message sender for the specified channel. Used for scheduled task persistence.
     */
    @JvmStatic
    fun getLastSenderId(channel: Channel): String? {
        return handlers[channel]?.getLastSenderId()
    }

    /**
     * Proactively send a message by user identifier (without relying on messageID context). Used for scheduled task triggers.
     */
    @JvmStatic
    fun sendMessageToUser(channel: Channel, userId: String, content: String) {
        val trimmedContent = content.trim('\n', '\r')
        if (trimmedContent.isBlank()) return
        XLog.d(TAG, "sendMessageToUser [${channel.displayName}] userId=${userId.take(20)}: ${trimmedContent.take(120)}")
        handlers[channel]?.sendMessageToUser(userId, trimmedContent)
    }

    /**
     * Called internally by ChannelHandlers to dispatch received messages to registered listeners.
     */
    @JvmStatic
    @JvmOverloads
    fun dispatchMessage(channel: Channel, message: String, messageID: String, senderId: String = "") {
        messageListener?.onMessageReceived(channel, message, messageID, senderId)
    }
}
