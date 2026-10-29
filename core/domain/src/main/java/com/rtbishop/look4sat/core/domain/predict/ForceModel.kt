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
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Force model for the numerical propagator. Everything here is plain Kotlin and is evaluated in
 * the TEME frame (the same frame SGP4 publishes, so the SGP4 state at epoch can be used directly
 * as the initial value).
 *
 * Included:
 *  - point-mass gravity + zonal harmonics J2..J8 (Earth's oblateness, the dominant secular terms),
 *  - atmospheric drag with an exponential US76 density profile and a calibrated ballistic
 *    coefficient (see [NumericalPropagator.calibrateDrag]),
 *  - luni-solar third-body perturbation with the Sun taken from the existing
 *    [solarPositionECI] and the Moon from a truncated Meeus series.
 *
 * Deliberately NOT included (documented in the precision report rather than hidden):
 *  - tesseral/sectorial harmonics (C22/S22, ...), which are short-period and average out over a
 *    day for the orbits this app tracks,
 *  - solid Earth and ocean tides, relativity, radiation pressure,
 *  - a real thermospheric density model driven by F10.7/Ap (only a uniform scale factor is
 *    exposed via [densityScale]). This is the dominant residual error term at multi-day horizons
 *    and no amount of integrator tuning removes it.
 */
class ForceModel(
    private val useDrag: Boolean = true,
    private val useThirdBody: Boolean = true,
    /** Cd*A/m in m^2/kg. */
    var ballisticCoefficient: Double = 0.0,
    /** Multiplier applied to the density (space-weather proxy). 1.0 = US76 nominal. */
    var densityScale: Double = 1.0,
    private val zonalDegree: Int = 8
) {

    /** Result buffer for [acceleration] to keep the integrator allocation-free. */
    private val acc = DoubleArray(3)

    /** Cached third-body positions, refreshed when the query time moves past [bodyCacheTolerance]. */
    private var cachedTimeSeconds = Double.NaN
    private var sunX = 0.0
    private var sunY = 0.0
    private var sunZ = 0.0
    private var moonX = 0.0
    private var moonY = 0.0
    private var moonZ = 0.0

    fun acceleration(tSinceEpochSeconds: Double, jdUtcEpoch: Double, state: DoubleArray): DoubleArray {
        val x = state[0]
        val y = state[1]
        val z = state[2]
        val vx = state[3]
        val vy = state[4]
        val vz = state[5]

        zonalAcceleration(x, y, z, acc)
        var ax = acc[0]
        var ay = acc[1]
        var az = acc[2]

        if (useThirdBody) {
            updateBodies(tSinceEpochSeconds, jdUtcEpoch)
            ax += thirdBodyComponent(MU_SUN, sunX, sunY, sunZ, x, y, z, 0)
            ay += thirdBodyComponent(MU_SUN, sunX, sunY, sunZ, x, y, z, 1)
            az += thirdBodyComponent(MU_SUN, sunX, sunY, sunZ, x, y, z, 2)
            ax += thirdBodyComponent(MU_MOON, moonX, moonY, moonZ, x, y, z, 0)
            ay += thirdBodyComponent(MU_MOON, moonX, moonY, moonZ, x, y, z, 1)
            az += thirdBodyComponent(MU_MOON, moonX, moonY, moonZ, x, y, z, 2)
        }

        if (useDrag && ballisticCoefficient > 0.0) {
            // Atmosphere co-rotates with the Earth: relative velocity needs the omega x r term.
            val rvx = vx + OMEGA_EARTH * y
            val rvy = vy - OMEGA_EARTH * x
            val rvz = vz
            val rv = sqrt(rvx * rvx + rvy * rvy + rvz * rvz)
            val altKm = sqrt(x * x + y * y + z * z) - EARTH_RADIUS
            val rho = Atmosphere.density(altKm) * densityScale
            if (rho > 0.0) {
                // a = -0.5 * (Cd*A/m) * rho * |v| * v, with B in m^2/kg and v in km/s:
                // a[km/s^2] = -0.5 * B * rho * (v_km * 1000)^2 / 1000 * v_hat
                //           = -0.5 * B * rho * |v_km| * v_vec_km * 1e3
                val k = -0.5 * ballisticCoefficient * 1.0e3 * rho * rv
                ax += k * rvx
                ay += k * rvy
                az += k * rvz
            }
        }
        return acc.also {
            it[0] = ax
            it[1] = ay
            it[2] = az
        }
    }

    /** Pure two-body + zonal harmonics acceleration (also used by the unit tests). */
    fun zonalAcceleration(x: Double, y: Double, z: Double, out: DoubleArray) {
        val r2 = x * x + y * y + z * z
        val r = sqrt(r2)
        val r3 = r2 * r
        var ax = -MU_EARTH * x / r3
        var ay = -MU_EARTH * y / r3
        var az = -MU_EARTH * z / r3

        val u = z / r
        val legendre = DoubleArray(zonalDegree + 1)
        val dLegendre = DoubleArray(zonalDegree + 1)
        legendre[0] = 1.0
        if (zonalDegree >= 1) {
            legendre[1] = u
            dLegendre[1] = 1.0
        }
        for (n in 2..zonalDegree) {
            legendre[n] = ((2.0 * n - 1.0) * u * legendre[n - 1] - (n - 1.0) * legendre[n - 2]) / n
            dLegendre[n] = dLegendre[n - 2] + (2.0 * n - 1.0) * legendre[n - 1]
        }

        val xy2 = x * x + y * y
        // (Re/r)^n, accumulated one factor per degree so the J2 term really is quadratic.
        var reOverR = EARTH_RADIUS / r
        for (n in 2..zonalDegree) {
            reOverR *= EARTH_RADIUS / r
            val jn = ZONAL[n]
            if (jn == 0.0) continue
            val pn = legendre[n]
            val dpn = dLegendre[n]
            val common = jn * MU_EARTH * reOverR / r3
            // dV/dx carries an extra minus sign (du/dx = -xz/r^3) that dV/dz does not, hence the
            // opposite signs of the x/y and z terms.
            ax += common * x * (z * dpn / r + (n + 1.0) * pn)
            ay += common * y * (z * dpn / r + (n + 1.0) * pn)
            az -= common * (dpn * xy2 / r - (n + 1.0) * z * pn)
        }
        out[0] = ax
        out[1] = ay
        out[2] = az
    }

    private fun thirdBodyComponent(
        mu: Double, bx: Double, by: Double, bz: Double,
        x: Double, y: Double, z: Double, component: Int
    ): Double {
        val ddx = bx - x
        val ddy = by - y
        val ddz = bz - z
        val d2 = ddx * ddx + ddy * ddy + ddz * ddz
        val d3 = d2 * sqrt(d2)
        val b2 = bx * bx + by * by + bz * bz
        val b3 = b2 * sqrt(b2)
        return when (component) {
            0 -> mu * (ddx / d3 - bx / b3)
            1 -> mu * (ddy / d3 - by / b3)
            else -> mu * (ddz / d3 - bz / b3)
        }
    }

    private fun updateBodies(tSinceEpochSeconds: Double, jdUtcEpoch: Double) {
        if (!cachedTimeSeconds.isNaN() && abs(cachedTimeSeconds - tSinceEpochSeconds) < BODY_CACHE_STEP) return
        cachedTimeSeconds = tSinceEpochSeconds
        val jdUtc = jdUtcEpoch + tSinceEpochSeconds / SEC_PER_DAY
        val sun = solarPositionECI(jdUtc)
        sunX = sun[0]
        sunY = sun[1]
        sunZ = sun[2]
        val moon = moonPositionECI(TimeScales.utcToTt(jdUtc))
        moonX = moon[0]
        moonY = moon[1]
        moonZ = moon[2]
    }

    companion object {
        const val MU_EARTH = 398600.4418
        const val MU_SUN = 132_712_440_017.99
        const val MU_MOON = 4902.800066
        const val OMEGA_EARTH = 7.2921150e-5

        /** Recompute Sun/Moon at most every 5 minutes; their direction changes slowly. */
        private const val BODY_CACHE_STEP = 300.0

        /** Unnormalised zonal coefficients (EGM96), index = degree. */
        val ZONAL = doubleArrayOf(
            0.0,
            0.0,
            1.08262668e-3,
            -2.53265649e-6,
            -1.61996117e-6,
            -2.27296e-7,
            5.4068e-7,
            -3.5236e-7,
            -2.0240e-7
        )

        /**
         * Truncated Meeus lunar theory (terms >= ~0.1 deg / 250 km). Good to ~0.02 deg in
         * direction, which perturbs the third-body acceleration far below the drag error.
         * Returns the geocentric equatorial position in km for a **TT** Julian date.
         */
        fun moonPositionECI(jdTt: Double): DoubleArray {
            val t = (jdTt - 2451545.0) / 36525.0
            val lPrime = 218.3164477 + 481267.88123421 * t
            val d = 297.8501921 + 445267.1114034 * t
            val m = 357.5291092 + 35999.0502909 * t
            val mPrime = 134.9633964 + 477198.8675055 * t
            val f = 93.2720950 + 483202.0175233 * t
            val dRad = Math.toRadians(d)
            val mRad = Math.toRadians(m)
            val mpRad = Math.toRadians(mPrime)
            val fRad = Math.toRadians(f)

            val lambdaDeg = lPrime +
                6.288774 * sin(mpRad) +
                1.274027 * sin(2 * dRad - mpRad) +
                0.658314 * sin(2 * dRad) +
                0.213618 * sin(2 * mpRad) -
                0.185116 * sin(mRad) -
                0.114332 * sin(2 * fRad) +
                0.058793 * sin(2 * dRad - 2 * mpRad) +
                0.057066 * sin(2 * dRad - mRad - mpRad) +
                0.053322 * sin(2 * dRad + mpRad) +
                0.045758 * sin(2 * dRad - mRad)

            val betaDeg =
                5.128122 * sin(fRad) +
                0.280602 * sin(mpRad + fRad) +
                0.277693 * sin(mpRad - fRad) +
                0.173237 * sin(2 * dRad - fRad) +
                0.055413 * sin(2 * dRad - mpRad + fRad) +
                0.046271 * sin(2 * dRad - mpRad - fRad) +
                0.032573 * sin(2 * dRad + fRad)

            val distanceKm =
                385000.56 -
                20905.355 * cos(mpRad) -
                3699.111 * cos(2 * dRad - mpRad) -
                2955.968 * cos(2 * dRad) -
                569.925 * cos(2 * mpRad) +
                246.158 * cos(2 * mpRad - 2 * dRad) -
                204.586 * cos(2 * dRad - mRad) -
                170.733 * cos(2 * dRad + mpRad) -
                152.138 * cos(2 * dRad - mRad - mpRad)

            val lambda = Math.toRadians(lambdaDeg)
            val beta = Math.toRadians(betaDeg)
            val eps = Math.toRadians(23.4392911 - 0.0130042 * t)

            val xEcl = distanceKm * cos(beta) * cos(lambda)
            val yEcl = distanceKm * cos(beta) * sin(lambda)
            val zEcl = distanceKm * sin(beta)

            val x = xEcl
            val y = yEcl * cos(eps) - zEcl * sin(eps)
            val z = yEcl * sin(eps) + zEcl * cos(eps)
            return doubleArrayOf(x, y, z)
        }
    }
}

/**
 * Exponential atmosphere used by the drag term. Log-linear interpolation over the US76 table;
 * above 1000 km the last scale height is extrapolated. This is a *nominal* quiet-time profile —
 * it carries no solar/geomagnetic input, which is precisely why multi-day drag error cannot be
 * removed by this propagator alone.
 */
object Atmosphere {

    private val altitudeKm = doubleArrayOf(
        0.0, 25.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 110.0, 120.0, 130.0,
        140.0, 150.0, 160.0, 170.0, 180.0, 190.0, 200.0, 250.0, 300.0, 350.0, 400.0, 450.0,
        500.0, 600.0, 700.0, 800.0, 900.0, 1000.0
    )

    private val densityKgM3 = doubleArrayOf(
        1.225, 3.899e-2, 1.774e-2, 3.972e-3, 1.057e-3, 3.206e-4, 8.770e-5, 1.905e-5, 3.396e-6,
        5.297e-7, 9.661e-8, 2.438e-8, 8.484e-9, 3.845e-9, 2.070e-9, 1.233e-9, 7.815e-10,
        5.194e-10, 3.581e-10, 2.541e-10, 6.073e-11, 1.916e-11, 7.014e-12, 2.803e-12, 1.184e-12,
        5.215e-13, 1.137e-13, 3.070e-14, 1.136e-14, 5.759e-15, 3.561e-15
    )

    private val logDensity = DoubleArray(densityKgM3.size) { ln(densityKgM3[it]) }

    fun density(altKm: Double): Double {
        if (altKm <= altitudeKm[0]) return densityKgM3[0]
        val top = altitudeKm.size - 1
        if (altKm >= altitudeKm[top]) {
            // Extrapolate with the 900-1000 km scale height.
            val h = altitudeKm[top] - altitudeKm[top - 1]
            val slope = (logDensity[top] - logDensity[top - 1]) / h
            return kotlin.math.exp(logDensity[top] + slope * (altKm - altitudeKm[top]))
        }
        var i = 1
        while (i < altitudeKm.size && altitudeKm[i] < altKm) i++
        val h0 = altitudeKm[i - 1]
        val h1 = altitudeKm[i]
        val l0 = logDensity[i - 1]
        val l1 = logDensity[i]
        val f = (altKm - h0) / (h1 - h0)
        return kotlin.math.exp(l0 + f * (l1 - l0))
    }
}
