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
import org.junit.Assert.assertTrue
import org.junit.Test

class PassCsvTest {

    private val aos = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    private fun pass(name: String = "ISS (ZARYA)"): OrbitalPass {
        val data = OrbitalData(
            name = name,
            epoch = 24001.5,
            meanmo = 15.49512345,
            eccn = 0.0006703,
            incl = 51.6416,
            raan = 247.4627,
            argper = 130.536,
            meanan = 325.0288,
            catnum = 25544,
            bstar = 0.0001
        )
        return OrbitalPass(
            aosTime = aos,
            losTime = aos + 600_000L,
            maxElevation = 45.0,
            orbitalObject = NearEarthObject(data)
        )
    }

    private fun metrics() = PassMetrics(
        aosTime = aos,
        losTime = aos + 600_000L,
        tcaTime = aos + 300_000L,
        durationSeconds = 600,
        maxElevationDeg = 45.0,
        azimuthAtTcaDeg = 181.5,
        minRangeKm = 512.25,
        maxRangeKm = 2143.5,
        rangeRateAtTcaKmS = 0.0,
        peakRangeRateKmS = 6.9,
        azimuthSweepDeg = 118.0,
        sunlitFraction = 0.75
    )

    private fun samples() = listOf(
        PassSample(aos, 10.0, 0.5, 2143.5, 6.9, 420.0, true),
        PassSample(aos + 300_000L, 181.5, 45.0, 512.25, 0.0, 420.0, false)
    )

    @Test
    fun `header appears once and data rows match the sample count`() {
        val csv = PassCsv.build(pass(), metrics(), samples(), nominalDownlinkHz = 145_800_000L)
        val lines = csv.trim().lines()
        assertEquals(1, lines.count { it.startsWith("time_utc,") })
        val dataRows = lines.filter { it.startsWith("2026-") }
        assertEquals(2, dataRows.size)
        assertEquals(8, dataRows.first().split(',').size)
    }

    @Test
    fun `without a reference frequency there is no doppler column`() {
        val csv = PassCsv.build(pass(), metrics(), samples(), nominalDownlinkHz = null)
        val lines = csv.trim().lines()
        assertTrue(lines.none { it.contains("doppler_hz") })
        assertEquals(7, lines.filter { it.startsWith("2026-") }.first().split(',').size)
    }

    @Test
    fun `metadata carries the element epoch and the derived metrics`() {
        val csv = PassCsv.build(pass(), metrics(), samples())
        assertTrue(csv.contains("# satellite,ISS (ZARYA),25544"))
        assertTrue(csv.contains("# aos_utc,2026-01-01T00:00:00Z"))
        assertTrue(csv.contains("# los_utc,2026-01-01T00:10:00Z"))
        assertTrue(csv.contains("# duration_s,600"))
        assertTrue(csv.contains("# element_epoch,24001.5000"))
        assertTrue(csv.contains("# sunlit_fraction,0.7500"))
    }

    @Test
    fun `names containing a comma are quoted so columns stay aligned`() {
        val csv = PassCsv.build(pass("CSS, (TIANHE)"), metrics(), samples())
        assertTrue(csv.contains("# satellite,\"CSS, (TIANHE)\",25544"))
        val dataRows = csv.trim().lines().filter { it.startsWith("2026-") }
        assertEquals(7, dataRows.first().split(',').size)
    }

    @Test
    fun `file name is filesystem safe and sortable`() {
        val name = PassCsv.fileName(pass("ISS (ZARYA)"), metrics())
        assertEquals("pass_25544_ISS_ZARYA_20260101T000000Z.csv", name)
    }

    @Test
    fun `sunlit flag is written as a word, not a byte`() {
        val rows = PassCsv.build(pass(), metrics(), samples()).trim().lines().filter { it.startsWith("2026-") }
        assertTrue(rows[0].endsWith(",sunlit"))
        assertTrue(rows[1].endsWith(",eclipse"))
    }
}
