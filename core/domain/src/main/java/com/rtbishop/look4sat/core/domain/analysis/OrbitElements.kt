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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/** Coarse orbit family. Used for labelling only: every prediction still runs through SGP4/SDP4. */
enum class OrbitClass {
    LEO,
    MEO,
    GEO,
    HEO
}

/**
 * How stale an element set is.
 *
 * SGP4 position error grows roughly with time since epoch, and the growth rate depends on the
 * object: a low, light satellite in a variable atmosphere degrades far faster than a geostationary
 * one. The thresholds here are the usual operational ones, not a measured error for this station.
 */
enum class ElementFreshness {
    FRESH,
    GOOD,
    USABLE,
    STALE;

    companion object {
        fun of(ageDays: Double): ElementFreshness = when {
            ageDays <= 1.0 -> FRESH
            ageDays <= 3.0 -> GOOD
            ageDays <= 7.0 -> USABLE
            else -> STALE
        }
    }
}

/**
 * Derived orbital geometry for one element set.
 *
 * Everything here is a closed-form function of the TLE fields, which is why it is testable without
 * a propagator: the semi-major axis comes from the mean motion, and the two secular drift rates come
 * from the J2 term alone. Those drift rates matter in practice — a sun-synchronous satellite's node
 * walks about a degree a day, so a week-old element set puts the ground track in a different place
 * than the same TLE would have predicted a week earlier.
 */
data class OrbitElements(
    val semiMajorAxisKm: Double,
    val perigeeAltitudeKm: Double,
    val apogeeAltitudeKm: Double,
    val periodMinutes: Double,
    val revolutionsPerDay: Double,
    val eccentricity: Double,
    val inclinationDeg: Double,
    val raanDeg: Double,
    val argPerigeeDeg: Double,
    val meanAnomalyDeg: Double,
    val orbitalClass: OrbitClass,
    /** Degrees per day: negative means the node drifts west, as it does for a prograde orbit. */
    val nodalRegressionDegPerDay: Double,
    /** Degrees per day: argument of perigee walk, the effect that makes Molniya orbits usable. */
    val apsidalRotationDegPerDay: Double,
    val elementAgeDays: Double,
    val freshness: ElementFreshness
) {
    /** Perigee is below the sensible atmosphere, so the element set describes a doomed object. */
    val isDecaying: Boolean get() = perigeeAltitudeKm < 200.0

    companion object {
        private const val MU = 398600.4418 // km^3 / s^2
        private const val EARTH_RADIUS_KM = 6378.137
        private const val J2 = 1.08262668e-3
        private const val SECONDS_PER_DAY = 86400.0

        fun from(data: OrbitalData, timeMillis: Long): OrbitElements {
            val periodSeconds = SECONDS_PER_DAY / data.meanmo.coerceAtLeast(1e-9)
            val periodMinutes = periodSeconds / 60.0
            val semiMajor = (MU * (periodSeconds / (2.0 * PI)).pow(2.0)).pow(1.0 / 3.0)
            val perigee = semiMajor * (1.0 - data.eccn) - EARTH_RADIUS_KM
            val apogee = semiMajor * (1.0 + data.eccn) - EARTH_RADIUS_KM
            val orbitClass = classify(periodMinutes, data.eccn, perigee)

            // Secular J2 rates. n is in rad/s, p is the semi-latus rectum in km.
            val meanMotionRad = 2.0 * PI / periodSeconds
            val p = semiMajor * (1.0 - data.eccn * data.eccn)
            val j2Factor = 1.5 * meanMotionRad * J2 * (EARTH_RADIUS_KM / p).pow(2.0)
            val cosI = cos(data.incl * PI / 180.0)
            val toDegPerDay = 180.0 / PI * SECONDS_PER_DAY
            val nodeRate = -j2Factor * cosI * toDegPerDay
            val apsisRate = 0.5 * j2Factor * (5.0 * cosI * cosI - 1.0) * toDegPerDay

            val ageDays = OrbitalData.timeToDaynum(timeMillis) - data.epochDaynum
            return OrbitElements(
                semiMajorAxisKm = semiMajor,
                perigeeAltitudeKm = perigee,
                apogeeAltitudeKm = apogee,
                periodMinutes = periodMinutes,
                revolutionsPerDay = data.meanmo,
                eccentricity = data.eccn,
                inclinationDeg = data.incl,
                raanDeg = data.raan,
                argPerigeeDeg = data.argper,
                meanAnomalyDeg = data.meanan,
                orbitalClass = orbitClass,
                nodalRegressionDegPerDay = nodeRate,
                apsidalRotationDegPerDay = apsisRate,
                elementAgeDays = ageDays,
                freshness = ElementFreshness.of(ageDays)
            )
        }

        /**
         * Classification by geometry, not by a name table.
         *
         * The order matters: a Molniya orbit has a perigee down in LEO, so an altitude-only test
         * would label it LEO. High eccentricity therefore wins first, then the near-24 h band.
         */
        private fun classify(periodMinutes: Double, eccentricity: Double, perigeeKm: Double): OrbitClass {
            if (eccentricity >= 0.25) return OrbitClass.HEO
            if (abs(periodMinutes - 1436.0) <= 60.0) return OrbitClass.GEO
            if (perigeeKm < 2000.0) return OrbitClass.LEO
            return OrbitClass.MEO
        }
    }
}
