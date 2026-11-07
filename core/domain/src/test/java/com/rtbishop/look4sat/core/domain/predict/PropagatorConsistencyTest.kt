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
package com.rtbishop.look4sat.core.domain.predict

import com.rtbishop.look4sat.core.domain.analysis.PassSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards two properties the whole prediction chain depends on.
 *
 * 1. **Order independence.** The numerical propagator keeps a small state cache and integrates from
 *    whichever cached point is nearest. That is only safe if the result for a given instant does not
 *    depend on which instants were asked for before it. If this ever regresses, two screens showing
 *    "the same" pass can disagree — which is exactly the kind of bug that is impossible to reason
 *    about from a screenshot.
 *
 * 2. **Scan and sampler agreement.** The pass list finds AOS by stepping along the elevation curve;
 *    the analysis screen samples the same window. They must describe the same curve, otherwise the
 *    first sample of a pass would not sit on the horizon.
 */
class PropagatorConsistencyTest {

    private val data = OrbitalData(
        name = "ISS (ZARYA)",
        epoch = 26003.5,
        meanmo = 15.49512345,
        eccn = 0.0006703,
        incl = 51.6416,
        raan = 247.4627,
        argper = 130.5360,
        meanan = 325.0288,
        catnum = 25544,
        bstar = 0.00010270
    )
    private val station = GeoPos(51.4778, -0.0015, 0.0)
    private val epochMillis = 315_446_400_000L + (data.epochDaynum * 86_400_000.0).toLong()
    private val target = epochMillis + 6 * 3_600_000L

    private fun elevationAt(sat: OrbitalObject, time: Long) =
        sat.getFullPosition(station, time).elevation * RAD2DEG

    @Test
    fun `elevation only and full position agree at the same instant`() {
        val sat = NumericalOrbitalObject(data)
        val quick = sat.getElevation(station, target) * RAD2DEG
        val full = elevationAt(sat, target)
        assertEquals(quick, full, 1e-9)
    }

    @Test
    fun `a cold propagator matches a warm one`() {
        val warm = NumericalOrbitalObject(data)
        elevationAt(warm, target)
        val cold = NumericalOrbitalObject(data)
        assertEquals(elevationAt(warm, target), elevationAt(cold, target), 1e-6)
    }

    @Test
    fun `walking forward first does not change the answer`() {
        val scanned = NumericalOrbitalObject(data)
        var time = target - 40 * 60_000L
        while (time < target) {
            scanned.getElevation(station, time)
            time += 30_000L
        }
        val cold = NumericalOrbitalObject(data)
        assertEquals(elevationAt(cold, target), elevationAt(scanned, target), 1e-6)
    }

    @Test
    fun `walking backwards first does not change the answer`() {
        val backwards = NumericalOrbitalObject(data)
        var time = target + 40 * 60_000L
        while (time > target) {
            backwards.getElevation(station, time)
            time -= 30_000L
        }
        val cold = NumericalOrbitalObject(data)
        assertEquals(elevationAt(cold, target), elevationAt(backwards, target), 1e-6)
    }

    @Test
    fun `the AOS found by the scan lies on the horizon for the sampler`() {
        val sat = NearEarthObject(data)
        var time = target
        // Start from the ground: stepping back sixty seconds from a point inside a long pass
        // still lands inside that pass, and the "AOS" would then be a midpoint with a large
        // elevation. Which case happens depends on the station, so it has to be handled, not
        // assumed away.
        while (sat.getElevation(station, time) > 0.0) time += 30_000L
        while (sat.getElevation(station, time) < 0.0) time += 60_000L
        time -= 60_000L
        while (sat.getElevation(station, time) < 0.0) time += 500L
        val aos = 1000 * ((time + 500) / 1000)
        val aosElevation = sat.getElevation(station, aos) * RAD2DEG

        var los = aos + 60_000L
        var scannedMax = 0.0
        while (sat.getElevation(station, los) > 0.0) {
            scannedMax = maxOf(scannedMax, sat.getElevation(station, los) * RAD2DEG)
            los += 30_000L
        }
        los -= 30_000L
        while (sat.getElevation(station, los) > 0.0) los += 500L
        val losTime = 1000 * ((los + 500) / 1000)

        val samples = PassSampler.sample(sat, station, aos, losTime, count = 31)
        val metrics = PassSampler.metrics(sat, station, aos, losTime, samples)

        assertTrue("AOS should sit on the horizon, was $aosElevation", aosElevation < 0.05)
        assertEquals(
            "the sampler must agree with the scan at the first sample",
            aosElevation,
            samples.first().elevationDeg,
            0.01
        )
        // The thirty-second scan is a lower bound by construction: it can step over the true peak.
        // What must hold is that the refined figure is never below that bound and never more than
        // one sampling step's worth above it. A degree of slack here would still catch a TCA search
        // that wandered, which is what this test exists for.
        assertTrue(
            "refined peak ${metrics.maxElevationDeg} must not be below the scanned $scannedMax",
            metrics.maxElevationDeg >= scannedMax - 0.01
        )
        assertEquals(
            "peak elevation must not depend on the sampling used to find it",
            scannedMax,
            metrics.maxElevationDeg,
            0.5
        )
    }
}
