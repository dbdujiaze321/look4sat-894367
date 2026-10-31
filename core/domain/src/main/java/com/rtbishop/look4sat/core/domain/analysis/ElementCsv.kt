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

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Element sets as CSV.
 *
 * Exports exactly the fields the database holds, using the column names the parser reads, so the
 * file imports back into this app unchanged — that is what makes it useful for moving a selection
 * between devices or keeping a snapshot before a data refresh.
 *
 * The format is deliberately *not* a regenerated TLE. A TLE also carries the international
 * designator, element set number and revolution count, none of which are stored here; writing
 * placeholders would produce a file that looks authoritative while quietly inventing three fields.
 * A TLE writer can be added the day those fields are persisted.
 */
object ElementCsv {

    const val HEADER =
        "OBJECT_NAME,EPOCH,MEAN_MOTION,ECCENTRICITY,INCLINATION,RA_OF_ASC_NODE," +
            "ARG_OF_PERICENTER,MEAN_ANOMALY,NORAD_CAT_ID,BSTAR,MEAN_MOTION_DOT"

    private val isoFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    fun build(entries: List<OrbitalData>): String {
        val builder = StringBuilder(HEADER.length + 8 + entries.size * 128)
        builder.append(HEADER).append('\n')
        entries.forEach { builder.append(row(it)).append('\n') }
        return builder.toString()
    }

    fun row(data: OrbitalData): String = buildString(128) {
        append(escape(data.name)).append(',')
        append(epochToIso(data.epoch)).append(',')
        append(format(data.meanmo, 8)).append(',')
        append(format(data.eccn, 7)).append(',')
        append(format(data.incl, 4)).append(',')
        append(format(data.raan, 4)).append(',')
        append(format(data.argper, 4)).append(',')
        append(format(data.meanan, 4)).append(',')
        append(data.catnum).append(',')
        append(formatSmall(data.bstar)).append(',')
        append(format(data.ndot, 8))
    }

    /**
     * TLE epoch (YYDDD.dddddddd) to ISO 8601 UTC.
     *
     * The fraction of a day is carried as nanoseconds rather than milliseconds: the epoch itself is
     * only precise to ~0.9 ms, and rounding it again to whole milliseconds would lose the last digit
     * of the field that SGP4 is most sensitive to.
     */
    fun epochToIso(epoch: Double): String {
        var year = floor(epoch / 1000.0)
        val dayOfYear = epoch - year * 1000.0
        year = if (year < 57.0) year + 2000 else year + 1900
        val base = LocalDate.of(year.toInt(), 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val seconds = (dayOfYear - 1.0) * 86_400.0
        val instant = base.plusNanos((seconds * 1_000_000_000.0).roundToLong())
        return isoFormat.format(instant)
    }

    fun fileName(now: Long = System.currentTimeMillis()): String {
        val day = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(now))
        return "look4sat-elements-$day.csv"
    }

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    /** B stars are ~1e-5; fixed point would print a row of zeros. */
    private fun formatSmall(value: Double): String = String.format(Locale.ROOT, "%.6e", value)

    /** RFC 4180: a name containing a comma would otherwise shift every following column. */
    private fun escape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' }) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }
}
