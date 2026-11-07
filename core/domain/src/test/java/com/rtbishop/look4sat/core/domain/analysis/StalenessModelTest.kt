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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class StalenessModelTest {

    /** Real ISS element set from the device database, captured 2026-11-07. */
    private val stale = OrbitalData(
        name = "ISS (ZARYA)", epoch = 26302.56368278, meanmo = 15.49100842, eccn = 0.000705,
        incl = 51.6333, raan = 355.1294, argper = 324.3141, meanan = 35.7375,
        catnum = 25544, bstar = 0.00010765, ndot = 5.507e-05
    )

    /** Real ISS element set from CelesTrak the same day, 8.32 days newer. */
    private val fresh = OrbitalData(
        name = "ISS (ZARYA)", epoch = 26310.88530624, meanmo = 15.49223765, eccn = 0.0007239,
        incl = 51.6322, raan = 313.9479, argper = 356.4073, meanan = 3.6863,
        catnum = 25544, bstar = 0.00022778, ndot = 0.00012224
    )

    @Test
    fun `measuring a real pair reports the age and the error`() {
        val sample = StalenessModel.measure(stale, fresh)

        assertNotNull(sample)
        assertEquals(8.32, sample!!.ageDays, 0.02)
        // Measured with this app's own propagator: 46.3 km along track at 7.66 km/s.
        assertEquals(6.03, sample.seconds, 0.3)
    }

    @Test
    fun `an unusable pair yields no sample instead of a wrong one`() {
        assertNull("identical epochs cannot teach anything", StalenessModel.measure(fresh, fresh))
        assertNull("an older reference is not a reference", StalenessModel.measure(fresh, stale))
        assertNull(
            "different objects are not comparable",
            StalenessModel.measure(stale, fresh.copy(catnum = 25543))
        )
    }

    @Test
    fun `prediction is zero at the epoch and grows with age`() {
        assertEquals(0.0, StalenessCalibration.EMPTY.predictedSeconds(0.0), 0.0)
        var previous = 0.0
        for (age in 1..40) {
            val predicted = StalenessCalibration.EMPTY.predictedSeconds(age.toDouble())
            assertTrue("age $age must predict more than age ${age - 1}", predicted > previous)
            previous = predicted
        }
    }

    @Test
    fun `the seed keeps the ten second budget to about nine days`() {
        // This is the number the whole feature is built on: how long an element set may sit unused.
        assertTrue(StalenessCalibration.EMPTY.predictedSeconds(8.0) < 10.0)
        assertFalse(StalenessCalibration.EMPTY.exceedsBudget(8.0))
        assertTrue(StalenessCalibration.EMPTY.predictedSeconds(11.0) > 10.0)
        assertTrue(StalenessCalibration.EMPTY.exceedsBudget(11.0))
    }

    @Test
    fun `the seed tracks the measured curve`() {
        // Measured values (docs/STALENESS.md): 8.32 d -> 6.0 s, 15.32 d -> 38.1 s, 28.32 d -> 188.1 s.
        val measured = listOf(8.32 to 6.0, 15.32 to 38.1, 28.32 to 188.1)
        for ((age, seconds) in measured) {
            val predicted = StalenessCalibration.EMPTY.predictedSeconds(age)
            val error = kotlin.math.abs(predicted - seconds) / seconds
            assertTrue("age $age predicted $predicted vs measured $seconds", error < 0.2)
        }
    }

    @Test
    fun `refitting recovers a known power law`() {
        val samples = (2..30).map { age ->
            ErrorSample(ageDays = age.toDouble(), seconds = 0.01 * Math.pow(age.toDouble(), 2.8))
        }
        val fitted = StalenessCalibration.refit(samples)

        assertEquals(2.8, fitted.exponent, 0.02)
        assertEquals(0.01, fitted.secondsAtOneDay, 0.0005)
    }

    @Test
    fun `refitting refuses to extrapolate a meaningless slope`() {
        // Ages within a single day give almost no leverage on the exponent; the fit is clamped.
        val tight = (1..20).map { ErrorSample(ageDays = 0.96 + it * 0.001, seconds = 0.01 * it) }
        val fitted = StalenessCalibration.refit(tight)
        assertTrue(fitted.exponent in 2.0..5.0)
    }

    @Test
    fun `samples too young to have drifted are ignored`() {
        val calibration = StalenessCalibration.EMPTY.withSample(ErrorSample(ageDays = 0.001, seconds = 500.0))
        assertEquals(0, calibration.sampleCount)
    }

    @Test
    fun `the reservoir keeps the newest samples`() {
        var calibration = StalenessCalibration.EMPTY
        repeat(StalenessCalibration.MAX_SAMPLES + 40) { index ->
            calibration = calibration.withSample(
                ErrorSample(ageDays = 0.5 + index * 0.01, seconds = 0.02 * Math.pow(0.5 + index * 0.01, 2.7))
            )
        }
        assertEquals(StalenessCalibration.MAX_SAMPLES, calibration.sampleCount)
        // The oldest of the excess is gone, the newest is kept.
        assertTrue(calibration.samples.last().ageDays > 5.0)
    }

    @Test
    fun `calibration survives a round trip and garbage degrades to the seed`() {
        val calibration = StalenessCalibration.EMPTY
            .withSample(ErrorSample(ageDays = 9.0, seconds = 7.0))
            .withSample(ErrorSample(ageDays = 12.0, seconds = 14.0))
        val restored = StalenessCalibration.decode(calibration.encode())

        assertEquals(calibration.sampleCount, restored.sampleCount)
        assertEquals(calibration, restored)
        assertEquals(StalenessCalibration.EMPTY, StalenessCalibration.decode("{not json"))
        assertEquals(StalenessCalibration.EMPTY, StalenessCalibration.decode(""))
    }

    @Test
    fun `a fitted model beats the seed on its own samples`() {
        // Ten synthetic updates of a satellite that ages faster than the seed assumes.
        val samples = (1..10).map { step ->
            val age = step.toDouble()
            ErrorSample(ageDays = age, seconds = 0.05 * Math.pow(age, 2.726))
        }
        val fitted = StalenessCalibration.refit(samples)
        val seedError = samples.sumOf {
            kotlin.math.abs(StalenessCalibration.EMPTY.predictedSeconds(it.ageDays) - it.seconds) / it.seconds
        }
        val fittedError = samples.sumOf {
            kotlin.math.abs(fitted.predictedSeconds(it.ageDays) - it.seconds) / it.seconds
        }
        assertTrue("fitting must not make the model worse", fittedError <= seedError)
        // ln(k) lands where the data says it should.
        assertEquals(ln(0.05), ln(fitted.secondsAtOneDay), 0.05)
    }
}
