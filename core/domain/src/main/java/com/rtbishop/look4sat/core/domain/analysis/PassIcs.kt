/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.analysis

import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders passes as an iCalendar file.
 *
 * This is how a prediction leaves the app: a calendar entry with a reminder is what actually gets
 * someone to walk outside with a handheld, whereas a pass list inside an app nobody has open at the
 * right moment does not. The events are UTC with a `Z` suffix so they land at the right minute on any
 * device, whatever its time zone.
 */
object PassIcs {

    private val stampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun build(
        passes: List<OrbitalPass>,
        reminderMinutes: Int = 10,
        generatedAt: Long = System.currentTimeMillis()
    ): String {
        val reminder = reminderMinutes.coerceIn(0, 120)
        val builder = StringBuilder(256 + passes.size * 512)
        builder.append("BEGIN:VCALENDAR\r\n")
        builder.append("VERSION:2.0\r\n")
        builder.append("PRODID:-//Look4Sat//Pass calendar//EN\r\n")
        builder.append("CALSCALE:GREGORIAN\r\n")
        builder.append("METHOD:PUBLISH\r\n")
        builder.append("X-WR-CALNAME:Look4Sat passes\r\n")
        for (pass in passes) {
            builder.append("BEGIN:VEVENT\r\n")
            builder.append("UID:").append(pass.catNum).append('-').append(pass.aosTime)
                .append("@look4sat\r\n")
            builder.append("DTSTAMP:").append(stampFormat.format(Instant.ofEpochMilli(generatedAt)))
                .append("\r\n")
            builder.append("DTSTART:").append(stampFormat.format(Instant.ofEpochMilli(pass.aosTime)))
                .append("\r\n")
            builder.append("DTEND:").append(stampFormat.format(Instant.ofEpochMilli(pass.losTime)))
                .append("\r\n")
            builder.append("SUMMARY:").append(escape(summaryOf(pass))).append("\r\n")
            builder.append("DESCRIPTION:").append(escape(descriptionOf(pass))).append("\r\n")
            builder.append("CATEGORIES:Look4Sat\r\n")
            if (reminder > 0) {
                builder.append("BEGIN:VALARM\r\n")
                builder.append("TRIGGER:-PT").append(reminder).append("M\r\n")
                builder.append("ACTION:DISPLAY\r\n")
                // A reminder with an empty description is rejected by some calendar clients.
                builder.append("DESCRIPTION:").append(escape(pass.name + " in " + reminder + " min"))
                    .append("\r\n")
                builder.append("END:VALARM\r\n")
            }
            builder.append("END:VEVENT\r\n")
        }
        builder.append("END:VCALENDAR\r\n")
        return builder.toString()
    }

    /** Calendar-safe name: no slashes, no colons, sortable by start date. */
    fun fileName(passes: List<OrbitalPass>): String {
        val first = passes.minByOrNull { it.aosTime }?.aosTime
        if (first == null) return "look4sat-passes.ics"
        val day = stampFormat.format(Instant.ofEpochMilli(first)).take(8)
        return "look4sat-passes-$day.ics"
    }

    private fun summaryOf(pass: OrbitalPass): String =
        "${pass.name} ${String.format(Locale.ROOT, "%.0f", pass.maxElevation)}°"

    private fun descriptionOf(pass: OrbitalPass): String {
        val durationMinutes = ((pass.losTime - pass.aosTime) / 60_000L).coerceAtLeast(0)
        return buildString {
            append("Max elevation ").append(String.format(Locale.ROOT, "%.1f", pass.maxElevation)).append("°")
            append(", visible for ").append(durationMinutes).append(" min")
            append(". Azimuth at AOS ").append(String.format(Locale.ROOT, "%.0f", pass.aosAzimuth)).append("°")
            append(", at LOS ").append(String.format(Locale.ROOT, "%.0f", pass.losAzimuth)).append("°")
        }
    }

    /**
     * RFC 5545 TEXT escaping: backslash, semicolon, comma and newline are structural otherwise.
     * A satellite called `CSS, (TIANHE)` would otherwise split the property into two values.
     */
    private fun escape(value: String): String = buildString(value.length + 8) {
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n', '\r' -> append("\\n")
                else -> append(char)
            }
        }
    }
}
