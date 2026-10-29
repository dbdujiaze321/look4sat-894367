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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Right-hand side of dy/dt = f(t, y). Writes six components into [out]. */
fun interface Derivative {
    fun evaluate(t: Double, y: DoubleArray, out: DoubleArray)
}

/**
 * Dormand-Prince 5(4) embedded Runge-Kutta integrator, adaptive step, FSAL reuse.
 * State layout: `[x, y, z, vx, vy, vz]` in km and km/s.
 *
 * Tableau (Dormand & Prince 1980, the pair used by MATLAB's ode45):
 * ```
 * 0      |
 * 1/5    | 1/5
 * 3/10   | 3/40          9/40
 * 4/5    | 44/45        -56/15        32/9
 * 8/9    | 19372/6561   -25360/2187   64448/6561   -212/729
 * 1      | 9017/3168    -355/33       46732/5247    49/176     -5103/18656
 * 1      | 35/384        0            500/1113     125/192    -2187/6784    11/84
 * ------ | ---------------------------------------------------------------------
 * b5     | 35/384        0            500/1113     125/192    -2187/6784    11/84   0
 * b4     | 5179/57600    0            7571/16695   393/640    -92097/339200 187/2100 1/40
 * ```
 *
 * Instances are not thread safe (one per satellite); all scratch arrays are pre-allocated so a
 * multi-day arc performs no heap allocation.
 */
class DormandPrince(
    var relativeTolerance: Double = 1.0e-10,
    var absoluteTolerance: Double = 1.0e-9,
    private val minStep: Double = 0.5,
    /** Upper bound on a single step, seconds. Callers tighten it near perigee. */
    var maxStep: Double = 120.0
) {

    private val k1 = DoubleArray(6)
    private val k2 = DoubleArray(6)
    private val k3 = DoubleArray(6)
    private val k4 = DoubleArray(6)
    private val k5 = DoubleArray(6)
    private val k6 = DoubleArray(6)
    private val k7 = DoubleArray(6)
    private val stage = DoubleArray(6)
    private val yHigh = DoubleArray(6)

    var evaluationCount: Long = 0
        private set
    var acceptedSteps: Long = 0
        private set
    var rejectedSteps: Long = 0
        private set

    fun resetCounters() {
        evaluationCount = 0
        acceptedSteps = 0
        rejectedSteps = 0
    }

    /**
     * Integrate [y] from [t0] to [t1] in place. [t1] may be smaller than [t0].
     * Returns the number of accepted steps (0 when the span is negligible).
     */
    fun integrate(y: DoubleArray, t0: Double, t1: Double, derivative: Derivative): Int {
        val span = t1 - t0
        if (abs(span) < 1.0e-9) return 0
        val direction = if (span > 0) 1.0 else -1.0
        var t = t0
        var h = direction * min(maxStep, abs(span))
        var accepted = 0
        var needFirstEvaluation = true

        while ((t1 - t) * direction > 1.0e-9) {
            if ((t + h - t1) * direction > 0.0) h = t1 - t
            val hAbs = abs(h)
            if (hAbs < minStep) break

            if (needFirstEvaluation) {
                evaluate(t, y, k1, derivative)
                needFirstEvaluation = false
            }

            // Stage 2
            for (i in 0 until 6) stage[i] = y[i] + h * A21 * k1[i]
            evaluate(t + C2 * h, stage, k2, derivative)
            // Stage 3
            for (i in 0 until 6) stage[i] = y[i] + h * (A31 * k1[i] + A32 * k2[i])
            evaluate(t + C3 * h, stage, k3, derivative)
            // Stage 4
            for (i in 0 until 6) stage[i] = y[i] + h * (A41 * k1[i] + A42 * k2[i] + A43 * k3[i])
            evaluate(t + C4 * h, stage, k4, derivative)
            // Stage 5
            for (i in 0 until 6) {
                stage[i] = y[i] + h * (A51 * k1[i] + A52 * k2[i] + A53 * k3[i] + A54 * k4[i])
            }
            evaluate(t + C5 * h, stage, k5, derivative)
            // Stage 6
            for (i in 0 until 6) {
                stage[i] = y[i] + h * (A61 * k1[i] + A62 * k2[i] + A63 * k3[i] + A64 * k4[i] + A65 * k5[i])
            }
            evaluate(t + C6 * h, stage, k6, derivative)
            // 5th-order solution (also stage 7's abscissa)
            for (i in 0 until 6) {
                yHigh[i] = y[i] + h * (B1 * k1[i] + B3 * k3[i] + B4 * k4[i] + B5 * k5[i] + B6 * k6[i])
            }
            evaluate(t + h, yHigh, k7, derivative)

            var sum = 0.0
            for (i in 0 until 6) {
                val y4 = y[i] + h * (
                    D1 * k1[i] + D3 * k3[i] + D4 * k4[i] + D5 * k5[i] + D6 * k6[i] + D7 * k7[i]
                    )
                val scale = absoluteTolerance + relativeTolerance * max(abs(y[i]), abs(yHigh[i]))
                val e = (yHigh[i] - y4) / scale
                sum += e * e
            }
            val error = sqrt(sum / 6.0)

            if (error.isNaN()) {
                h *= 0.25
                rejectedSteps++
                continue
            }

            if (error <= 1.0) {
                for (i in 0 until 6) y[i] = yHigh[i]
                t += h
                accepted++
                acceptedSteps++
                // FSAL: k7 is f(t_new, y_new), reuse as the next k1.
                System.arraycopy(k7, 0, k1, 0, 6)
                val factor = if (error <= 1.0e-12) 5.0 else 0.9 * error.pow(-0.2)
                h *= factor.coerceIn(0.2, 5.0)
                if (abs(h) > maxStep) h = direction * maxStep
            } else {
                rejectedSteps++
                h *= (0.9 * error.pow(-0.2)).coerceIn(0.1, 0.5)
            }
        }
        return accepted
    }

    private fun evaluate(t: Double, y: DoubleArray, out: DoubleArray, derivative: Derivative) {
        derivative.evaluate(t, y, out)
        evaluationCount++
    }

    private companion object {
        const val C2 = 1.0 / 5.0
        const val C3 = 3.0 / 10.0
        const val C4 = 4.0 / 5.0
        const val C5 = 8.0 / 9.0
        const val C6 = 1.0

        const val A21 = 1.0 / 5.0

        const val A31 = 3.0 / 40.0
        const val A32 = 9.0 / 40.0

        const val A41 = 44.0 / 45.0
        const val A42 = -56.0 / 15.0
        const val A43 = 32.0 / 9.0

        const val A51 = 19372.0 / 6561.0
        const val A52 = -25360.0 / 2187.0
        const val A53 = 64448.0 / 6561.0
        const val A54 = -212.0 / 729.0

        const val A61 = 9017.0 / 3168.0
        const val A62 = -355.0 / 33.0
        const val A63 = 46732.0 / 5247.0
        const val A64 = 49.0 / 176.0
        const val A65 = -5103.0 / 18656.0

        // 5th order weights (also the 7th stage weights).
        const val B1 = 35.0 / 384.0
        const val B3 = 500.0 / 1113.0
        const val B4 = 125.0 / 192.0
        const val B5 = -2187.0 / 6784.0
        const val B6 = 11.0 / 84.0

        // 4th order weights, used only for the error estimate.
        const val D1 = 5179.0 / 57600.0
        const val D3 = 7571.0 / 16695.0
        const val D4 = 393.0 / 640.0
        const val D5 = -92097.0 / 339200.0
        const val D6 = 187.0 / 2100.0
        const val D7 = 1.0 / 40.0
    }
}
