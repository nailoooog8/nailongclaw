// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The create tool against a fake gateway — the gateway is an interface
 * precisely so the default-calendar choice, the time contract, the honest
 * failure routing, and the echo format can be pinned without a
 * ContentResolver.
 */
class CalendarCreateToolTest {

    private class FakeGateway(
        var calendars: List<CalendarGateway.CalendarInfo> = emptyList(),
        var defaultCalendar: CalendarGateway.CalendarInfo? = null,
        var failDefaultWith: Exception? = null,
        var failCreateWith: Exception? = null,
    ) : CalendarGateway {
        var defaultLookups = 0
        var createCalls = 0
        var lastEvent: CalendarGateway.NewEvent? = null

        override fun visibleCalendars(): List<CalendarGateway.CalendarInfo> = calendars

        override fun defaultWriteCalendar(): CalendarGateway.CalendarInfo? {
            defaultLookups++
            failDefaultWith?.let { throw it }
            return defaultCalendar
        }

        override fun eventsBetween(startMillis: Long, endMillis: Long, maxEvents: Int): List<CalendarGateway.Event> =
            emptyList()

        override fun createEvent(event: CalendarGateway.NewEvent): Long {
            createCalls++
            failCreateWith?.let { throw it }
            lastEvent = event
            return 4242L
        }
    }

    private val start = 1_777_000_000_000L // fixed absolute start, like the read tool's now
    private val work = CalendarGateway.CalendarInfo(
        id = 7L, displayName = "Work", accountName = "me@example.com", isPrimary = true,
    )

    private fun tool(gateway: CalendarGateway) = CalendarCreateTool(gatewayProvider = { gateway })

    private fun baseParams() = mutableMapOf<String, Any>(
        "title" to "Lunch with Sam",
        "start_millis" to start,
    )

    @Test
    fun `defaults land on the primary writable calendar with an hour of duration`() {
        val gateway = FakeGateway(defaultCalendar = work)
        val result = tool(gateway).execute(baseParams())

        assertTrue(result.isSuccess)
        assertEquals(1, gateway.createCalls)
        val inserted = gateway.lastEvent!!
        assertEquals(7L, inserted.calendarId)
        assertEquals("Lunch with Sam", inserted.title)
        assertEquals(start, inserted.beginMillis)
        assertEquals(start + 60 * 60_000L, inserted.endMillis)
        assertFalse(inserted.allDay)
        assertNull(inserted.location)
        assertNull(inserted.description)
        // Null = the implementation's timezone convention (device default for
        // timed events); the tool does not second-guess it.
        assertNull(inserted.timezoneId)
    }

    @Test
    fun `duration_minutes moves the end`() {
        val gateway = FakeGateway(defaultCalendar = work)
        tool(gateway).execute(baseParams() + mapOf("duration_minutes" to 90))
        assertEquals(start + 90 * 60_000L, gateway.lastEvent!!.endMillis)
    }

    @Test
    fun `all_day defaults to a full day and flags the insert`() {
        val gateway = FakeGateway(defaultCalendar = work)
        tool(gateway).execute(baseParams() + mapOf("all_day" to true))
        val inserted = gateway.lastEvent!!
        assertTrue(inserted.allDay)
        assertEquals(start + 1_440 * 60_000L, inserted.endMillis)
    }

    @Test
    fun `multi-day all-day events count days in the duration`() {
        val gateway = FakeGateway(defaultCalendar = work)
        tool(gateway).execute(baseParams() + mapOf("all_day" to true, "duration_minutes" to 2880))
        assertEquals(start + 2 * 1_440 * 60_000L, gateway.lastEvent!!.endMillis)
    }

    @Test
    fun `explicit calendar id is honored and skips the default lookup`() {
        val gateway = FakeGateway(defaultCalendar = work, calendars = listOf(work))
        val result = tool(gateway).execute(baseParams() + mapOf("calendar_id" to 7L))

        assertTrue(result.isSuccess)
        assertEquals(0, gateway.defaultLookups)
        assertEquals(7L, gateway.lastEvent!!.calendarId)
    }

    @Test
    fun `blank title is refused before anything is inserted`() {
        val gateway = FakeGateway(defaultCalendar = work)
        val result = tool(gateway).execute(baseParams() + mapOf("title" to "   "))

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("title"))
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun `missing or non-positive start is refused`() {
        val gateway = FakeGateway(defaultCalendar = work)
        val missing = tool(gateway).execute(mapOf("title" to "X"))
        assertFalse(missing.isSuccess)
        assertTrue(missing.error!!.contains("start_millis"))

        val zero = tool(gateway).execute(baseParams() + mapOf("start_millis" to 0))
        assertFalse(zero.isSuccess)
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun `a duration below one minute is refused`() {
        val gateway = FakeGateway(defaultCalendar = work)
        val result = tool(gateway).execute(baseParams() + mapOf("duration_minutes" to 0))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("duration"))
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun `an absurd duration is refused not silently clamped`() {
        val gateway = FakeGateway(defaultCalendar = work)
        val result = tool(gateway).execute(baseParams() + mapOf("duration_minutes" to 999_999))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("two weeks"))
        // Nothing inserted — a clamped event would be one the user never asked for.
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun `no writable calendar is an honest error`() {
        val gateway = FakeGateway(defaultCalendar = null)
        val result = tool(gateway).execute(baseParams())

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("no writable calendar"))
        // Nothing inserted — the failure happened before the write.
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun `write permission denial names the settings path`() {
        val gateway = FakeGateway(
            failDefaultWith = CalendarGateway.Failure.PermissionDenied(write = true)
        )
        val result = tool(gateway).execute(baseParams())

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("WRITE_CALENDAR"))
        assertTrue(result.error!!.contains("Settings"))
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun `read permission denial says which permission is missing`() {
        val gateway = FakeGateway(
            failDefaultWith = CalendarGateway.Failure.PermissionDenied(write = false)
        )
        val result = tool(gateway).execute(baseParams())

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("READ_CALENDAR"))
    }

    @Test
    fun `unknown calendar id surfaces the provider reason`() {
        val gateway = FakeGateway(
            failCreateWith = CalendarGateway.Failure.NotFound("No calendar with id 99")
        )
        val result = tool(gateway).execute(baseParams() + mapOf("calendar_id" to 99L))

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("No calendar with id 99"))
    }

    @Test
    fun `provider refusal says so without suggesting a retry`() {
        val gateway = FakeGateway(
            defaultCalendar = work,
            failCreateWith = CalendarGateway.Failure.Unavailable("provider refused the insert")
        )
        val result = tool(gateway).execute(baseParams())

        assertFalse(result.isSuccess)
        assertTrue(result.error!!.contains("refused"))
        // The duplicate risk is named, so "retry" is never the advice.
        assertTrue(result.error!!.contains("duplicate"))
    }

    @Test
    fun `the confirmation echoes title time calendar and id`() {
        val gateway = FakeGateway(defaultCalendar = work)
        val result = tool(gateway).execute(baseParams() + mapOf("location" to "Cafe Luna"))

        assertTrue(result.isSuccess)
        val data = result.data!!
        assertTrue(data.contains("Lunch with Sam"))
        assertTrue(data.contains("Work"))
        assertTrue(data.contains("4242"))
    }

    @Test
    fun `a past start is allowed — recording history is a legitimate create`() {
        val gateway = FakeGateway(defaultCalendar = work)
        val result = tool(gateway).execute(baseParams() + mapOf("start_millis" to 1_000_000_000_000L))

        assertTrue(result.isSuccess)
        assertEquals(1, gateway.createCalls)
    }

    @Test
    fun `long titles are truncated in the echo but inserted whole`() {
        val gateway = FakeGateway(defaultCalendar = work)
        val long = "T".repeat(300)
        val result = tool(gateway).execute(baseParams() + mapOf("title" to long))

        assertTrue(result.isSuccess)
        assertEquals(long, gateway.lastEvent!!.title)
        assertFalse(result.data!!.contains("T".repeat(300)))
    }

    @Test
    fun `blank location and description are dropped not stored`() {
        val gateway = FakeGateway(defaultCalendar = work)
        tool(gateway).execute(
            baseParams() + mapOf("location" to "   ", "description" to "")
        )
        assertNull(gateway.lastEvent!!.location)
        assertNull(gateway.lastEvent!!.description)
    }

    @Test
    fun `calendar_create does not offer wait_after`() {
        val params = CalendarCreateTool().getParametersWithWaitAfter()
        assertFalse(params.any { it.name == "wait_after" })
    }

    // ---- formatConfirmation: the echo the model repeats ----

    @Test
    fun `all-day confirmation prints the UTC date that was stored`() {
        val utcMidnight = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse("2026-10-27")!!.time
        val line = CalendarCreateTool.formatConfirmation(
            title = "Conference",
            beginMillis = utcMidnight,
            endMillis = utcMidnight + 1_440 * 60_000L,
            allDay = true,
            calendarName = "Work",
            eventId = 9L,
        )
        assertTrue(line.contains("2026-10-27 (all day)"))
    }

    @Test
    fun `same-day events collapse the end to a time`() {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val begin = fmt.parse("10-27 15:00")!!.time
        val line = CalendarCreateTool.formatConfirmation(
            title = "Standup",
            beginMillis = begin,
            endMillis = begin + 15 * 60_000L,
            allDay = false,
            calendarName = null,
            eventId = 1L,
        )
        assertTrue(line.contains("–"))
        assertFalse(line.contains("on calendar"))
        // No calendar name: the clause is omitted rather than printed empty.
        assertFalse(line.contains("null"))
    }

    @Test
    fun `an event crossing midnight prints both date-times`() {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val begin = fmt.parse("10-27 23:00")!!.time
        val line = CalendarCreateTool.formatConfirmation(
            title = "Night shift",
            beginMillis = begin,
            endMillis = begin + 2 * 3_600_000L,
            allDay = false,
            calendarName = "Work",
            eventId = 2L,
        )
        assertTrue(line.contains("10-27 23:00"))
        assertTrue(line.contains("10-28 01:00"))
    }
}
