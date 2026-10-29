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
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Numerical orbit propagator: Dormand-Prince 5(4) integration of a J2..J8 + drag + luni-solar
 * force model, seeded from the analytic SGP4/SDP4 state at the TLE epoch.
 *
 * Why seed from SGP4: a TLE carries *mean* elements fitted by SGP4's own force model, so there is
 * no direct way to obtain osculating elements from it. SGP4's output at t = 0 is the best available
 * osculating estimate; from there the integrator takes over and the analytic truncations (SGP4's
 * short-period series, SDP4's deep-space simplifications) no longer constrain the trajectory.
 *
 * Drag: SGP4 reduces drag to a single B* coefficient. This propagator needs a ballistic coefficient
 * (Cd*A/m). It is first estimated from the TLE's mean-motion derivative with a US76 density profile,
 * then refined so that the 1-day along-track drift matches SGP4's own decay. That keeps the
 * near-epoch behaviour consistent with the element set while letting the integrator handle
 * short-period and third-body terms exactly.
 *
 * All returned vectors are TEME, in km and km/s. Nothing here allocates per call except the
 * caller's output array.
 */
class NumericalPropagator(
    private val data: OrbitalData,
    private val reference: OrbitalObject,
    private val config: Config = Config()
) {

    data class Config(
        val useDrag: Boolean = true,
        val useThirdBody: Boolean = true,
        val calibrateDrag: Boolean = true,
        val relativeTolerance: Double = 1.0e-10,
        val absoluteTolerance: Double = 1.0e-9,
        val zonalDegree: Int = 8
    )

    /** UTC Julian date of the element set epoch. */
    val jdUtcEpoch: Double = TimeScales.daynumToJulian(OrbitalData.epochToDaynum(data.epoch))

    /** Orbital period, seconds (from the TLE mean motion). */
    val periodSeconds: Double = SEC_PER_DAY / data.meanmo

    private val forceModel = ForceModel(
        useDrag = config.useDrag,
        useThirdBody = config.useThirdBody,
        zonalDegree = config.zonalDegree
    )

    private val integrator = DormandPrince(
        relativeTolerance = config.relativeTolerance,
        absoluteTolerance = config.absoluteTolerance,
        maxStep = (periodSeconds / 60.0).coerceIn(20.0, 120.0)
    )

    /** Osculating state at the epoch: [x, y, z, vx, vy, vz] in km, km/s, TEME. */
    private val epochState: DoubleArray = reference.temeStateKm(0.0)

    private val workState = DoubleArray(6)

    private val derivative = Derivative { t, y, out ->
        val acc = forceModel.acceleration(t, jdUtcEpoch, y)
        out[0] = y[3]
        out[1] = y[4]
        out[2] = y[5]
        out[3] = acc[0]
        out[4] = acc[1]
        out[5] = acc[2]
    }

    // --- Sequential state cache -----------------------------------------------------------------
    // Pass prediction walks time forward in 30-60 s increments, so keeping the last few states
    // turns almost every call into a single integration step.
    private val cacheTime = DoubleArray(CACHE_SIZE) { Double.NaN }
    private val cacheState = Array(CACHE_SIZE) { DoubleArray(6) }
    private var cacheCursor = 0

    @Volatile
    var isDragCalibrated: Boolean = false
        private set

    /** Ballistic coefficient actually in use, m^2/kg. */
    var ballisticCoefficient: Double = 0.0
        private set

    var lastEvaluationCount: Long = 0
        private set

    /** Steps taken by the last propagation call, used by the performance report. */
    var lastStepCount: Long = 0
        private set

    init {
        if (config.useDrag) {
            ballisticCoefficient = estimateBallisticCoefficient()
            forceModel.ballisticCoefficient = ballisticCoefficient
        } else {
            isDragCalibrated = true
        }
    }

    /**
     * Propagate to [tsinceMinutes] after the epoch. Writes TEME [x, y, z, vx, vy, vz] (km, km/s)
     * into [out].
     */
    fun propagateMinutes(tsinceMinutes: Double, out: DoubleArray) = propagateSeconds(tsinceMinutes * 60.0, out)

    /** Propagate to [tSeconds] after the epoch (negative values go backwards). */
    fun propagateSeconds(tSeconds: Double, out: DoubleArray) {
        if (config.useDrag && !isDragCalibrated && abs(tSeconds) > 3600.0) refineDrag()

        var bestIndex = -1
        var bestTime = 0.0
        for (i in cacheTime.indices) {
            val cached = cacheTime[i]
            if (cached.isNaN()) continue
            if (cached <= tSeconds && (bestIndex < 0 || cached > bestTime)) {
                bestIndex = i
                bestTime = cached
            }
        }

        if (bestIndex >= 0) {
            System.arraycopy(cacheState[bestIndex], 0, workState, 0, 6)
        } else {
            System.arraycopy(epochState, 0, workState, 0, 6)
            bestTime = 0.0
        }

        val span = tSeconds - bestTime
        if (abs(span) > 1.0e-9) {
            integrator.maxStep = stepLimitFor(workState)
            integrator.resetCounters()
            integrator.integrate(workState, bestTime, tSeconds, derivative)
            lastEvaluationCount = integrator.evaluationCount
            lastStepCount = integrator.acceptedSteps
        }

        System.arraycopy(workState, 0, cacheState[cacheCursor], 0, 6)
        cacheTime[cacheCursor] = tSeconds
        cacheCursor = (cacheCursor + 1) % CACHE_SIZE

        System.arraycopy(workState, 0, out, 0, 6)
    }

    /** Temperature/altitude aware step limit: tighter near perigee, looser when high up. */
    private fun stepLimitFor(state: DoubleArray): Double {
        val r = sqrt(state[0] * state[0] + state[1] * state[1] + state[2] * state[2])
        val altitude = r - EARTH_RADIUS
        return when {
            altitude < 400.0 -> 40.0
            altitude < 1000.0 -> 60.0
            altitude < 3000.0 -> 120.0
            altitude < 10000.0 -> 240.0
            else -> 480.0
        }
    }

    // --- drag calibration -----------------------------------------------------------------------

    /**
     * First-guess Cd*A/m from the TLE mean-motion derivative.
     *
     * Circular-orbit energy balance: `da/dt = -B * rho * v * a`, with the TLE's `ndot` field being
     * dn/dt / 2 in rev/day^2, so `dn/dt = 2 * ndot * 2pi / 86400^2` and `da/dt = -(2/3)(a/n) dn/dt`.
     * Density is sampled at perigee and apogee and combined geometrically, which is much closer to
     * the orbit-averaged value than a single point sample at the mean radius.
     */
    private fun estimateBallisticCoefficient(): Double {
        val n0 = data.meanmo * TWO_PI / SEC_PER_DAY // rad/s
        if (n0 <= 0.0) return 0.0
        val aKm = (ForceModel.MU_EARTH / (n0 * n0)).pow(1.0 / 3.0)
        val aM = aKm * 1000.0
        val speed = sqrt(ForceModel.MU_EARTH / aKm) * 1000.0 // m/s
        val dnDt = 2.0 * data.ndot * TWO_PI / (SEC_PER_DAY * SEC_PER_DAY) // rad/s^2
        val daDt = -(2.0 / 3.0) * (aM / n0) * dnDt // m/s
        if (daDt == 0.0 || speed == 0.0) return 0.0

        val perigeeAlt = aKm * (1.0 - data.eccn) - EARTH_RADIUS
        val apogeeAlt = aKm * (1.0 + data.eccn) - EARTH_RADIUS
        val rhoPerigee = Atmosphere.density(perigeeAlt)
        val rhoApogee = Atmosphere.density(apogeeAlt)
        val rho = sqrt(rhoPerigee * rhoApogee)
        // Above ~2000 km the atmosphere is irrelevant; a vanishing density must not turn the
        // ballistic coefficient into a meaningless huge number.
        if (!rho.isFinite() || rho < 1.0e-19) return 0.0

        val b = -daDt / (rho * speed * aM)
        // Guard against nonsense from corrupt ndot fields.
        if (!b.isFinite() || b <= 0.0) return fallbackBallisticFromBstar()
        return b.coerceIn(1.0e-6, 0.5)
    }

    /** Last resort when the TLE's ndot is zero: scale B* into a rough Cd*A/m. */
    private fun fallbackBallisticFromBstar(): Double {
        val b = data.bstar * 2.0 / 0.1570
        return if (!b.isFinite() || b <= 0.0) 0.0 else b.coerceIn(1.0e-6, 0.5)
    }

    /**
     * Refine the drag coefficient so the 1-day along-track drift matches SGP4's. SGP4 remains the
     * only self-consistent decay reference available from a TLE; anything else would need a real
     * density model plus space-weather input, which this app deliberately does not ship.
     */
    private fun refineDrag() {
        val probe = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        // Calibrate across the middle of the standard window (two days) instead of a single day:
        // the along-track residual grows with t^2, so matching the curvature matters more than
        // matching the first day exactly.
        val spanSeconds = min(periodSeconds * 30.0, 2.0 * SEC_PER_DAY)
        val sgp4End = reference.temeStateKm(spanSeconds / 60.0)

        fun separationFor(b: Double): Double {
            forceModel.ballisticCoefficient = b
            System.arraycopy(epochState, 0, probe, 0, 6)
            integrator.maxStep = stepLimitFor(probe)
            integrator.integrate(probe, 0.0, spanSeconds, derivative)
            return alongTrackSeparationKm(probe, sgp4End)
        }

        val b0 = ballisticCoefficient
        if (b0 <= 0.0) {
            isDragCalibrated = true
            return
        }
        var bestB = b0
        var bestAbs = abs(separationFor(b0))

        // Secant iteration on the along-track separation; the residual is quadratic in time, so a
        // 0.2 km mismatch here becomes ~10 km after a week. Keep the best sample rather than the
        // last one, because the secant can step outside the physically sane range.
        var bPrev = b0
        var fPrev = separationFor(b0)
        var b = b0 * 1.5
        for (iteration in 0 until 5) {
            val f = separationFor(b)
            if (abs(f) < bestAbs) {
                bestAbs = abs(f)
                bestB = b
            }
            if (abs(f) < 20.0) break
            val denominator = f - fPrev
            val next = if (abs(denominator) < 1.0e-9) b * 1.2 else b - f * (b - bPrev) / denominator
            if (!next.isFinite() || next <= 0.0) break
            bPrev = b
            fPrev = f
            b = next.coerceIn(1.0e-6, 0.5)
        }
        ballisticCoefficient = bestB
        forceModel.ballisticCoefficient = bestB
        isDragCalibrated = true
    }

    /**
     * Along-track separation between two TEME states, kilometres. Positive means [b] is ahead of
     * [a] in the direction of motion.
     */
    private fun alongTrackSeparationKm(a: DoubleArray, b: DoubleArray): Double {
        val ra = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val rb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (ra <= 0.0 || rb <= 0.0) return 0.0
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (ra * rb)
        val angle = acos(dot.coerceIn(-1.0, 1.0))
        // Sign from the angular momentum of the reference state.
        val hx = a[1] * a[5] - a[2] * a[4]
        val hy = a[2] * a[3] - a[0] * a[5]
        val hz = a[0] * a[4] - a[1] * a[3]
        val cross = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
        val sign = if (cross[0] * hx + cross[1] * hy + cross[2] * hz >= 0.0) 1.0 else -1.0
        return sign * angle * ((ra + rb) * 0.5)
    }

    /** Convert a TEME state to geodetic latitude/longitude/altitude at [tSeconds] after epoch. */
    fun geodeticAt(tSeconds: Double, out: DoubleArray) {
        val state = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        propagateSeconds(tSeconds, state)
        val jdUtc = jdUtcEpoch + tSeconds / SEC_PER_DAY
        val geo = eciToGeodetic(jdUtc, doubleArrayOf(state[0], state[1], state[2]))
        out[0] = geo[0]
        out[1] = geo[1]
        out[2] = geo[2]
    }

    private companion object {
        const val CACHE_SIZE = 32
    }
}
