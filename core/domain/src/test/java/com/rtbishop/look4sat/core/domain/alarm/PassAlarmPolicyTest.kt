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
package com.rtbishop.look4sat.core.domain.alarm

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassAlarmPolicyTest {

    private val minute = 60_000L
    private val now = 1_000_000_000L

    private fun pass(catNum: Int, aosOffsetMin: Long, decayed: Boolean = false): OrbitalPass {
        val data = OrbitalData(
            name = "SAT-$catNum",
            epoch = 24000.0,
            meanmo = 15.5,
            eccn = 0.001,
            incl = 51.6,
            raan = 0.0,
            argper = 0.0,
            meanan = 0.0,
            catnum = catNum,
            bstar = 0.0001
        )
        return OrbitalPass(
            aosTime = now + aosOffsetMin * minute,
            losTime = now + (aosOffsetMin + 10) * minute,
            maxElevation = 45.0,
            orbitalObject = data.getObject(),
            hasDecayed = decayed
        )
    }

    @Test
    fun `earliest reminder wins`() {
        val passes = listOf(pass(1, 60), pass(2, 20), pass(3, 40))
        val target = PassAlarmPolicy.nextTarget(passes, leadMinutes = 10, now = now)
        assertEquals(2, target?.catNum)
        assertEquals(now + 10 * minute, target?.triggerAt)
    }

    @Test
    fun `passes already inside the lead window are skipped`() {
        // AOS in 5 minutes with a 10 minute lead would fire in the past, so it cannot be armed.
        val passes = listOf(pass(1, 5), pass(2, 30))
        val target = PassAlarmPolicy.nextTarget(passes, leadMinutes = 10, now = now)
        assertEquals(2, target?.catNum)
    }

    @Test
    fun `rearm slack prevents refiring the pass that just fired`() {
        val passes = listOf(pass(1, 10))
        assertNull(PassAlarmPolicy.nextTarget(passes, leadMinutes = 10, now = now))
    }

    @Test
    fun `decayed passes are ignored`() {
        val passes = listOf(pass(1, 20, decayed = true), pass(2, 40))
        val target = PassAlarmPolicy.nextTarget(passes, leadMinutes = 10, now = now)
        assertEquals(2, target?.catNum)
    }

    @Test
    fun `empty list yields no alarm`() {
        assertNull(PassAlarmPolicy.nextTarget(emptyList(), leadMinutes = 10, now = now))
    }

    @Test
    fun `lead minutes are clamped`() {
        val passes = listOf(pass(1, 500))
        // 999 minutes clamps to the 120 minute maximum instead of moving the alarm hours out.
        val target = PassAlarmPolicy.nextTarget(passes, leadMinutes = 999, now = now)
        assertEquals(now + 500 * minute - 120 * minute, target?.triggerAt)
    }
}
