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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitElementsTest {

    private fun data(
        meanmo: Double,
        eccn: Double = 0.001,
        incl: Double = 51.6,
        epoch: Double = 26001.0
    ) = OrbitalData(
        name = "TEST",
        epoch = epoch,
        meanmo = meanmo,
        eccn = eccn,
        incl = incl,
        raan = 0.0,
        argper = 0.0,
        meanan = 0.0,
        catnum = 1,
        bstar = 0.0001
    )

    @Test
    fun `ISS-like mean motion reproduces published geometry`() {
        // 15.5 rev/day is the classic ISS figure: 92.9 min period, ~420 km perigee.
        val elements = OrbitElements.from(data(15.5, 0.0006703, 51.6416), timeMillis = 0L)
        assertEquals(92.90, elements.periodMinutes, 0.05)
        assertTrue("semi-major axis ${elements.semiMajorAxisKm}", elements.semiMajorAxisKm in 6790.0..6800.0)
        assertTrue("perigee ${elements.perigeeAltitudeKm}", elements.perigeeAltitudeKm in 400.0..425.0)
        assertEquals(OrbitClass.LEO, elements.orbitalClass)
    }

    @Test
    fun `nodal regression for a 51 degree orbit is about five degrees per day westward`() {
        // Well known figure for the ISS: the ground track walks west by roughly 5 deg/day.
        val elements = OrbitElements.from(data(15.5, 0.0006703, 51.6416), timeMillis = 0L)
        assertTrue(
            "node rate ${elements.nodalRegressionDegPerDay}",
            elements.nodalRegressionDegPerDay in -5.2..-4.7
        )
    }

    @Test
    fun `sun synchronous inclination drifts east`() {
        // Prograde beyond 90 degrees would flip the sign; at 98 degrees a retrograde orbit must
        // regress positively, which is what makes a sun-synchronous orbit hold its local time.
        val elements = OrbitElements.from(data(14.5, 0.001, 98.0), timeMillis = 0L)
        assertTrue(
            "node rate ${elements.nodalRegressionDegPerDay}",
            elements.nodalRegressionDegPerDay > 0.0
        )
    }

    @Test
    fun `geostationary mean motion classifies as GEO`() {
        val elements = OrbitElements.from(data(1.0027, 0.0002, 0.05), timeMillis = 0L)
        assertEquals(1436.1, elements.periodMinutes, 1.0)
        assertEquals(OrbitClass.GEO, elements.orbitalClass)
        assertTrue("altitude ${elements.perigeeAltitudeKm}", elements.perigeeAltitudeKm in 35_000.0..36_500.0)
    }

    @Test
    fun `molniya eccentricity wins over its low perigee`() {
        // A 12 h orbit at e=0.72 dips to a few hundred km. An altitude-only test would call this LEO.
        val elements = OrbitElements.from(data(2.0, 0.72, 63.4), timeMillis = 0L)
        assertEquals(OrbitClass.HEO, elements.orbitalClass)
        assertTrue("perigee ${elements.perigeeAltitudeKm}", elements.perigeeAltitudeKm < 1200.0)
    }

    @Test
    fun `element age is measured from the epoch, not the calendar year`() {
        val epochDaynum = data(15.5, epoch = 26001.0).epochDaynum
        val twoDaysLater = 315_446_400_000L + ((epochDaynum + 2.0) * 86_400_000.0).toLong()
        val elements = OrbitElements.from(data(15.5), timeMillis = twoDaysLater)
        assertEquals(2.0, elements.elementAgeDays, 0.001)
        assertEquals(ElementFreshness.GOOD, elements.freshness)
    }

    @Test
    fun `freshness grades follow the documented thresholds`() {
        assertEquals(ElementFreshness.FRESH, ElementFreshness.of(0.2))
        assertEquals(ElementFreshness.GOOD, ElementFreshness.of(2.9))
        assertEquals(ElementFreshness.USABLE, ElementFreshness.of(6.9))
        assertEquals(ElementFreshness.STALE, ElementFreshness.of(30.0))
    }
}
