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

import java.io.File
import kotlin.math.acos
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Precision window report and gates.
 *
 * IMPORTANT — what this test does and does not prove:
 *  - [printConsistencyTable] compares the RK45 kernel against the analytic SGP4/SDP4 kernel of the
 *    same element set. That is an **internal consistency** measurement: it shows the integrator
 *    reproduces the reference kernel's trajectory (and therefore that the new code path is not
 *    broken), but it is NOT evidence of absolute accuracy. TLE-vs-TLE proves nothing about truth.
 *  - [truthGate] is the real gate. It needs precise ephemeris fixtures as CSV files in
 *    `src/test/resources/ephemeris` (columns: `noradId,secondsFromEpoch,xKm,yKm,zKm`, TEME
 *    or J2000 as declared in the header). Without fixtures it is **skipped**, never silently
 *    passed. See docs/STAGE1-AUDIT.md section 4.5 for why the fixtures could not be downloaded here.
 */
class PrecisionWindowTest {

    private val satellites = TleFixtures.load()
    private val checkpointsDays = listOf(1, 3, 7, 14, 21, 30)

    @Test
    fun printConsistencyTable() {
        println()
        println("=== numerical (RK45) vs analytic (SGP4/SDP4): in-track time offset, seconds ===")
        println(String.format("%-18s %8s %8s %8s %8s %8s %8s", "satellite", "1d", "3d", "7d", "14d", "21d", "30d"))
        for (data in satellites) {
            val analytic = analyticFor(data)
            val propagator = NumericalPropagator(data, analytic)
            val row = StringBuilder(String.format("%-18s", data.name.take(18)))
            for (day in checkpointsDays) {
                val tSec = day * 86_400.0
                val numerical = DoubleArray(6)
                propagator.propagateSeconds(tSec, numerical)
                val reference = analytic.temeStateKm(tSec / 60.0)
                row.append(String.format(" %8.2f", inTrackSeconds(numerical, reference)))
            }
            row.append(String.format("   B=%.3e cal=%s", propagator.ballisticCoefficient, propagator.isDragCalibrated))
            println(row)
        }
        println("note: this table measures agreement with SGP4, NOT accuracy against truth.")
        println()
    }

    @Test
    fun highPrecisionWindowGate() {
        // Segmented policy agreed for this build: 1 s inside two days, 3 s up to seven days.
        // Longer checkpoints are reported (see above) but not asserted, because the drag model
        // error alone exceeds the budget there for every TLE-driven propagator.
        //
        // GEO is held to a looser gate on purpose: SDP4 carries the 2:1 tesseral resonance terms
        // that keep a geostationary satellite's longitude libration, and this force model only has
        // the zonal harmonics. The gap is measured and reported rather than papered over.
        for (data in satellites) {
            val analytic = analyticFor(data)
            val propagator = NumericalPropagator(data, analytic)
            val isGeo = data.meanmo < 1.1
            // GEO is deliberately loose and documented as a known gap: SDP4 carries the 2:1
            // tesseral resonance that governs longitude libration, and this force model only has
            // zonal harmonics. Until resonance terms land, GEO agreement with SDP4 is seconds per
            // day and its absolute accuracy is UNVERIFIED. The dashboard must not advertise a
            // high-precision window for GEO satellites.
            val day1Limit = if (isGeo) 8.0 else 1.0
            val day3Limit = if (isGeo) 25.0 else 3.0
            val oneDay = offsetSeconds(propagator, analytic, 86_400.0)
            assertTrue("${data.name}: 1-day offset ${oneDay}s > ${day1Limit}s", oneDay <= day1Limit)
            val threeDays = offsetSeconds(propagator, analytic, 3 * 86_400.0)
            assertTrue("${data.name}: 3-day offset ${threeDays}s > ${day3Limit}s", threeDays <= day3Limit)
        }
    }

    @Test
    fun truthGate() {
        val fixtureDir = File("src/test/resources/ephemeris")
        val fixtures = fixtureDir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
        assumeTrue(
            "SKIPPED: no precise-ephemeris fixtures in src/test/resources/ephemeris — " +
                "absolute accuracy is unproven (see docs/STAGE1-AUDIT.md section 4.5)",
            fixtures.isNotEmpty()
        )
        for (fixture in fixtures) {
            val rows = fixture.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("noradId") }
                .map { it.split(",").map(String::trim) }
            if (rows.isEmpty()) continue
            val noradId = rows.first()[0].toInt()
            val data = satellites.firstOrNull { it.catnum == noradId } ?: continue
            val analytic = analyticFor(data)
            val propagator = NumericalPropagator(data, analytic)
            for (row in rows) {
                val tSec = row[1].toDouble()
                val truth = doubleArrayOf(row[2].toDouble(), row[3].toDouble(), row[4].toDouble(), 0.0, 0.0, 0.0)
                val numerical = DoubleArray(6)
                propagator.propagateSeconds(tSec, numerical)
                val offset = inTrackSeconds(numerical, truth)
                assertTrue(
                    "${data.name} @ ${tSec / 86_400.0}d: along-track offset ${offset}s > 1.0s",
                    offset <= 1.0
                )
            }
        }
    }

    private fun offsetSeconds(data: NumericalPropagator, probe: OrbitalObject, tSec: Double): Double {
        val numerical = DoubleArray(6)
        data.propagateSeconds(tSec, numerical)
        return inTrackSeconds(numerical, probe.temeStateKm(tSec / 60.0))
    }

    /** Along-track separation converted to a time offset using the satellite's own speed. */
    private fun inTrackSeconds(a: DoubleArray, b: DoubleArray): Double {
        val ra = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val rb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (ra * rb)
        val angle = acos(dot.coerceIn(-1.0, 1.0))
        val hx = a[1] * a[5] - a[2] * a[4]
        val hy = a[2] * a[3] - a[0] * a[5]
        val hz = a[0] * a[4] - a[1] * a[3]
        val cx = a[1] * b[2] - a[2] * b[1]
        val cy = a[2] * b[0] - a[0] * b[2]
        val cz = a[0] * b[1] - a[1] * b[0]
        val sign = if (cx * hx + cy * hy + cz * hz >= 0.0) 1.0 else -1.0
        val separation = sign * angle * (ra + rb) / 2.0
        val speed = sqrt(a[3] * a[3] + a[4] * a[4] + a[5] * a[5]).coerceAtLeast(0.1)
        return separation / speed
    }

    private fun analyticFor(data: OrbitalData): OrbitalObject =
        if (data.isDeepSpace) DeepSpaceObject(data) else NearEarthObject(data)
}
