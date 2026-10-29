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

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctness tests for the numerical kernel. These do **not** claim absolute accuracy — that
 * needs an external precise ephemeris (see [PrecisionWindowTest]). They prove the pieces are
 * individually right: the J2 gradient, the RK45 pair, the epoch continuity with SGP4 and the
 * geodetic conversion.
 */
class NumericalPropagatorTest {

    private val satellites = TleFixtures.load()

    @Test
    fun fixtureCoversEveryOrbitClass() {
        assertTrue("need at least 2 LEO fixtures", satellites.count { it.meanmo > 11.0 } >= 2)
        assertTrue("need at least 2 sun-synchronous fixtures", satellites.count { abs(it.incl - 98.0) < 2.0 } >= 2)
        assertTrue("need at least 1 MEO fixture", satellites.count { it.meanmo in 1.5..5.0 } >= 1)
        assertTrue("need at least 2 GEO fixtures", satellites.count { it.meanmo < 1.1 } >= 2)
    }

    @Test
    fun numericalStateAtEpochEqualsSgp4State() {
        for (data in satellites) {
            val analytic = analyticObject(data)
            val epochState = analytic.temeStateKm(0.0)
            val propagator = NumericalPropagator(data, analytic)
            val out = DoubleArray(6)
            propagator.propagateMinutes(0.0, out)
            val dr = sqrt(
                (out[0] - epochState[0]).pow(2) +
                    (out[1] - epochState[1]).pow(2) +
                    (out[2] - epochState[2]).pow(2)
            )
            assertTrue("${data.name}: epoch state drifted by $dr km", dr < 1.0e-6)
        }
    }

    @Test
    fun zonalAccelerationMatchesClosedFormJ2() {
        val model = ForceModel(useDrag = false, useThirdBody = false, zonalDegree = 2)
        val positions = listOf(
            doubleArrayOf(7000.0, 0.0, 0.0),
            doubleArrayOf(5000.0, 3000.0, 2000.0),
            doubleArrayOf(0.0, 0.0, 8000.0),
            doubleArrayOf(-4200.0, -4200.0, -3000.0)
        )
        for (p in positions) {
            val out = DoubleArray(3)
            model.zonalAcceleration(p[0], p[1], p[2], out)
            val r2 = p[0] * p[0] + p[1] * p[1] + p[2] * p[2]
            val r = sqrt(r2)
            val r5 = r2 * r2 * r
            val z2 = p[2] * p[2] / r2
            val k = ForceModel.MU_EARTH / r2 / r // -mu/r^3 without the sign
            val j2 = ForceModel.ZONAL[2]
            val expectedX =
                -k * p[0] - 1.5 * j2 * ForceModel.MU_EARTH * EARTH_RADIUS * EARTH_RADIUS * p[0] / r5 * (1.0 - 5.0 * z2)
            val expectedY =
                -k * p[1] - 1.5 * j2 * ForceModel.MU_EARTH * EARTH_RADIUS * EARTH_RADIUS * p[1] / r5 * (1.0 - 5.0 * z2)
            val expectedZ =
                -k * p[2] - 1.5 * j2 * ForceModel.MU_EARTH * EARTH_RADIUS * EARTH_RADIUS * p[2] / r5 * (3.0 - 5.0 * z2)
            assertEquals("J2 ax", expectedX, out[0], 1.0e-9)
            assertEquals("J2 ay", expectedY, out[1], 1.0e-9)
            assertEquals("J2 az", expectedZ, out[2], 1.0e-9)
        }
    }

    @Test
    fun rk45KeepsTwoBodyOrbitClosed() {
        val mu = ForceModel.MU_EARTH
        val r0 = 7000.0
        val v0 = sqrt(mu / r0)
        val state = doubleArrayOf(r0, 0.0, 0.0, 0.0, v0, 0.0)
        val period = 2.0 * Math.PI * sqrt(r0 * r0 * r0 / mu)
        val derivative = Derivative { _, y, out ->
            val r2 = y[0] * y[0] + y[1] * y[1] + y[2] * y[2]
            val r = sqrt(r2)
            val k = -mu / (r2 * r)
            out[0] = y[3]
            out[1] = y[4]
            out[2] = y[5]
            out[3] = k * y[0]
            out[4] = k * y[1]
            out[5] = k * y[2]
        }
        val integrator = DormandPrince(relativeTolerance = 1.0e-12, absoluteTolerance = 1.0e-12, maxStep = 60.0)
        val energyBefore = energy(state, mu)
        integrator.integrate(state, 0.0, period, derivative)
        val energyAfter = energy(state, mu)
        val dr = sqrt((state[0] - r0).pow(2) + state[1].pow(2) + state[2].pow(2))
        assertTrue("one-orbit position closure $dr km", dr < 1.0e-5)
        // DOPRI5 with rtol/atol 1e-12 keeps |dE/E| around 1e-11 per orbit; 1e-10 is the gate.
        assertTrue("relative energy drift", abs((energyAfter - energyBefore) / energyBefore) < 1.0e-10)
    }

    @Test
    fun numericalForwardPassKeepsCacheConsistent() {
        val data = satellites.first { it.meanmo > 11.0 }
        val analytic = analyticObject(data)
        val propagator = NumericalPropagator(data, analytic)
        val direct = DoubleArray(6)
        propagator.propagateMinutes(180.0, direct)

        // Walk forward in 30 s steps the way pass prediction does, then compare the endpoint.
        val stepped = DoubleArray(6)
        var minute = 0.0
        while (minute < 180.0) {
            minute += 0.5
            propagator.propagateMinutes(minute, stepped)
        }
        val dr = sqrt((stepped[0] - direct[0]).pow(2) + (stepped[1] - direct[1]).pow(2) + (stepped[2] - direct[2]).pow(2))
        // 100 m: the cached path may take a different number of adaptive steps than the direct one.
        assertTrue("${data.name}: cached walk diverged by $dr km", dr < 1.0e-4)
    }

    @Test
    fun geodeticMatchesAnalyticKernel() {
        val data = satellites.first { it.meanmo > 11.0 }
        val analytic = analyticObject(data)
        val propagator = NumericalPropagator(data, analytic)
        val observer = GeoPos(0.0, 0.0, 0.0)
        val epochMillis = daynumToMillis(OrbitalData.epochToDaynum(data.epoch))
        val tSec = 1800.0

        val analyticPos = analytic.getPosition(observer, epochMillis + (tSec * 1000).toLong())
        val geo = DoubleArray(3)
        propagator.geodeticAt(tSec, geo)

        val dLat = abs(Math.toDegrees(geo[0]) - Math.toDegrees(analyticPos.latitude))
        val dLon = abs(Math.toDegrees(geo[1]) - Math.toDegrees(analyticPos.longitude))
        assertTrue("${data.name}: latitude delta $dLat deg", dLat < 0.02)
        assertTrue("${data.name}: longitude delta $dLon deg", dLon < 0.02)
        assertEquals("${data.name}: altitude", analyticPos.altitude, geo[2], 5.0)
    }

    private fun energy(state: DoubleArray, mu: Double): Double {
        val r = sqrt(state[0] * state[0] + state[1] * state[1] + state[2] * state[2])
        val v2 = state[3] * state[3] + state[4] * state[4] + state[5] * state[5]
        return v2 / 2.0 - mu / r
    }

    private fun analyticObject(data: OrbitalData): OrbitalObject =
        if (data.isDeepSpace) DeepSpaceObject(data) else NearEarthObject(data)
}

/** Loads the checked-in TLE fixtures with the same fixed-column layout the app parses. */
internal object TleFixtures {

    fun load(): List<OrbitalData> {
        val stream = TleFixtures::class.java.getResourceAsStream("/tle/precision-tles.txt")
            ?: error("missing /tle/precision-tles.txt test fixture")
        val lines = stream.bufferedReader().use { it.readLines() }
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
        return lines.chunked(3).mapNotNull { chunk ->
            if (chunk.size < 3) return@mapNotNull null
            val name = chunk[0].trim()
            val l1 = chunk[1]
            val l2 = chunk[2]
            if (!l1.startsWith("1") || !l2.startsWith("2")) return@mapNotNull null
            OrbitalData(
                name = name,
                epoch = l1.substring(18, 32).toDouble(),
                meanmo = l2.substring(52, 63).toDouble(),
                eccn = l2.substring(26, 33).toDouble() / 1.0e7,
                incl = l2.substring(8, 16).toDouble(),
                raan = l2.substring(17, 25).toDouble(),
                argper = l2.substring(34, 42).toDouble(),
                meanan = l2.substring(43, 51).toDouble(),
                catnum = l1.substring(2, 7).trim().toInt(),
                bstar = parseExponent(l1.substring(53, 61)),
                ndot = l1.substring(33, 43).trim().toDouble()
            )
        }
    }

    private fun parseExponent(field: String): Double {
        val mantissaSign = if (field[0] == '-') "-" else ""
        return "${mantissaSign}0.${field.substring(1, 6)}E${field.substring(6)}".toDouble()
    }
}
