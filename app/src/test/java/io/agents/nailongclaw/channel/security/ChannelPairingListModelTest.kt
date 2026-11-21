// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.channel.security

import io.agents.nailongclaw.channel.Channel
import io.agents.nailongclaw.channel.security.ChannelSecurityGuard.InMemoryStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The paired-sender settings list (BACKLOG P1) is a mapping from
 * [ChannelSecurityGuard] storage to row models plus the revoke/clear state
 * transitions — all of it pure Kotlin exercised here on the JVM:
 *
 *  - empty state
 *  - multiple senders across channels (deterministic ordering)
 *  - pending re-bind rows from the F1 upgrade migration
 *  - revoking one sender (active and pending paths)
 *  - the clear-all path
 */
class ChannelPairingListModelTest {

    private lateinit var inMemoryStorage: InMemoryStorage

    @Before
    fun setUp() {
        inMemoryStorage = InMemoryStorage()
        ChannelSecurityGuard.storage = inMemoryStorage
        ChannelSecurityGuard.setSecurityEnabled(true)
        ChannelSecurityGuard.setPairPin("123456")
        ChannelSecurityGuard.clearWhitelist()
        // The legacy purge is one-shot per process; replay it per test.
        ChannelSecurityGuard.resetLegacyPurgeForTesting()
        // Pin the clock so pair-time assertions are exact.
        ChannelSecurityGuard.nowMillis = { 1_000L }
    }

    @After
    fun tearDown() {
        ChannelSecurityGuard.clearWhitelist()
        inMemoryStorage.clear()
        ChannelSecurityGuard.nowMillis = { System.currentTimeMillis() }
        ChannelSecurityGuard.resetLegacyPurgeForTesting()
    }

    private fun buildModel(): PairingListModel = ChannelPairingListModel.build(
        whitelist = ChannelSecurityGuard.getWhitelist(),
        pairTimes = ChannelSecurityGuard.getPairTimes(),
        pendingIds = ChannelSecurityGuard.getPendingRebindIds(),
    )

    /**
     * Seeds the F1 migration state: appends raw pre-per-channel entries to
     * whatever the whitelist already holds, exactly as an upgraded install
     * would carry both formats in one persisted string.
     */
    private fun seedLegacyWhitelist(vararg ids: String) {
        val existing = inMemoryStorage.getString("KEY_CHANNEL_WHITELIST", "")
        val merged = listOf(existing, ids.joinToString(","))
            .filter { it.isNotEmpty() }
            .joinToString(",")
        inMemoryStorage.putString("KEY_CHANNEL_WHITELIST", merged)
        ChannelSecurityGuard.resetLegacyPurgeForTesting()
    }

    // ---- Empty state ----

    @Test
    fun `empty storage maps to an empty list`() {
        val model = buildModel()
        assertTrue(model.isEmpty)
        assertTrue(model.rows.isEmpty())
        assertTrue(model.activeRows.isEmpty())
        assertTrue(model.pendingRows.isEmpty())
    }

    // ---- Multiple senders ----

    @Test
    fun `multiple senders across channels map to deterministic active rows with pair times`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "tg_2")
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "tg_1")
        ChannelSecurityGuard.addSenderToWhitelist(Channel.QQ, "qq_1")

        val model = buildModel()

        assertFalse(model.isEmpty)
        // Sorted by channel name, then sender id: QQ before TELEGRAM.
        assertEquals(
            listOf(
                PairingRow.Active("qq_1", Channel.QQ, 1_000L),
                PairingRow.Active("tg_1", Channel.TELEGRAM, 1_000L),
                PairingRow.Active("tg_2", Channel.TELEGRAM, 1_000L),
            ),
            model.rows
        )
    }

    @Test
    fun `an entry without a recorded pair time maps to unknown rather than a made-up date`() {
        // Written straight to storage, as an entry paired before pair times
        // existed would look.
        inMemoryStorage.putString("KEY_CHANNEL_WHITELIST", "TELEGRAM|tg_old")

        val model = buildModel()

        val row = model.activeRows.single()
        assertEquals("tg_old", row.senderId)
        assertNull(row.pairedAtMs)
    }

    @Test
    fun `re-pairing an already whitelisted sender keeps the original pair time`() {
        ChannelSecurityGuard.nowMillis = { 1_000L }
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "tg_1")
        ChannelSecurityGuard.nowMillis = { 9_999_999L }
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "tg_1")

        assertEquals(
            1_000L,
            ChannelSecurityGuard.getPairTimes()[ChannelSecurityGuard.entryKeyOf(Channel.TELEGRAM, "tg_1")]
        )
        assertEquals(1, buildModel().rows.size)
    }

    // ---- Pending re-bind (F1 migration) ----

    @Test
    fun `legacy entries surface as pending re-bind rows after the migration`() {
        seedLegacyWhitelist("10086", "20087")

        // The migration runs on the first verify (or when the settings page
        // forces it); either way the ids lose authorisation but stay visible.
        ChannelSecurityGuard.purgeLegacyEntries()

        val model = buildModel()
        assertTrue(model.activeRows.isEmpty())
        assertEquals(
            listOf(
                PairingRow.PendingRebind("10086"),
                PairingRow.PendingRebind("20087"),
            ),
            model.pendingRows
        )
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted(Channel.TELEGRAM, "10086"))
    }

    @Test
    fun `pending rows sort after active rows`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.QQ, "qq_alive")
        seedLegacyWhitelist("10086")
        ChannelSecurityGuard.purgeLegacyEntries()

        val model = buildModel()

        assertEquals(
            listOf(
                PairingRow.Active("qq_alive", Channel.QQ, 1_000L),
                PairingRow.PendingRebind("10086"),
            ),
            model.rows
        )
    }

    @Test
    fun `re-binding resolves the pending state for that sender`() {
        seedLegacyWhitelist("10086")
        ChannelSecurityGuard.purgeLegacyEntries()

        ChannelSecurityGuard.setPairPin("654321")
        ChannelSecurityGuard.verify(
            channel = Channel.TELEGRAM,
            senderId = "10086",
            message = "/bind 654321"
        )

        val model = buildModel()
        assertTrue(model.pendingRows.isEmpty())
        assertEquals(listOf(PairingRow.Active("10086", Channel.TELEGRAM, 1_000L)), model.rows)
    }

    @Test
    fun `a pending id that is whitelisted on any channel is not rendered as pending`() {
        // Defence against inconsistent storage: the two keys are written under
        // one lock, but the re-bind determination must not trust that.
        val model = ChannelPairingListModel.build(
            whitelist = mapOf(Channel.TELEGRAM to setOf("10086")),
            pairTimes = emptyMap(),
            pendingIds = setOf("10086", "20087"),
        )

        assertEquals(
            listOf<PairingRow>(
                PairingRow.Active("10086", Channel.TELEGRAM, null),
                PairingRow.PendingRebind("20087"),
            ),
            model.rows
        )
    }

    // ---- Revoke one ----

    @Test
    fun `revoking one sender removes only its row`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "tg_1")
        ChannelSecurityGuard.addSenderToWhitelist(Channel.QQ, "qq_1")
        ChannelSecurityGuard.addSenderToWhitelist(Channel.WECHAT, "wx_1")

        ChannelSecurityGuard.removeSenderFromWhitelist(Channel.QQ, "qq_1")

        val model = buildModel()
        assertEquals(
            listOf(
                PairingRow.Active("tg_1", Channel.TELEGRAM, 1_000L),
                PairingRow.Active("wx_1", Channel.WECHAT, 1_000L),
            ),
            model.rows
        )
    }

    @Test
    fun `revoking a sender on one channel keeps the same id on another channel`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "777")
        ChannelSecurityGuard.addSenderToWhitelist(Channel.DISCORD, "777")

        ChannelSecurityGuard.removeSenderFromWhitelist(Channel.TELEGRAM, "777")

        val model = buildModel()
        val row = model.activeRows.single()
        assertEquals(Channel.DISCORD, row.channel)
        assertEquals("777", row.senderId)
    }

    @Test
    fun `revoking a sender drops its recorded pair time`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "tg_1")

        ChannelSecurityGuard.removeSenderFromWhitelist(Channel.TELEGRAM, "tg_1")

        assertTrue(ChannelSecurityGuard.getPairTimes().isEmpty())
        assertTrue(buildModel().isEmpty)
    }

    // ---- Revoke a pending record ----

    @Test
    fun `forgetting a pending record removes only that row`() {
        seedLegacyWhitelist("10086", "20087")
        ChannelSecurityGuard.purgeLegacyEntries()

        ChannelSecurityGuard.removePendingRebindId("10086")

        val model = buildModel()
        assertEquals(
            listOf(PairingRow.PendingRebind("20087")),
            model.pendingRows
        )
        assertTrue(ChannelSecurityGuard.getWhitelist().isEmpty())
    }

    // ---- Clear all ----

    @Test
    fun `clearing the whitelist empties the list including pending rows and pair times`() {
        ChannelSecurityGuard.addSenderToWhitelist(Channel.QQ, "qq_alive")
        ChannelSecurityGuard.addSenderToWhitelist(Channel.TELEGRAM, "tg_alive")
        seedLegacyWhitelist("10086")
        ChannelSecurityGuard.purgeLegacyEntries()

        ChannelSecurityGuard.clearWhitelist()

        val model = buildModel()
        assertTrue(model.isEmpty)
        assertTrue(ChannelSecurityGuard.getWhitelist().isEmpty())
        assertTrue(ChannelSecurityGuard.getPendingRebindIds().isEmpty())
        assertTrue(ChannelSecurityGuard.getPairTimes().isEmpty())
    }

    @Test
    fun `clearing an already empty list is a harmless no-op`() {
        ChannelSecurityGuard.clearWhitelist()
        assertTrue(buildModel().isEmpty)
    }

    // ---- Re-bind independence between channels ----

    @Test
    fun `resolving a pending id on one channel does not resurrect it on another`() {
        seedLegacyWhitelist("10086")
        ChannelSecurityGuard.purgeLegacyEntries()

        ChannelSecurityGuard.setPairPin("654321")
        ChannelSecurityGuard.verify(
            channel = Channel.TELEGRAM,
            senderId = "10086",
            message = "/bind 654321"
        )

        // The pending record is resolved by the Telegram bind, but the very
        // same id arriving on QQ is still unauthorised — per-channel scope.
        assertFalse(ChannelSecurityGuard.isSenderWhitelisted(Channel.QQ, "10086"))
        assertTrue(buildModel().pendingRows.isEmpty())
    }
}
