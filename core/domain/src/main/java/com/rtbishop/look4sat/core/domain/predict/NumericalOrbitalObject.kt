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
 * Global switch between the analytic SGP4/SDP4 kernel and the numerical RK45 kernel.
 *
 * Both kernels publish through the same [OrbitalObject] entry points, so flipping this flag is the
 * whole rollback story: no call site, no feature module and no UI code changes.
 */
object PropagationConfig {

    /** Numerical kernel on by default; set false to fall back to the legacy analytic path. */
    @Volatile
    var useNumericalPropagator: Boolean = true

    /** Skip drag calibration (used by tests that need a pure gravity comparison). */
    @Volatile
    var calibrateDrag: Boolean = true

    /** Uniform multiplier on atmospheric density, 1.0 = US76 nominal. */
    @Volatile
    var densityScale: Double = 1.0
}

/**
 * Drop-in replacement for [NearEarthObject]/[DeepSpaceObject] that keeps the analytic kernel as
 * the initial value and as the fallback, and returns the RK45 state through the inherited
 * [OrbitalObject] plumbing (azimuth/elevation/range-rate, sub-satellite point, eclipse, phase).
 */
class NumericalOrbitalObject(data: OrbitalData) : OrbitalObject(data) {

    private val reference: OrbitalObject =
        if (data.isDeepSpace) DeepSpaceObject(data) else NearEarthObject(data)

    private val propagator = NumericalPropagator(
        data = data,
        reference = reference,
        config = NumericalPropagator.Config(
            useDrag = true,
            useThirdBody = true,
            calibrateDrag = PropagationConfig.calibrateDrag
        )
    )

    private val state = DoubleArray(6)

    /** Exposed for the precision tests and the dashboard's accuracy window. */
    internal val numericalPropagator: NumericalPropagator get() = propagator

    override fun calculateSDP4orSGP4(tsince: Double) {
        propagator.propagateMinutes(tsince, state)
        applyTemeStateKm(state[0], state[1], state[2], state[3], state[4], state[5])
        applyPhaseFromState(state[0], state[1], state[2], state[3], state[4], state[5])
    }
}
