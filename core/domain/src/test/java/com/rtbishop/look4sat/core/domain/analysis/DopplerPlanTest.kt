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

import com.rtbishop.look4sat.core.domain.model.SatRadio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DopplerPlanTest {

    private fun radio(
        downlinkLow: Long? = 145_800_000L,
        downlinkHigh: Long? = 145_820_000L,
        uplinkLow: Long? = 435_000_000L,
        uplinkHigh: Long? = 435_020_000L,
        inverted: Boolean = false
    ) = SatRadio(
        uuid = "test",
        downlinkLow = downlinkLow,
        downlinkHigh = downlinkHigh,
        uplinkLow = uplinkLow,
        uplinkHigh = uplinkHigh,
        isInverted = inverted
    )

    private fun sample(time: Long, rateKmS: Double, elevation: Double = 10.0) = PassSample(
        time = time,
        azimuthDeg = 90.0,
        elevationDeg = elevation,
        rangeKm = 1500.0,
        rangeRateKmS = rateKmS,
        altitudeKm = 420.0,
        sunlit = true
    )

    @Test
    fun `a receding satellite reads low and needs a high uplink`() {
        val down = DopplerPlan.correctedDownlinkHz(145_800_000L, rangeRateKmS = 1.0)
        val up = DopplerPlan.correctedUplinkHz(145_800_000L, rangeRateKmS = 1.0)
        assertEquals(145_799_514L, down)
        assertEquals(145_800_486L, up)
        assertTrue(down < 145_800_000L && up > 145_800_000L)
    }

    @Test
    fun `an approaching satellite reads high and needs a low uplink`() {
        val down = DopplerPlan.correctedDownlinkHz(145_800_000L, rangeRateKmS = -1.0)
        val up = DopplerPlan.correctedUplinkHz(145_800_000L, rangeRateKmS = -1.0)
        assertEquals(145_800_486L, down)
        assertEquals(145_799_514L, up)
    }

    @Test
    fun `zero range rate leaves the frequency untouched`() {
        assertEquals(437_000_000L, DopplerPlan.correctedDownlinkHz(437_000_000L, 0.0))
        assertEquals(437_000_000L, DopplerPlan.correctedUplinkHz(437_000_000L, 0.0))
    }

    @Test
    fun `equal passbands reduce the inverted mapping to the sum rule`() {
        val mapped = DopplerPlan.invertedUplinkHz(
            downlinkLow = 145_800_000L,
            downlinkHigh = 145_820_000L,
            uplinkLow = 435_000_000L,
            uplinkHigh = 435_020_000L,
            observedDownlinkHz = 145_810_000L
        )
        // Classic shortcut: upLow + upHigh - rx
        assertEquals(435_010_000L, mapped)
    }

    @Test
    fun `unequal passbands scale instead of shifting`() {
        // 20 kHz downlink against 40 kHz uplink: the mapping must compress by a factor of two.
        val atLowEdge = DopplerPlan.invertedUplinkHz(145_800_000L, 145_820_000L, 435_000_000L, 435_040_000L, 145_800_000L)
        val atHighEdge = DopplerPlan.invertedUplinkHz(145_800_000L, 145_820_000L, 435_000_000L, 435_040_000L, 145_820_000L)
        assertEquals(435_040_000L, atLowEdge)
        assertEquals(435_000_000L, atHighEdge)
    }

    @Test
    fun `incomplete passband data yields no mapping`() {
        assertNull(DopplerPlan.invertedUplinkHz(145_800_000L, null, 435_000_000L, 435_020_000L, 145_810_000L))
        assertNull(DopplerPlan.invertedUplinkHz(145_820_000L, 145_800_000L, 435_000_000L, 435_020_000L, 145_810_000L))
    }

    @Test
    fun `plan covers every sample and inverts the uplink edge`() {
        val plan = DopplerPlan.forTransponder(
            radio(inverted = true),
            listOf(sample(0L, 0.0), sample(1000L, 2.0), sample(2000L, -2.0))
        )
        assertEquals(3, plan.size)
        // Inverting means the lowest downlink edge is produced by the highest uplink edge.
        assertEquals(435_020_000L, plan[0].uplinkHz)
        assertEquals(145_800_000L, plan[0].downlinkHz)
        assertTrue("receding uplink must rise", plan[1].uplinkHz!! > plan[0].uplinkHz!!)
    }

    @Test
    fun `non inverting plan uses the published uplink edge`() {
        val plan = DopplerPlan.forTransponder(radio(), listOf(sample(0L, 0.0)))
        assertEquals(435_000_000L, plan[0].uplinkHz)
    }

    @Test
    fun `downlink only transponder still produces a curve`() {
        val plan = DopplerPlan.forTransponder(
            radio(uplinkLow = null, uplinkHigh = null),
            listOf(sample(0L, 1.0), sample(1000L, -1.0))
        )
        assertEquals(2, plan.size)
        assertNull(plan[0].uplinkHz)
        assertTrue(plan[0].downlinkHz!! < plan[1].downlinkHz!!)
    }

    @Test
    fun `peak deviation scales with the largest range rate`() {
        val plan = listOf(
            DopplerPoint(0L, 145_800_000L, null),
            DopplerPoint(1L, 145_803_000L, null),
            DopplerPoint(2L, 145_797_000L, null)
        )
        assertEquals(3_000L, DopplerPlan.peakDeviationHz(plan, 145_800_000L))
    }
}
