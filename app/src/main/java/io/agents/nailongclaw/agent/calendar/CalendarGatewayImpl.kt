// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.nailongclaw.agent.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import io.agents.nailongclaw.utils.XLog
import java.util.TimeZone

/**
 * The production [CalendarGateway], over CalendarContract.
 *
 * ## What lives here and nowhere else
 *
 * - **Instances, not Events**: `eventsBetween` queries the Instances table,
 *   which is the provider's pre-expanded view of recurring events — RRULE
 *   math and per-occurrence timezone resolution are the provider's job.
 * - **Timezone on write**: Android requires EVENT_TIMEZONE on insert; an
 *   all-day event gets UTC (the contract's convention), everything else the
 *   device's current default unless the caller says otherwise.
 * - **Honest failures**: permission checks happen *before* the query, and a
 *   SecurityException from the provider is still translated — the caller gets
 *   a typed [CalendarGateway.Failure], never a bare exception to guess at.
 */
class CalendarGatewayImpl(private val context: Context) : CalendarGateway {

    companion object {
        private const val TAG = "CalendarGateway"

        /** Calendars we are willing to write to; below this the account is read-only. */
        private const val MIN_WRITE_ACCESS = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
    }

    private val resolver get() = context.contentResolver

    private fun hasReadPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun hasWritePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private data class CalendarRow(
        val info: CalendarGateway.CalendarInfo,
        val canWrite: Boolean,
    )

    private fun queryCalendars(): List<CalendarRow> {
        if (!hasReadPermission()) throw CalendarGateway.Failure.PermissionDenied(write = false)
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val rows = mutableListOf<CalendarRow>()
        try {
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars._ID} ASC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                // IS_PRIMARY ships as the string "1"/"0"; some providers omit
                // the column entirely, so a -1 index means "nobody said".
                val primaryIdx = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                val accessIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIdx) ?: continue
                    rows += CalendarRow(
                        info = CalendarGateway.CalendarInfo(
                            id = cursor.getLong(idIdx),
                            displayName = displayName,
                            accountName = if (accountIdx >= 0) cursor.getString(accountIdx) else null,
                            isPrimary = primaryIdx >= 0 && cursor.getString(primaryIdx) == "1",
                        ),
                        canWrite = cursor.getInt(accessIdx) >= MIN_WRITE_ACCESS,
                    )
                }
            } ?: throw CalendarGateway.Failure.Unavailable("Calendar provider returned no cursor")
        } catch (e: SecurityException) {
            throw CalendarGateway.Failure.PermissionDenied(write = false)
        }
        return rows
    }

    override fun visibleCalendars(): List<CalendarGateway.CalendarInfo> =
        queryCalendars().map { it.info }

    override fun defaultWriteCalendar(): CalendarGateway.CalendarInfo? {
        if (!hasWritePermission()) throw CalendarGateway.Failure.PermissionDenied(write = true)
        val writable = queryCalendars().filter { it.canWrite }
        return writable.firstOrNull { it.info.isPrimary }?.info
            ?: writable.firstOrNull()?.info
    }

    override fun eventsBetween(startMillis: Long, endMillis: Long, maxEvents: Int): List<CalendarGateway.Event> {
        if (!hasReadPermission()) throw CalendarGateway.Failure.PermissionDenied(write = false)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, startMillis)
            ContentUris.appendId(this, endMillis)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
        )
        val events = mutableListOf<CalendarGateway.Event>()
        try {
            resolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val instanceIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances._ID)
                val eventIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val calendarIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val locationIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val descriptionIdx = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
                while (cursor.moveToNext() && events.size < maxEvents) {
                    events += CalendarGateway.Event(
                        instanceId = cursor.getLong(instanceIdx),
                        eventId = cursor.getLong(eventIdx),
                        calendarId = cursor.getLong(calendarIdx),
                        title = cursor.getString(titleIdx).orEmpty(),
                        beginMillis = cursor.getLong(beginIdx),
                        endMillis = cursor.getLong(endIdx),
                        allDay = cursor.getInt(allDayIdx) != 0,
                        location = if (locationIdx >= 0) cursor.getString(locationIdx) else null,
                        description = if (descriptionIdx >= 0) cursor.getString(descriptionIdx) else null,
                    )
                }
            } ?: throw CalendarGateway.Failure.Unavailable("Calendar provider returned no cursor")
        } catch (e: SecurityException) {
            throw CalendarGateway.Failure.PermissionDenied(write = false)
        }
        return events
    }

    override fun createEvent(event: CalendarGateway.NewEvent): Long {
        if (!hasWritePermission()) throw CalendarGateway.Failure.PermissionDenied(write = true)
        if (!hasReadPermission()) throw CalendarGateway.Failure.PermissionDenied(write = false)
        if (queryCalendars().none { it.info.id == event.calendarId }) {
            throw CalendarGateway.Failure.NotFound("No calendar with id ${event.calendarId}")
        }
        val timezone = event.timezoneId
            ?: if (event.allDay) "UTC" else TimeZone.getDefault().id
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, event.calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.beginMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, timezone)
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            event.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        return try {
            val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw CalendarGateway.Failure.Unavailable("Calendar provider refused the insert")
            val id = uri.lastPathSegment?.toLongOrNull()
                ?: throw CalendarGateway.Failure.Unavailable("Calendar provider returned no event id")
            XLog.i(TAG, "createEvent: event $id on calendar ${event.calendarId}")
            id
        } catch (e: SecurityException) {
            throw CalendarGateway.Failure.PermissionDenied(write = true)
        }
    }
}
