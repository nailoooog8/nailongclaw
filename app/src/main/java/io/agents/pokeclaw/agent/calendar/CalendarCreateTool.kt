// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.calendar

import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Creates an event on the device calendar — the agent's answer to "put lunch
 * with Sam tomorrow at noon on my calendar", and the write half of the pair
 * [CalendarReadTool] reads back.
 *
 * ## What it is not
 *
 * Not a reminder: something the user should be *told about* at a moment is
 * `schedule_task` (it delivers a notification and records a ledger entry).
 * This writes into the user's real calendar, where they and everyone they
 * share it with will see it — which is why the success echo says exactly
 * what landed, on which calendar, with the event id.
 *
 * ## Time contract
 *
 * Millis are absolute, like `schedule_task`'s `at_millis`: the model already
 * owes that tool a conversion, and one convention is easier to keep straight
 * than two. All-day events take the day's **UTC** midnight (the provider
 * stores EVENT_TIMEZONE as UTC for them), so the echo prints the UTC date —
 * if the day that comes back is not the day the user asked for, the wrong
 * midnight was passed, and the echo makes that visible instead of silent.
 *
 * ## Honest failures
 *
 * Every [CalendarGateway.Failure] routes to a message that names the cause
 * and the next action: which permission to grant and where, that there is no
 * writable calendar to land on, or that the provider refused. Nothing here
 * retries — a refused insert is reported, not looped on.
 *
 * WRITE tier ([io.agents.pokeclaw.tool.ToolRiskPolicy]): unwatched sources
 * cannot create calendar entries, and it earns no remembered yes.
 */
class CalendarCreateTool(
    private val gatewayProvider: () -> CalendarGateway = {
        CalendarGatewayImpl(ClawApplication.instance)
    },
) : BaseTool() {

    override fun getName() = "calendar_create"

    override fun getDisplayName() = "Calendar"

    override fun getDescriptionEN() =
        "Create an event on the device calendar. Requires a title and an absolute start in " +
        "epoch milliseconds (same convention as schedule_task's at_millis). duration_minutes sets " +
        "the length (default 60; all-day events use 1440 per day and take the day's UTC midnight). " +
        "Optional location, description, and calendar_id (defaults to the primary writable calendar). " +
        "The event lands in the user's real calendar app — use this for \"add lunch with Sam " +
        "tomorrow at noon\" requests; for \"remind me at 7\" use schedule_task instead."

    override fun getDescriptionCN() =
        "在设备日历上创建日程。需要标题和绝对开始时间（epoch 毫秒，约定同 schedule_task 的 " +
        "at_millis）。duration_minutes 设时长（默认 60；全天日程按 1440 为一天，开始时间传当天 " +
        "UTC 零点）。可选地点、描述、calendar_id（缺省落在主要的可写日历）。日程会出现在用户真实的" +
        "日历 App 里——「明天中午和山姆吃饭」这类请求用它；「七点提醒我」用 schedule_task。"

    override fun getParameters() = listOf(
        ToolParameter(
            "title", "string",
            "Event title, e.g. \"Lunch with Sam\".",
            true
        ),
        ToolParameter(
            "start_millis", "integer",
            "Absolute start in epoch milliseconds. For all_day events pass the day's UTC midnight.",
            true
        ),
        ToolParameter(
            "duration_minutes", "integer",
            "Optional: length in minutes. Default 60 (1440 for all-day events, one day each).",
            false
        ),
        ToolParameter(
            "all_day", "boolean",
            "Optional: true for an all-day event (start_millis is then the day's UTC midnight).",
            false
        ),
        ToolParameter(
            "location", "string",
            "Optional: event location.",
            false
        ),
        ToolParameter(
            "description", "string",
            "Optional: event notes/description.",
            false
        ),
        ToolParameter(
            "calendar_id", "integer",
            "Optional: target calendar id. Defaults to the primary writable calendar.",
            false
        ),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val title = params["title"]?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            return ToolResult.error("calendar_create: title is empty — name the event.")
        }
        val startMillis = params["start_millis"]?.let { raw ->
            when (raw) {
                is Number -> raw.toLong()
                else -> raw.toString().toLongOrNull()
            }
        }
        if (startMillis == null || startMillis <= 0) {
            return ToolResult.error(
                "calendar_create: start_millis is missing or not a positive epoch value — " +
                    "give the absolute start in milliseconds, like schedule_task's at_millis."
            )
        }
        val allDay = optionalBoolean(params, "all_day", false)
        // Refuse rather than clamp: this tool writes, and a silently
        // reinterpreted length (0 clamped to 1, a year clamped to two weeks)
        // creates an event the user never asked for. calendar_read clamps
        // because misreading a window harms nothing.
        val durationMinutes = if (params.containsKey("duration_minutes")) {
            val requested = optionalInt(params, "duration_minutes", 0)
            if (requested < 1 || requested > MAX_DURATION_MINUTES) {
                return ToolResult.error(
                    "calendar_create: duration_minutes must be between 1 and " +
                        "$MAX_DURATION_MINUTES (two weeks) — got $requested."
                )
            }
            requested
        } else {
            if (allDay) ALL_DAY_MINUTES else 60
        }

        val gateway = gatewayProvider()
        return try {
            // Default path resolves the calendar and its display name in one
            // call; an explicit id skips the lookup and the name becomes a
            // best-effort label for the echo (a missing one must not fail an
            // otherwise valid create — the provider's own NotFound is the
            // honest error for a bad id).
            val calendarId: Long
            val calendarName: String?
            if (params.containsKey("calendar_id")) {
                calendarId = optionalLong(params, "calendar_id", 0)
                calendarName = runCatching {
                    gateway.visibleCalendars().firstOrNull { it.id == calendarId }?.displayName
                }.getOrNull()
            } else {
                // Throws PermissionDenied when the grants are missing, and
                // returns null when there is no writable calendar at all —
                // both are the caller's to explain, not to guess at.
                val target = gateway.defaultWriteCalendar()
                    ?: return ToolResult.error(
                        "calendar_create: no writable calendar on this device. The user needs a " +
                            "calendar account (any provider) before events can be created — say so " +
                            "instead of retrying."
                    )
                calendarId = target.id
                calendarName = target.displayName
            }
            val eventId = gateway.createEvent(
                CalendarGateway.NewEvent(
                    calendarId = calendarId,
                    title = title,
                    beginMillis = startMillis,
                    endMillis = startMillis + durationMinutes * 60_000L,
                    allDay = allDay,
                    location = params["location"]?.toString()?.trim()?.ifEmpty { null },
                    description = params["description"]?.toString()?.trim()?.ifEmpty { null },
                    // Null = the implementation's convention: device timezone
                    // for timed events, UTC for all-day ones.
                    timezoneId = null,
                )
            )
            ToolResult.success(
                formatConfirmation(
                    title = title,
                    beginMillis = startMillis,
                    endMillis = startMillis + durationMinutes * 60_000L,
                    allDay = allDay,
                    calendarName = calendarName,
                    eventId = eventId,
                )
            )
        } catch (e: CalendarGateway.Failure.PermissionDenied) {
            val which = if (e.write) {
                "WRITE_CALENDAR is not granted (creating an event also needs read access to " +
                    "pick the calendar)"
            } else {
                "READ_CALENDAR is not granted (needed to find the writable calendar)"
            }
            ToolResult.error(
                "calendar_create: $which. Tell the user to open Android Settings → Apps → " +
                    "PokeClaw → Permissions and allow Calendar, then ask again."
            )
        } catch (e: CalendarGateway.Failure.NotFound) {
            ToolResult.error("calendar_create: ${e.message}")
        } catch (e: CalendarGateway.Failure.Unavailable) {
            ToolResult.error(
                "calendar_create: the calendar provider refused the insert (${e.message}). " +
                    "Say so instead of retrying — a second insert risks a duplicate event."
            )
        } catch (e: Exception) {
            ToolResult.error("calendar_create error: ${e.message}")
        }
    }

    internal companion object {

        /** Two weeks. Past that, the request is a calendar series, not one event. */
        private const val MAX_DURATION_MINUTES = 20_160

        /** One all-day event. Multi-day ones pass duration in days × 1440. */
        private const val ALL_DAY_MINUTES = 1_440

        private val dayAndTime = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        private val timeOnly = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val utcDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }

        /**
         * The echo the model repeats to the user: what landed, when, on which
         * calendar. Pure (no gateway, no clock) so the format is testable.
         *
         * Timed events print local time (that is what the provider stores);
         * all-day events print the UTC date, because UTC is what was stored
         * for them — the echo is where a wrong midnight becomes visible.
         */
        fun formatConfirmation(
            title: String,
            beginMillis: Long,
            endMillis: Long,
            allDay: Boolean,
            calendarName: String?,
            eventId: Long,
        ): String {
            val shown = title.ifBlank { "(untitled)" }.take(120)
            val whenText = when {
                allDay -> "${utcDate.format(Date(beginMillis))} (all day)"
                sameLocalDay(beginMillis, endMillis) ->
                    "${dayAndTime.format(Date(beginMillis))}–${timeOnly.format(Date(endMillis))}"
                else ->
                    "${dayAndTime.format(Date(beginMillis))}–${dayAndTime.format(Date(endMillis))}"
            }
            val where = calendarName?.takeIf { it.isNotBlank() }?.let { " on calendar \"$it\"" } ?: ""
            return "Created \"$shown\" — $whenText$where. Event id $eventId."
        }

        private fun sameLocalDay(a: Long, b: Long): Boolean =
            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).run {
                format(Date(a)) == format(Date(b))
            }
    }
}
