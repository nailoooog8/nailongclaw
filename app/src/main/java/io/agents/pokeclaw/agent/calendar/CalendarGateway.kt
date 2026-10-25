// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.calendar

/**
 * The only door to the Android calendar from agent code.
 *
 * Tools ([CalendarReadTool], and later the create tool) talk to this interface,
 * never to [android.provider.CalendarContract] directly — so the provider
 * details (Instances table for recurring events, timezone rules, permission
 * states, which calendar is writable) live in one implementation and the tools
 * stay testable against a fake.
 *
 * ## Honest failures
 *
 * Every failure is a typed [Failure], never a bare exception: a tool that
 * cannot distinguish "permission not granted" from "no calendar on this
 * device" from "the provider refused" cannot tell the user what to do next,
 * and an honest error path is the whole point of this gateway
 * (BACKLOG, calendar milestone).
 */
interface CalendarGateway {

    /** A calendar account visible to the user, with its provider identity. */
    data class CalendarInfo(
        val id: Long,
        val displayName: String,
        val accountName: String?,
        /** The device's main calendar, if the provider marked one. */
        val isPrimary: Boolean,
    )

    /**
     * One occurrence of an event inside a queried window.
     *
     * Produced from the Instances table, so a recurring event already shows up
     * once per materialized occurrence with its own begin/end in UTC millis —
     * RRULE expansion and timezone math are the provider's job, not ours.
     */
    data class Event(
        val instanceId: Long,
        val eventId: Long,
        val calendarId: Long,
        val title: String,
        val beginMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val location: String?,
        val description: String?,
    )

    /** What a create call inserts; millis are absolute, timezone is explicit. */
    data class NewEvent(
        val calendarId: Long,
        val title: String,
        val beginMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val location: String? = null,
        val description: String? = null,
        /** Null = the device's current default timezone. All-day events use UTC. */
        val timezoneId: String? = null,
    )

    /** Typed failures so tools can route each cause to an honest message. */
    sealed class Failure(message: String) : Exception(message) {
        /** The runtime grant is missing. [write] says which permission. */
        class PermissionDenied(val write: Boolean) :
            Failure(if (write) "WRITE_CALENDAR not granted" else "READ_CALENDAR not granted")

        /** Nothing matches the request (no calendar account, no such calendar id). */
        class NotFound(val what: String) : Failure(what)

        /** The provider exists but refused or failed the operation. */
        class Unavailable(val why: String) : Failure(why)
    }

    /** Calendars the user can see. Requires read permission. */
    @Throws(Failure::class)
    fun visibleCalendars(): List<CalendarInfo>

    /**
     * Event occurrences overlapping [startMillis, endMillis), sorted by begin,
     * at most [maxEvents]. Recurring events are expanded by the provider.
     * Requires read permission.
     */
    @Throws(Failure::class)
    fun eventsBetween(startMillis: Long, endMillis: Long, maxEvents: Int): List<Event>

    /** Insert an event; returns the new event id. Requires write permission. */
    @Throws(Failure::class)
    fun createEvent(event: NewEvent): Long

    /**
     * Where new events should land by default: the primary calendar, else the
     * first writable one, else null (the caller must ask the user to pick).
     */
    @Throws(Failure::class)
    fun defaultWriteCalendar(): CalendarInfo?
}
