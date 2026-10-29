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
package com.rtbishop.look4sat.core.domain.merge

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TleMergeEngineTest {

    @Test
    fun sameIdWithDifferentEpochKeepsTheNewestElements() {
        val older = tle("ISS (ZARYA)", catnum = 25544, epoch = 26_300.5, meanmo = 15.49)
        val newer = tle("ISS (ZARYA)", catnum = 25544, epoch = 26_301.5, meanmo = 15.50)

        val outcome = TleMergeEngine.merge(
            listOf(
                TleSourceBatch("CelesTrak", 0, listOf(older)),
                TleSourceBatch("SatNOGS", 1, listOf(newer))
            )
        )

        assertEquals(1, outcome.entries.size)
        assertEquals(26_301.5, outcome.entries.single().epoch, 1.0e-9)
        assertEquals(15.50, outcome.entries.single().meanmo, 1.0e-9)
    }

    @Test
    fun sameIdWithSameEpochPrefersTheHigherPrioritySource() {
        val fromSatNogs = tle("ISS (ZARYA)", catnum = 25544, epoch = 26_300.5, meanmo = 15.49)
        val fromCelesTrak = tle("ISS (ZARYA)", catnum = 25544, epoch = 26_300.5, meanmo = 15.51)

        val outcome = TleMergeEngine.merge(
            listOf(
                TleSourceBatch("SatNOGS", 1, listOf(fromSatNogs)),
                TleSourceBatch("CelesTrak", 0, listOf(fromCelesTrak))
            )
        )

        assertEquals(1, outcome.entries.size)
        // Same epoch: the higher-priority feed (CelesTrak, 0) supplies the elements.
        assertEquals(15.51, outcome.entries.single().meanmo, 1.0e-9)
    }

    @Test
    fun satelliteMissingFromOneSourceIsKeptFromTheOther() {
        val iss = tle("ISS (ZARYA)", catnum = 25544, epoch = 26_300.5, meanmo = 15.49)
        val noaa = tle("NOAA 20 (JPSS-1)", catnum = 43013, epoch = 26_300.9, meanmo = 14.19)

        val outcome = TleMergeEngine.merge(
            listOf(
                TleSourceBatch("CelesTrak", 0, listOf(iss, noaa)),
                TleSourceBatch("SatNOGS", 1, listOf(iss))
            )
        )

        assertEquals(2, outcome.entries.size)
        assertTrue(outcome.entries.any { it.catnum == 43013 })
    }

    @Test
    fun emptySourceDoesNotRemoveAnything() {
        val iss = tle("ISS (ZARYA)", catnum = 25544, epoch = 26_300.5, meanmo = 15.49)

        val outcome = TleMergeEngine.merge(
            listOf(
                TleSourceBatch("CelesTrak", 0, listOf(iss)),
                TleSourceBatch("Broken mirror", 1, emptyList())
            )
        )

        assertEquals(1, outcome.entries.size)
        assertEquals(0, outcome.acceptedPerSource["Broken mirror"])
    }

    @Test
    fun provisionalEntriesNeverEnterTheCatalogue() {
        val provisional = listOf(
            tle("TBA - TO BE ASSIGNED", catnum = 98_205, epoch = 26_300.5, meanmo = 15.1),
            tle("UNKNOWN", catnum = 90_001, epoch = 26_300.5, meanmo = 15.1),
            tle("ANALYSIS OBJECT", catnum = 99_001, epoch = 26_300.5, meanmo = 15.1),
            tle("Out of range", catnum = 400_000, epoch = 26_300.5, meanmo = 15.1),
            tle("ISS (ZARYA)", catnum = 25544, epoch = 26_300.5, meanmo = 15.49)
        )

        val outcome = TleMergeEngine.merge(listOf(TleSourceBatch("CelesTrak", 0, provisional)))

        assertEquals(1, outcome.entries.size)
        assertEquals(25544, outcome.entries.single().catnum)
        assertEquals(4, outcome.provisionalDropped)
    }

    @Test
    fun entriesWithoutMeanMotionAreReportedAsMalformed() {
        val broken = tle("BROKEN BIRD", catnum = 12345, epoch = 26_300.5, meanmo = 0.0)

        val outcome = TleMergeEngine.merge(listOf(TleSourceBatch("Mirror", 2, listOf(broken))))

        assertTrue(outcome.entries.isEmpty())
        assertEquals(1, outcome.malformedDropped)
    }

    @Test
    fun nameFollowsSourcePriorityWhileElementsFollowFreshness() {
        val oldButNamed = tle("ISS (ZARYA)", catnum = 25544, epoch = 26_299.0, meanmo = 15.48)
        val freshButUnnamed = tle("ISSCATALOG", catnum = 25544, epoch = 26_301.0, meanmo = 15.50)

        val outcome = TleEngineHelper.merge(
            TleSourceBatch("CelesTrak", 0, listOf(oldButNamed)),
            TleSourceBatch("Mirror", 3, listOf(freshButUnnamed))
        )

        // Elements come from the fresher record, the name from the preferred source.
        assertEquals(26_301.0, outcome.entries.single().epoch, 1.0e-9)
        assertEquals("ISS (ZARYA)", outcome.entries.single().name)
    }

    @Test
    fun trackabilityRejectsPlaceholdersAndZeroMeanMotion() {
        assertTrue(TleMergeEngine.isTrackable(tle("ISS (ZARYA)", 25544, 26_300.5, 15.49)))
        assertFalse(TleMergeEngine.isTrackable(tle("OBJECT A", 25544, 26_300.5, 0.0)))
        assertFalse(TleMergeEngine.isTrackable(tle("TBA", 25544, 26_300.5, 15.0)))
        assertFalse(TleMergeEngine.isTrackable(tle("ISS", 0, 26_300.5, 15.0)))
    }

    private fun tle(name: String, catnum: Int, epoch: Double, meanmo: Double) = OrbitalData(
        name = name,
        epoch = epoch,
        meanmo = meanmo,
        eccn = 0.0007,
        incl = 51.64,
        raan = 3.7,
        argper = 317.3,
        meanan = 42.6,
        catnum = catnum,
        bstar = 1.0681e-4,
        ndot = 5.456e-5
    )
}

private object TleEngineHelper {
    fun merge(vararg batches: TleSourceBatch) = TleMergeEngine.merge(batches.toList())
}
