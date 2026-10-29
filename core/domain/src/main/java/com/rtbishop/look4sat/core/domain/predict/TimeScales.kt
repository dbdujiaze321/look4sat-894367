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

/**
 * Time scale conversions used by the numerical propagator.
 *
 * Rules implemented here, in the order the propagator needs them:
 *  - TLE epochs are **UTC** (never TT). Everything the app stores is UTC millis.
 *  - TT = TAI + 32.184 s, TAI = UTC + (leap second count).
 *  - The dynamics integrate in TT; the Earth-rotation angle (GMST) is evaluated in **UT1**.
 *  - UT1 = UTC + DUT1. DUT1 is |DUT1| < 0.9 s; the value in use is [dut1Seconds], which can be
 *    refreshed from a bulletin but defaults to 0.0. The resulting ground-track error is at most
 *    ~0.4 km at the equator, which is 0.05 s of along-track time for a LEO — inside the budget,
 *    but it is an approximation and is documented as such instead of being hidden.
 *
 * The leap-second table is built in up to 2026. Leap seconds are announced with ~6 months of
 * notice; if a new one is inserted after this build, [leapSecondsValidUntilDaynum] tells the UI
 * that the table is stale, and propagation keeps using the last known offset rather than drifting
 * silently by one second.
 */
object TimeScales {

    /** TT - TAI, seconds. Exact by definition. */
    const val TT_MINUS_TAI = 32.184

    /**
     * Daynum (days since 1979-12-31, the app's internal epoch) up to which the table is known
     * complete: IERS had announced no further leap second for the whole of 2026 at build time.
     */
    val leapSecondsValidUntilDaynum: Double =
        julianDateUtc(2027, 1, 1, 0, 0, 0.0) - 2444238.5

    /**
     * Leap-second insertions as (year, month, day, TAI-UTC after the insertion).
     * Only the entries from 1999 on matter for live TLEs, but the earlier ones keep the table
     * usable for archived element sets.
     */
    private val leapTable: Array<IntArray> = arrayOf(
        intArrayOf(1972, 1, 1, 10),
        intArrayOf(1972, 7, 1, 11),
        intArrayOf(1973, 1, 1, 12),
        intArrayOf(1974, 1, 1, 13),
        intArrayOf(1975, 1, 1, 14),
        intArrayOf(1976, 1, 1, 15),
        intArrayOf(1977, 1, 1, 16),
        intArrayOf(1978, 1, 1, 17),
        intArrayOf(1979, 1, 1, 18),
        intArrayOf(1980, 1, 1, 19),
        intArrayOf(1981, 7, 1, 20),
        intArrayOf(1982, 7, 1, 21),
        intArrayOf(1983, 7, 1, 22),
        intArrayOf(1985, 7, 1, 23),
        intArrayOf(1988, 1, 1, 24),
        intArrayOf(1990, 1, 1, 25),
        intArrayOf(1991, 1, 1, 26),
        intArrayOf(1992, 7, 1, 27),
        intArrayOf(1993, 7, 1, 28),
        intArrayOf(1994, 7, 1, 29),
        intArrayOf(1996, 1, 1, 30),
        intArrayOf(1997, 7, 1, 31),
        intArrayOf(1999, 1, 1, 32),
        intArrayOf(2006, 1, 1, 33),
        intArrayOf(2009, 1, 1, 34),
        intArrayOf(2012, 7, 1, 35),
        intArrayOf(2015, 7, 1, 36),
        intArrayOf(2017, 1, 1, 37)
    )

    /** UTC Julian dates of the [leapTable] entries, precomputed once. */
    private val leapJd: DoubleArray = DoubleArray(leapTable.size) { i ->
        val (y, m, d) = Triple(leapTable[i][0], leapTable[i][1], leapTable[i][2])
        julianDateUtc(y, m, d, 0, 0, 0.0)
    }

    /** DUT1 = UT1 - UTC in seconds. Set to 0.0 when no bulletin value is available. */
    @Volatile
    var dut1Seconds: Double = 0.0

    /** TAI - UTC in seconds for the given UTC Julian date. */
    fun taiMinusUtc(jdUtc: Double): Double {
        var offset = 10.0
        for (i in leapJd.indices) {
            if (jdUtc >= leapJd[i]) offset = leapTable[i][3].toDouble() else break
        }
        return offset
    }

    /** TT - UTC in seconds for the given UTC Julian date. */
    fun ttMinusUtc(jdUtc: Double): Double = taiMinusUtc(jdUtc) + TT_MINUS_TAI

    /** UTC Julian date -> TT Julian date. */
    fun utcToTt(jdUtc: Double): Double = jdUtc + ttMinusUtc(jdUtc) / SEC_PER_DAY

    /** UTC Julian date -> UT1 Julian date. */
    fun utcToUt1(jdUtc: Double): Double = jdUtc + dut1Seconds / SEC_PER_DAY

    /** Unix epoch milliseconds -> astronomical Julian date (UTC). */
    fun julianDateUtc(millis: Long): Double = 2440587.5 + millis / 86_400_000.0

    /** App daynum (days since 1979-12-31 00:00 UTC) -> Julian date. */
    fun daynumToJulian(daynum: Double): Double = daynum + 2444238.5

    /** Julian date -> app daynum. */
    fun julianToDaynum(jd: Double): Double = jd - 2444238.5

    /**
     * Julian date of a calendar UTC date/time. [second] may be fractional. Valid for 1583..9999.
     */
    fun julianDateUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Double): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = y / 100
        val b = 2 - a + a / 4
        val dayFraction = (hour + minute / 60.0 + second / 3600.0) / 24.0
        return kotlin.math.floor(365.25 * (y + 4716)).toInt() +
            kotlin.math.floor(30.6001 * (m + 1)).toInt() + day + b - 1524.5 + dayFraction
    }

    /** True when the built-in leap-second table has passed its validity date. */
    fun isLeapTableStale(jdUtc: Double): Boolean =
        julianToDaynum(jdUtc) > leapSecondsValidUntilDaynum
}
