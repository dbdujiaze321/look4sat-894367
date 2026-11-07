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

import org.junit.Test
import kotlin.math.abs
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How much prediction quality is lost when the element set is not refreshed.
 *
 * Two real element sets for the ISS are used: the one found in the device database on 2026-11-07
 * (epoch day 302.56, i.e. 8.3 days old) and the one CelesTrak served the same day (epoch 310.89).
 * Nothing here is simulated — these are the numbers the app would have shown on that phone.
 *
 * Nothing is asserted: the output is a measurement report (docs/ACCURACY.md). A test that asserts a
 * numeric error bound would have to be re-baselined every time the input data changes, which is how
 * accuracy tests quietly become meaningless.
 */
class AccuracyReportTest {

    /** Stale set: from the device database, captured 2026-11-07. */
    private val stale = OrbitalData(
        name = "ISS (ZARYA)",
        epoch = 26302.56368278,
        meanmo = 15.49100842,
        eccn = 0.000705,
        incl = 51.6333,
        raan = 355.1294,
        argper = 324.3141,
        meanan = 35.7375,
        catnum = 25544,
        bstar = 0.00010765,
        ndot = 5.507e-05
    )

    /** Fresh set: CelesTrak GP, fetched 2026-11-07. */
    private val fresh = OrbitalData(
        name = "ISS (ZARYA)",
        epoch = 26310.88530624,
        meanmo = 15.49223765,
        eccn = 0.0007239,
        incl = 51.6322,
        raan = 313.9479,
        argper = 356.4073,
        meanan = 3.6863,
        catnum = 25544,
        bstar = 0.00022778,
        ndot = 0.00012224
    )

    private val station = GeoPos(51.4778, -0.0015, 0.0)
    private val day = 86_400_000L

    private fun epochMillis(data: OrbitalData): Long =
        315_446_400_000L + (data.epochDaynum * 86_400_000.0).toLong()

    private fun tsinceMinutes(data: OrbitalData, time: Long): Double =
        (time - epochMillis(data)) / 60_000.0

    /** Straight-line separation in the TEME frame: the honest 3D number. */
    private fun separationKm(a: OrbitalObject, b: OrbitalObject, aData: OrbitalData, bData: OrbitalData, time: Long): Double {
        val sa = a.temeStateKm(tsinceMinutes(aData, time))
        val sb = b.temeStateKm(tsinceMinutes(bData, time))
        val dx = sa[0] - sb[0]
        val dy = sa[1] - sb[1]
        val dz = sa[2] - sb[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Sub-satellite point distance, which is what "along track error" usually means. */
    private fun groundKm(a: OrbitalObject, b: OrbitalObject, time: Long): Double {
        // OrbitalPos keeps sub-satellite latitude and longitude in radians already.
        val pa = a.getFullPosition(station, time)
        val latA = pa.latitude
        val lonA = pa.longitude
        val pb = b.getFullPosition(station, time)
        val latB = pb.latitude
        val lonB = pb.longitude
        val dLat = latB - latA
        val dLon = lonB - lonA
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(latA) * cos(latB) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6371.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun format(value: Double, decimals: Int = 1) =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    @Test
    fun stalenessCost() {
        val staleObject = stale.getObject()
        val freshObject = fresh.getObject()
        println("ACCURACY | stale epoch 2026-302.56, fresh epoch 2026-310.89, gap 8.32 d")
        println("AGE(d) | 3D(km) | ground(km) | range(km) | el(deg) | az(deg)")
        for (age in longArrayOf(0, 1, 3, 7, 14, 21)) {
            val time = epochMillis(fresh) + age * day
            val ageStale = (time - epochMillis(stale)) / day.toDouble()
            val a = staleObject.getFullPosition(station, time)
            val rangeA = a.distance
            val elA = a.elevation * RAD2DEG
            val azA = a.azimuth * RAD2DEG
            val b = freshObject.getFullPosition(station, time)
            val rangeB = b.distance
            val elB = b.elevation * RAD2DEG
            val azB = b.azimuth * RAD2DEG
            println(
                "STALE ${format(ageStale)} | ${format(separationKm(staleObject, freshObject, stale, fresh, time))} | " +
                    "${format(groundKm(staleObject, freshObject, time))} | " +
                    "${format(rangeA - rangeB)} | ${format(elA - elB, 2)} | ${format(azA - azB, 2)}"
            )
        }
    }

    @Test
    fun passTimeShift() {
        val staleObject = stale.getObject()
        val freshObject = fresh.getObject()
        // Walk forward from the fresh epoch and pair consecutive passes of both sets.
        for (age in longArrayOf(0, 3, 7, 14)) {
            val from = epochMillis(fresh) + age * day
            val passStale = findPass(staleObject, from)
            val passFresh = findPass(freshObject, from)
            if (passStale == null || passFresh == null) continue
            val deltaAos = (passStale.first - passFresh.first) / 1000.0
            val deltaLos = (passStale.second - passFresh.second) / 1000.0
            // Once the sets drift apart the two "next passes" can be different orbits entirely;
            // reporting that as a timing error would be nonsense.
            if (abs(deltaAos) > 900.0) {
                println("PASS age=${format((from - epochMillis(stale)) / day.toDouble())} d skipped: different pass")
                continue
            }
            val maxStale = maxElevation(staleObject, passStale.first, passStale.second)
            val maxFresh = maxElevation(freshObject, passFresh.first, passFresh.second)
            println(
                "PASS age=${format((from - epochMillis(stale)) / day.toDouble())} d " +
                    "dAos=${format(deltaAos)} s dLos=${format(deltaLos)} s " +
                    "maxEl ${format(maxStale)} vs ${format(maxFresh)} deg"
            )
        }
    }

    @Test
    fun modelSpread() {
        // Same element set, two force models: how far apart do they drift?
        for (age in longArrayOf(0, 7, 14, 30)) {
            val sg4 = NearEarthObject(fresh)
            val numerical = NumericalOrbitalObject(fresh)
            val time = epochMillis(fresh) + age * day
            println(
                "MODEL age=${age} d " +
                    "3D=${format(separationKm(sg4, numerical, fresh, fresh, time))} km"
            )
        }
    }

    private fun findPass(sat: OrbitalObject, from: Long): Pair<Long, Long>? {
        var t = from
        // Skip the pass in progress so both objects start from the same state of the sky.
        if (sat.getElevation(station, t) > 0.0) {
            while (sat.getElevation(station, t) > 0.0) t += 30_000L
        }
        val limit = from + 2 * day
        while (t < limit) {
            if (sat.getElevation(station, t) > 0.0) {
                var aos = t - 30_000L
                while (sat.getElevation(station, aos) < 0.0) aos += 500L
                var los = t
                while (sat.getElevation(station, los) > 0.0) los += 500L
                return aos to los
            }
            t += 30_000L
        }
        return null
    }

    private fun maxElevation(sat: OrbitalObject, aos: Long, los: Long): Double {
        var max = 0.0
        var t = aos
        while (t <= los) {
            max = maxOf(max, sat.getElevation(station, t) * RAD2DEG)
            t += 5_000L
        }
        return max
    }

}
