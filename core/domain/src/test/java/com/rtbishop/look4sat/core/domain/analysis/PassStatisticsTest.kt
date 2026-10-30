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

import com.rtbishop.look4sat.core.domain.predict.NearEarthObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassStatisticsTest {

    private val dayMillis = 86_400_000L

    private fun pass(catNum: Int, dayStart: Long, aosOffsetMin: Long, durationMin: Long, elevation: Double): OrbitalPass {
        val data = OrbitalData(
            name = "SAT-$catNum",
            epoch = 26001.0,
            meanmo = 15.5,
            eccn = 0.001,
            incl = 51.6,
            raan = 0.0,
            argper = 0.0,
            meanan = 0.0,
            catnum = catNum,
            bstar = 0.0001
        )
        val aos = dayStart + aosOffsetMin * 60_000L
        return OrbitalPass(
            aosTime = aos,
            losTime = aos + durationMin * 60_000L,
            maxElevation = elevation,
            orbitalObject = NearEarthObject(data)
        )
    }

    @Test
    fun `empty list produces a zeroed summary instead of NaN`() {
        val stats = PassStatistics.from(emptyList())
        assertEquals(0, stats.passCount)
        assertEquals(0L, stats.visibleSeconds)
        assertEquals(0.0, stats.medianMaxElevationDeg, 0.0)
        assertNull(stats.busiestDay)
    }

    @Test
    fun `aggregates count distinct satellites and total visible time`() {
        val day0 = 10 * dayMillis
        val day1 = 11 * dayMillis
        val passes = listOf(
            pass(1, day0, 0, 10, 20.0),
            pass(1, day0, 600, 5, 45.0),
            pass(2, day1, 0, 20, 80.0)
        )
        val stats = PassStatistics.from(passes)
        assertEquals(3, stats.passCount)
        assertEquals(2, stats.satelliteCount)
        assertEquals(35 * 60L, stats.visibleSeconds)
        assertEquals(45.0, stats.medianMaxElevationDeg, 0.0)
        assertEquals(80.0, stats.bestElevationDeg, 0.0)
        assertEquals(20 * 60L, stats.longestPassSeconds)
    }

    @Test
    fun `buckets stay in chronological order and pick the busiest day`() {
        val day0 = 10 * dayMillis
        val day1 = 11 * dayMillis
        val passes = listOf(
            pass(1, day1, 0, 5, 20.0),
            pass(1, day0, 0, 10, 20.0),
            pass(1, day0, 600, 15, 20.0)
        )
        val stats = PassStatistics.from(passes)
        assertEquals(day0, stats.days.first().dayStartMillis)
        assertEquals(day1, stats.days.last().dayStartMillis)
        assertEquals(2, stats.busiestDay?.passCount)
        assertEquals(25 * 60L, stats.busiestDay?.visibleSeconds)
    }

    @Test
    fun `median of an even count averages the middle pair`() {
        val day0 = 10 * dayMillis
        val passes = listOf(
            pass(1, day0, 0, 5, 10.0),
            pass(1, day0, 100, 5, 20.0),
            pass(1, day0, 200, 5, 30.0),
            pass(1, day0, 300, 5, 40.0)
        )
        assertEquals(25.0, PassStatistics.from(passes).medianMaxElevationDeg, 0.0)
    }
}
