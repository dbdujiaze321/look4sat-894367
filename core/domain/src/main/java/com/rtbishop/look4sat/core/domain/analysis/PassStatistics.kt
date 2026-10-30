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

/** Visibility on one calendar day, in UTC. The UI shifts the label into the local zone. */
data class DayBucket(val dayStartMillis: Long, val passCount: Int, val visibleSeconds: Long)

/**
 * Aggregate view over a pass list.
 *
 * Answers the questions the list itself cannot: how much observing time is actually available in the
 * window, which day is the busiest, and whether the 15 degree filter is quietly hiding most of it.
 * Everything is computed from the already-predicted passes, so switching a filter is the only cost.
 */
data class PassStatistics(
    val satelliteCount: Int,
    val passCount: Int,
    val visibleSeconds: Long,
    val medianMaxElevationDeg: Double,
    val bestElevationDeg: Double,
    val longestPassSeconds: Long,
    val days: List<DayBucket>
) {
    val busiestDay: DayBucket? get() = days.maxByOrNull { it.visibleSeconds }

    companion object {
        private const val DAY_MILLIS = 86_400_000L

        fun from(passes: List<OrbitalPass>): PassStatistics {
            if (passes.isEmpty()) {
                return PassStatistics(0, 0, 0, 0.0, 0.0, 0, emptyList())
            }
            val durations = passes.map { (it.losTime - it.aosTime).coerceAtLeast(0L) / 1000 }
            val elevations = passes.map { it.maxElevation }.sorted()
            val buckets = HashMap<Long, MutableList<OrbitalPass>>()
            for (pass in passes) {
                val dayStart = Math.floorDiv(pass.aosTime, DAY_MILLIS) * DAY_MILLIS
                buckets.getOrPut(dayStart) { mutableListOf() }.add(pass)
            }
            val days = buckets.entries
                .sortedBy { it.key }
                .map { (dayStart, dayPasses) ->
                    DayBucket(
                        dayStartMillis = dayStart,
                        passCount = dayPasses.size,
                        visibleSeconds = dayPasses.sumOf {
                            (it.losTime - it.aosTime).coerceAtLeast(0L) / 1000
                        }
                    )
                }
            return PassStatistics(
                satelliteCount = passes.map { it.catNum }.distinct().size,
                passCount = passes.size,
                visibleSeconds = durations.sum(),
                medianMaxElevationDeg = median(elevations),
                bestElevationDeg = elevations.last(),
                longestPassSeconds = durations.max(),
                days = days
            )
        }

        /** True median: averages the two middle values on an even count instead of picking one. */
        private fun median(sorted: List<Double>): Double {
            if (sorted.isEmpty()) return 0.0
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            }
        }
    }
}
