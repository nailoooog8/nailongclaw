// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.channel.security

import io.agents.nailongclaw.channel.Channel

/**
 * One row of the paired-sender settings list (`ChannelPairActivity`), in the
 * terms the UI renders — no storage details leak into the widgets.
 */
sealed class PairingRow {
    abstract val senderId: String

    /**
     * A sender currently authorised on one channel. Authorisation is per
     * channel (SECURITY_AUDIT_R2 F1), so the same id may legitimately appear
     * as two rows on two channels.
     *
     * @param pairedAtMs when the sender was first paired; null when the
     *   timestamp was never recorded (entries paired before this was kept, or
     *   a lost write) — the UI says "unknown" rather than inventing a date.
     */
    data class Active(
        override val senderId: String,
        val channel: Channel,
        val pairedAtMs: Long?,
    ) : PairingRow()

    /**
     * A leftover from the F1 upgrade migration: paired before the whitelist
     * became per-channel, so its channel can no longer be confirmed and it
     * authorises nothing. It stays visible — greyed, badged "pending
     * re-bind" — until the sender /binds again (which resolves it) or the
     * user deletes the record, because a silently emptied whitelist after an
     * upgrade reads as "nothing happened".
     */
    data class PendingRebind(override val senderId: String) : PairingRow()
}

/**
 * Immutable view model of [ChannelSecurityGuard]'s pairing state, as the
 * settings list renders it. Pure Kotlin, JVM-testable: the mapping from
 * storage to rows, and the pending-re-bind determination, live here rather
 * than in the Activity.
 */
data class PairingListModel(
    val rows: List<PairingRow>,
) {
    /** True when there is nothing to show — active or pending. */
    val isEmpty: Boolean get() = rows.isEmpty()

    val activeRows: List<PairingRow.Active>
        get() = rows.filterIsInstance<PairingRow.Active>()

    val pendingRows: List<PairingRow.PendingRebind>
        get() = rows.filterIsInstance<PairingRow.PendingRebind>()
}

object ChannelPairingListModel {

    /**
     * Map storage → row model.
     *
     * Ordering is deterministic — active rows first, sorted by channel name
     * then sender id; pending rows last, sorted by id — so the list never
     * shuffles between renders (MMKV round-trips do not preserve order).
     *
     * Re-bind determination: an id counts as pending only while it is NOT
     * whitelisted on any channel. A successful /bind resolves the pending
     * record in storage, and this check keeps the UI honest even if the two
     * keys ever disagree (they are written under one lock, but they are two
     * writes, and stale-read defence belongs where the rendering is).
     */
    fun build(
        whitelist: Map<Channel, Set<String>>,
        pairTimes: Map<String, Long>,
        pendingIds: Set<String>,
    ): PairingListModel {
        val whitelistedAnywhere = whitelist.values.flatten().toSet()
        val active = whitelist.entries
            .flatMap { (channel, ids) ->
                ids.map { id ->
                    PairingRow.Active(
                        senderId = id,
                        channel = channel,
                        pairedAtMs = pairTimes[ChannelSecurityGuard.entryKeyOf(channel, id)],
                    )
                }
            }
            .sortedWith(compareBy({ it.channel.name }, { it.senderId }))
        val pending = pendingIds
            .filterNot { it in whitelistedAnywhere }
            .sorted()
            .map { PairingRow.PendingRebind(it) }
        return PairingListModel(active + pending)
    }

    /**
     * The number the 渠道接入页's "Paired senders" trailing text shows.
     *
     * Derived through [build] rather than counted off the raw maps, so the
     * entry page cannot disagree with the whitelist page it links to: both
     * display the same row model, including the rule that a pending id that
     * is whitelisted somewhere is not a second row. The caller must have run
     * [ChannelSecurityGuard.purgeLegacyEntries] first (same ritual as
     * ChannelPairActivity.renderAll) — before the one-shot migration the
     * quarantined entries are neither whitelisted nor pending, and any count
     * taken then reads low.
     */
    fun summaryCount(
        whitelist: Map<Channel, Set<String>>,
        pairTimes: Map<String, Long>,
        pendingIds: Set<String>,
    ): Int = build(whitelist, pairTimes, pendingIds).rows.size
}
