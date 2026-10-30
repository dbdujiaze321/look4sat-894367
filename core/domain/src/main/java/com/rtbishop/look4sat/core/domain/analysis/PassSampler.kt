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

import com.rtbishop.look4sat.core.domain.predict.DEG2RAD
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.SPEED_OF_LIGHT
import kotlin.math.abs
import kotlin.math.roundToLong

/** One instant of a pass, already resolved into everything an operator tunes against. */
data class PassSample(
    val time: Long,
    val azimuthDeg: Double,
    val elevationDeg: Double,
    val rangeKm: Double,
    val rangeRateKmS: Double,
    val altitudeKm: Double,
    val sunlit: Boolean
) {
    /** Received frequency offset if the other end transmits exactly [nominalHz]. */
    fun dopplerShiftHz(nominalHz: Long): Long =
        (nominalHz.toDouble() * -rangeRateKmS * 1000.0 / SPEED_OF_LIGHT).roundToLong()
}

/**
 * The numbers an operator actually decides with, derived from the sampled pass.
 *
 * Kept separate from [com.rtbishop.look4sat.core.domain.predict.OrbitalPass] because the pass list is
 * recomputed on every selection or filter change and only ever needs AOS, LOS and the peak elevation.
 * Sampling a curve for four hundred passes would be wasteful; sampling one pass on demand is cheap.
 */
data class PassMetrics(
    val aosTime: Long,
    val losTime: Long,
    val tcaTime: Long,
    val durationSeconds: Long,
    val maxElevationDeg: Double,
    val azimuthAtTcaDeg: Double,
    val minRangeKm: Double,
    val maxRangeKm: Double,
    /** Largest absolute range rate over the pass, i.e. the worst-case Doppler on the horizon. */
    val peakRangeRateKmS: Double,
    /** Signed azimuth travelled between AOS and LOS. Negative when the pass runs anticlockwise. */
    val azimuthSweepDeg: Double,
    /** Fraction of the pass during which the satellite is in sunlight, weighted by sample spacing. */
    val sunlitFraction: Double
) {
    /** Peak Doppler excursion for a given nominal frequency, always positive. */
    fun peakDopplerHz(nominalHz: Long): Long =
        (nominalHz.toDouble() * peakRangeRateKmS * 1000.0 / SPEED_OF_LIGHT).roundToLong()
}

object PassSampler {

    private const val DEFAULT_SAMPLE_COUNT = 121

    /**
     * Samples a pass between AOS and LOS.
     *
     * The upper bound on the count is deliberate: the chart is at most a few hundred pixels wide, and
     * every sample costs a full SGP4 evaluation including the eclipse geometry. 121 points (every
     * ~5 s on a ten minute pass) is smooth at screen resolution.
     */
    fun sample(
        obj: OrbitalObject,
        station: GeoPos,
        aosTime: Long,
        losTime: Long,
        count: Int = DEFAULT_SAMPLE_COUNT
    ): List<PassSample> {
        if (losTime <= aosTime || count < 2) return emptyList()
        val step = (losTime - aosTime).toDouble() / (count - 1)
        val samples = ArrayList<PassSample>(count)
        for (index in 0 until count) {
            val time = aosTime + (step * index).roundToLong()
            samples.add(snapshot(obj, station, time))
        }
        return samples
    }

    /** Single instant, copied out of the propagator's reused buffer. */
    fun snapshot(obj: OrbitalObject, station: GeoPos, time: Long): PassSample {
        val pos = obj.getFullPosition(station, time)
        return PassSample(
            time = time,
            azimuthDeg = pos.azimuth / DEG2RAD,
            elevationDeg = pos.elevation / DEG2RAD,
            rangeKm = pos.distance,
            rangeRateKmS = pos.distanceRate,
            altitudeKm = pos.altitude,
            sunlit = !pos.eclipsed
        )
    }

    /**
     * Time of closest approach, refined by ternary search on range.
     *
     * A sampled minimum is only accurate to one sample step, which on a ten minute pass is five
     * seconds — enough to matter when the point of the screen is telling an operator when to stop
     * turning the antenna. Range over a single pass is unimodal, so the ternary search converges
     * without any derivative.
     */
    fun refineClosestApproach(
        obj: OrbitalObject,
        station: GeoPos,
        aosTime: Long,
        losTime: Long,
        iterations: Int = 48
    ): Long {
        if (losTime <= aosTime) return aosTime
        var low = aosTime
        var high = losTime
        repeat(iterations) {
            if (high - low <= 200L) return low
            val third = (high - low) / 3
            val left = low + third
            val right = high - third
            val leftRange = obj.getFullPosition(station, left).distance
            val rightRange = obj.getFullPosition(station, right).distance
            if (leftRange < rightRange) high = right else low = left
        }
        return (low + high) / 2
    }

    /**
     * Derives the operator-facing metrics from a sampled pass.
     *
     * [samples] must be evenly spaced and cover the same window as [aosTime]..[losTime]; the sunlit
     * fraction is an unweighted average for exactly that reason.
     */
    fun metrics(
        obj: OrbitalObject,
        station: GeoPos,
        aosTime: Long,
        losTime: Long,
        samples: List<PassSample>
    ): PassMetrics {
        require(samples.isNotEmpty()) { "metrics() needs at least one sample" }
        val tca = refineClosestApproach(obj, station, aosTime, losTime)
        val atTca = obj.getFullPosition(station, tca)
        val peakRate = samples.maxOf { abs(it.rangeRateKmS) }
        return PassMetrics(
            aosTime = aosTime,
            losTime = losTime,
            tcaTime = tca,
            durationSeconds = (losTime - aosTime) / 1000,
            maxElevationDeg = atTca.elevation / DEG2RAD,
            azimuthAtTcaDeg = atTca.azimuth / DEG2RAD,
            minRangeKm = atTca.distance,
            maxRangeKm = samples.maxOf { it.rangeKm },
            peakRangeRateKmS = peakRate,
            azimuthSweepDeg = azimuthSweep(samples),
            sunlitFraction = samples.count { it.sunlit }.toDouble() / samples.size
        )
    }

    /**
     * Total azimuth travelled, taking the short way round at the 0/360 seam.
     *
     * Summing raw differences would report a pass that crosses north as a 350 degree swing in the
     * wrong direction, which is exactly the case worth noticing (it means the satellite went over
     * the pole side of the sky).
     */
    private fun azimuthSweep(samples: List<PassSample>): Double {
        var total = 0.0
        for (index in 1 until samples.size) {
            var delta = samples[index].azimuthDeg - samples[index - 1].azimuthDeg
            while (delta > 180.0) delta -= 360.0
            while (delta < -180.0) delta += 360.0
            total += delta
        }
        return total
    }
}
