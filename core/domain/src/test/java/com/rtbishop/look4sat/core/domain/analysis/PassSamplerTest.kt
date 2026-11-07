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

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.NearEarthObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.RAD2DEG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PassSamplerTest {

    // A real ISS element set. The analytic kernel is used directly so the test does not depend on
    // the numerical propagator's configuration or on its drag calibration costing seconds per run.
    private val data = OrbitalData(
        name = "ISS (ZARYA)",
        epoch = 24001.5,
        meanmo = 15.49512345,
        eccn = 0.0006703,
        incl = 51.6416,
        raan = 247.4627,
        argper = 130.5360,
        meanan = 325.0288,
        catnum = 25544,
        bstar = 0.00010270
    )
    private val satellite = NearEarthObject(data)
    private val station = GeoPos(51.4778, -0.0015, 20.0)
    private val epochMillis = 315_446_400_000L + (data.epochDaynum * 86_400_000.0).toLong()

    /** First minute after the epoch at which the satellite is comfortably above the horizon. */
    private fun visibleTime(): Long {
        var time = epochMillis
        val limit = epochMillis + 2 * 86_400_000L
        while (time < limit) {
            if (satellite.getElevation(station, time) * RAD2DEG > 30.0) return time
            time += 60_000L
        }
        error("no visible window within two days of the epoch")
    }

    @Test
    fun `samples are evenly spaced and cover the whole window`() {
        val center = visibleTime()
        val samples = PassSampler.sample(satellite, station, center - 360_000L, center + 360_000L, count = 61)
        assertEquals(61, samples.size)
        assertEquals(center - 360_000L, samples.first().time)
        assertEquals(center + 360_000L, samples.last().time)
        for (index in 1 until samples.size) {
            assertTrue("time went backwards at $index", samples[index].time > samples[index - 1].time)
        }
        assertTrue(samples.any { it.elevationDeg > 20.0 })
    }

    @Test
    fun `range is positive and range rate changes sign across closest approach`() {
        val center = visibleTime()
        val samples = PassSampler.sample(satellite, station, center - 300_000L, center + 300_000L, count = 51)
        assertTrue(samples.all { it.rangeKm > 200.0 })
        assertTrue("no approaching sample", samples.any { it.rangeRateKmS < -0.1 })
        assertTrue("no receding sample", samples.any { it.rangeRateKmS > 0.1 })
    }

    @Test
    fun `ternary search finds a closer point than any sample`() {
        val center = visibleTime()
        val start = center - 300_000L
        val end = center + 300_000L
        val samples = PassSampler.sample(satellite, station, start, end, count = 21)
        val tca = PassSampler.refineClosestApproach(satellite, station, start, end)
        assertTrue("tca $tca outside window", tca in start..end)
        val refinedRange = satellite.getFullPosition(station, tca).distance
        val bestSampled = samples.minOf { it.rangeKm }
        assertTrue(
            "refined $refinedRange should not be worse than sampled $bestSampled",
            refinedRange <= bestSampled + 1e-6
        )
    }

    @Test
    fun `metrics agree with the samples they came from`() {
        val center = visibleTime()
        val start = center - 300_000L
        val end = center + 300_000L
        val samples = PassSampler.sample(satellite, station, start, end, count = 31)
        val metrics = PassSampler.metrics(satellite, station, start, end, samples)

        assertEquals(600L, metrics.durationSeconds)
        assertTrue(metrics.tcaTime in start..end)
        assertTrue("max elevation ${metrics.maxElevationDeg}", metrics.maxElevationDeg > 20.0)
        assertTrue("min ${metrics.minRangeKm} vs max ${metrics.maxRangeKm}", metrics.minRangeKm < metrics.maxRangeKm)
        assertTrue(metrics.peakRangeRateKmS > 0.0)
        assertTrue(metrics.sunlitFraction in 0.0..1.0)
        assertTrue("sweep ${metrics.azimuthSweepDeg}", abs(metrics.azimuthSweepDeg) < 360.0)
    }

    @Test
    fun `peak doppler at 145 MHz is a few kilohertz, not a few hundred`() {
        val center = visibleTime()
        val start = center - 300_000L
        val end = center + 300_000L
        val samples = PassSampler.sample(satellite, station, start, end, count = 31)
        val metrics = PassSampler.metrics(satellite, station, start, end, samples)
        // LEO range rates reach about 7 km/s at the horizon; at 145.8 MHz that is ~3.4 kHz.
        val peak = metrics.peakDopplerHz(145_800_000L)
        assertTrue("peak doppler $peak Hz", peak in 500L..12_000L)
    }

    @Test
    fun `a deep space pass finds the closest approach near the sampled minimum`() {
        // A geostationary pass spans two days and the range oscillates with the orbit, so an
        // unbracketed search over the whole window wanders off. The metrics must land on the sample
        // minimum instead.
        val geo = OrbitalData(
            name = "GEO TEST",
            epoch = 26310.0,
            meanmo = 1.0027,
            eccn = 0.0001,
            incl = 0.05,
            raan = 95.0,
            argper = 0.0,
            meanan = 0.0,
            catnum = 99999,
            bstar = 0.0
        )
        val deepSpace = geo.getObject()
        val geoStation = GeoPos(0.0, 95.0, 0.0)
        val start = 315_446_400_000L + (geo.epochDaynum * 86_400_000.0).toLong()
        val end = start + 2 * 86_400_000L
        val samples = PassSampler.sample(deepSpace, geoStation, start, end, count = 25)
        val metrics = PassSampler.metrics(deepSpace, geoStation, start, end, samples)

        val closest = samples.minByOrNull { it.rangeKm }!!
        val stepMillis = (end - start) / (samples.size - 1)
        assertTrue(
            "TCA ${metrics.tcaTime} should sit within one sample of the sampled minimum ${closest.time}",
            abs(metrics.tcaTime - closest.time) <= stepMillis * 2
        )
        assertTrue(
            "max elevation ${metrics.maxElevationDeg} should match the sampled curve",
            abs(metrics.maxElevationDeg - samples.maxOf { it.elevationDeg }) < 1.0
        )
    }

    @Test
    fun `degenerate windows produce nothing instead of throwing`() {
        assertTrue(PassSampler.sample(satellite, station, 1000L, 1000L).isEmpty())
        assertTrue(PassSampler.sample(satellite, station, 2000L, 1000L).isEmpty())
    }
}
