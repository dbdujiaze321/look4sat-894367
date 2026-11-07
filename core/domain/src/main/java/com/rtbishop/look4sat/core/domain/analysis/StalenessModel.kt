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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One measured prediction error: how far a pass time computed from an element set of [ageDays]
 * actually was, in seconds, compared with the next element set that arrived for the same object.
 */
@Serializable
data class ErrorSample(val ageDays: Double, val seconds: Double)

/**
 * How wrong a prediction is, as a function of how old its element set is.
 *
 * The shape is a power law, which is what both the data and the physics say: the along-track error of
 * a two-body fit grows steadily and then accelerates as the ignored drag term accumulates, so the
 * error roughly follows age^3.5 over the first month.
 *
 * Two coefficients are *trained*, not assumed. `secondsAtOneDay` and `exponent` start from a seed
 * measured against real element sets (see docs/STALENESS.md) and are refitted every time the app
 * replaces an element set with a newer one: each such update is one labelled sample, because the old
 * set's prediction can be compared with the new set at the new epoch. That is the whole training
 * loop — no external data, no manual labels, and it gets sharper the longer the app is used.
 *
 * The point of the model is not cosmetic. The app promises sub-10-second pass times; this is what
 * lets it keep that promise by knowing when to stop trusting its own data, instead of trusting a
 * fixed "refresh every 48 hours" rule that says nothing about the actual error.
 */
@Serializable
data class StalenessCalibration(
    val exponent: Double = SEED_EXPONENT,
    val secondsAtOneDay: Double = SEED_SECONDS_AT_ONE_DAY,
    val samples: List<ErrorSample> = emptyList()
) {

    val sampleCount: Int get() = samples.size

    /** Expected absolute error of a pass time predicted from elements of [ageDays] days. */
    fun predictedSeconds(ageDays: Double): Double {
        if (ageDays <= 0.0) return 0.0
        return secondsAtOneDay * ageDays.pow(exponent)
    }

    /** True when predictions from elements this old are outside the accuracy the app promises. */
    fun exceedsBudget(ageDays: Double): Boolean = predictedSeconds(ageDays) > BUDGET_SECONDS

    /**
     * Adds one measured sample and refits.
     *
     * The reservoir keeps the most recent [MAX_SAMPLES] points: the model describes *current* solar
     * and drag conditions, and a two-year-old sample says more about that year's atmosphere than
     * about today's.
     */
    fun withSample(sample: ErrorSample): StalenessCalibration {
        if (!sample.isUsable()) return this
        val kept = (samples + sample).takeLast(MAX_SAMPLES)
        return refit(kept)
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /**
         * Seed from thirteen real measurements on 2026-11-07: two ISS element sets 8.32 days apart,
         * compared at ages from 8.3 to 37.6 days (docs/STALENESS.md). Fitted residual is 6% mean,
         * 13% worst case — small enough that the budget it drives is meaningful.
         */
        const val SEED_EXPONENT = 2.726
        const val SEED_SECONDS_AT_ONE_DAY = 0.0208

        /** The accuracy the app promises for a pass time. */
        const val BUDGET_SECONDS = 10.0

        const val MAX_SAMPLES = 512

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        val EMPTY = StalenessCalibration()

        fun decode(raw: String): StalenessCalibration {
            if (raw.isBlank()) return EMPTY
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(EMPTY)
        }

        /**
         * Least squares in log-log space: ln(seconds) = ln(k) + p * ln(age).
         *
         * A power law is linear there, so the fit is a closed-form two-parameter regression rather
         * than an iterative one — it cannot fail to converge, which matters because this runs on the
         * main data path after every update.
         */
        fun refit(samples: List<ErrorSample>): StalenessCalibration {
            val usable = samples.filter { it.isUsable() }
            if (usable.size < MIN_SAMPLES_FOR_REFIT) {
                return StalenessCalibration(samples = usable)
            }
            val xs = usable.map { ln(it.ageDays) }
            val ys = usable.map { ln(it.seconds) }
            val meanX = xs.average()
            val meanY = ys.average()
            var covariance = 0.0
            var variance = 0.0
            for (index in xs.indices) {
                val dx = xs[index] - meanX
                covariance += dx * (ys[index] - meanY)
                variance += dx * dx
            }
            if (variance <= 1e-9) return StalenessCalibration(samples = usable)
            val slope = (covariance / variance).coerceIn(MIN_EXPONENT, MAX_EXPONENT)
            val intercept = meanY - slope * meanX
            return StalenessCalibration(
                exponent = slope,
                secondsAtOneDay = exp(intercept),
                samples = usable
            )
        }
    }
}

/** Below this an element set cannot have drifted, and a sample would describe noise, not staleness. */
private const val MIN_AGE_DAYS = 0.05
private const val MIN_EXPONENT = 2.0
private const val MAX_EXPONENT = 5.0
private const val MIN_SAMPLES_FOR_REFIT = 8

private fun ErrorSample.isUsable(): Boolean =
    ageDays >= MIN_AGE_DAYS && seconds > 0.0 && seconds.isFinite() && ageDays.isFinite()

object StalenessModel {

    /**
     * Measures the error of [stale] against [reference] at the moment the reference was fitted.
     *
     * Both sets are propagated to the reference epoch and the separation is projected on the
     * reference velocity: that projection is exactly the along-track error, and dividing it by the
     * orbital speed turns it into the timing error an operator would notice.
     *
     * Returns null instead of a wrong sample whenever the inputs cannot be compared — different
     * objects, a reference that is not actually newer, or a result so large that the element set was
     * simply broken.
     */
    fun measure(stale: OrbitalData, reference: OrbitalData): ErrorSample? {
        if (stale.catnum != reference.catnum) return null
        val ageDays = reference.epochDaynum - stale.epochDaynum
        if (ageDays < MIN_AGE_DAYS) return null
        val staleObject = stale.getObject()
        val referenceObject = reference.getObject()
        val referenceTime = EPOCH_MILLIS_BASE + (reference.epochDaynum * 86_400_000.0).toLong()
        val staleState = staleObject.temeStateKm(
            (referenceTime - (EPOCH_MILLIS_BASE + (stale.epochDaynum * 86_400_000.0).toLong())) / 60_000.0
        )
        val referenceState = referenceObject.temeStateKm(0.0)
        val dx = staleState[0] - referenceState[0]
        val dy = staleState[1] - referenceState[1]
        val dz = staleState[2] - referenceState[2]
        val speed = sqrt(
            referenceState[3] * referenceState[3] +
                referenceState[4] * referenceState[4] +
                referenceState[5] * referenceState[5]
        )
        val alongKm = (dx * referenceState[3] + dy * referenceState[4] + dz * referenceState[5]) / speed
        val separationKm = sqrt(dx * dx + dy * dy + dz * dz)
        val seconds = abs(alongKm) / speed
        // One place decides whether the numbers describe a real measurement or a broken element set.
        val usable = speed > MIN_SPEED_KM_S &&
            separationKm.isFinite() && separationKm <= MAX_SEPARATION_KM &&
            seconds.isFinite() && seconds <= MAX_ERROR_SECONDS
        return if (usable) ErrorSample(ageDays = ageDays, seconds = seconds) else null
    }

    private const val EPOCH_MILLIS_BASE = 315_446_400_000L
    private const val MIN_SPEED_KM_S = 1e-6
    private const val MAX_SEPARATION_KM = 100_000.0
    private const val MAX_ERROR_SECONDS = 36_000.0
}
