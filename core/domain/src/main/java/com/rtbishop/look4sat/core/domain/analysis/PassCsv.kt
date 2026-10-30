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
 * Builds a CSV of one pass.
 *
 * Metadata goes in `#` comment lines rather than a second header block: the file then has exactly one
 * header row, which is what `read_csv` and every spreadsheet expect, while still carrying the epoch
 * and element age that make the numbers interpretable months later. All timestamps are UTC and ISO
 * 8601 — a local-time export is unusable the moment it crosses a timezone boundary.
 */
object PassCsv {

    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun build(
        pass: OrbitalPass,
        metrics: PassMetrics,
        samples: List<PassSample>,
        nominalDownlinkHz: Long? = null,
        generatedAt: Long = System.currentTimeMillis()
    ): String {
        val builder = StringBuilder(256 + samples.size * 96)
        builder.appendLine("# Look4Sat pass export")
        builder.appendLine("# satellite,${escape(pass.name)},${pass.catNum}")
        builder.appendLine("# aos_utc,${timeFormat.format(Instant.ofEpochMilli(metrics.aosTime))}")
        builder.appendLine("# los_utc,${timeFormat.format(Instant.ofEpochMilli(metrics.losTime))}")
        builder.appendLine("# tca_utc,${timeFormat.format(Instant.ofEpochMilli(metrics.tcaTime))}")
        builder.appendLine("# duration_s,${metrics.durationSeconds}")
        builder.appendLine("# max_elevation_deg,${format(metrics.maxElevationDeg)}")
        builder.appendLine("# min_range_km,${format(metrics.minRangeKm)}")
        builder.appendLine("# peak_range_rate_km_s,${format(metrics.peakRangeRateKmS)}")
        builder.appendLine("# sunlit_fraction,${format(metrics.sunlitFraction)}")
        builder.appendLine("# element_epoch,${format(pass.orbitalObject.data.epoch)}")
        builder.appendLine("# generated_utc,${timeFormat.format(Instant.ofEpochMilli(generatedAt))}")
        if (nominalDownlinkHz != null) {
            builder.appendLine("# doppler_reference_hz,$nominalDownlinkHz")
        }
        builder.append("time_utc,epoch_ms,azimuth_deg,elevation_deg,range_km,range_rate_km_s")
        if (nominalDownlinkHz != null) builder.append(",doppler_hz")
        builder.appendLine(",sunlit")
        for (sample in samples) {
            builder.append(timeFormat.format(Instant.ofEpochMilli(sample.time))).append(',')
            builder.append(sample.time).append(',')
            builder.append(format(sample.azimuthDeg)).append(',')
            builder.append(format(sample.elevationDeg)).append(',')
            builder.append(format(sample.rangeKm)).append(',')
            builder.append(format(sample.rangeRateKmS))
            if (nominalDownlinkHz != null) {
                builder.append(',').append(sample.dopplerShiftHz(nominalDownlinkHz))
            }
            builder.append(',').append(if (sample.sunlit) "sunlit" else "eclipse")
            builder.appendLine()
        }
        return builder.toString()
    }

    /** Suggested file name: stable, sortable, and safe on every file system. */
    fun fileName(pass: OrbitalPass, metrics: PassMetrics): String {
        val compact = timeFormat.format(Instant.ofEpochMilli(metrics.aosTime))
            .replace("-", "").replace(":", "")
        val safeName = pass.name.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        return "pass_${pass.catNum}_${safeName}_$compact.csv"
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

    /**
     * RFC 4180 quoting. Satellite names routinely contain commas and parentheses ("CSS (TIANHE)"),
     * and an unquoted comma silently shifts every following column by one.
     */
    private fun escape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' }) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }
}
