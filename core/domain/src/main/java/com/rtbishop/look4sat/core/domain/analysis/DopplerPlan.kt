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

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.SPEED_OF_LIGHT
import kotlin.math.roundToLong

/** One instant of a tuning plan: what the radio hears, and what it has to transmit. */
data class DopplerPoint(
    val time: Long,
    val downlinkHz: Long?,
    val uplinkHz: Long?
)

/**
 * Turns geometry into a tuning plan.
 *
 * The two corrections have opposite signs, and getting that wrong is a classic mistake: while the
 * satellite approaches, the received downlink reads high, and the transmitter has to aim low so that
 * the signal arrives on frequency. Both fall out of the same range rate, so they share one code path
 * instead of two that can drift apart.
 */
object DopplerPlan {

    /** Received frequency when the satellite transmits [nominalHz] at [rangeRateKmS]. */
    fun correctedDownlinkHz(nominalHz: Long, rangeRateKmS: Double): Long =
        (nominalHz.toDouble() * (SPEED_OF_LIGHT - rangeRateKmS * 1000.0) / SPEED_OF_LIGHT).roundToLong()

    /** Transmit frequency that arrives at the satellite on [nominalHz] at [rangeRateKmS]. */
    fun correctedUplinkHz(nominalHz: Long, rangeRateKmS: Double): Long =
        (nominalHz.toDouble() * (SPEED_OF_LIGHT + rangeRateKmS * 1000.0) / SPEED_OF_LIGHT).roundToLong()

    /**
     * Maps an observed downlink through an inverting passband to the uplink that produced it.
     *
     * Scaled rather than the common `upLow + upHigh - rx` shortcut, because that shortcut silently
     * assumes the two passbands are the same width. Most linear transponders invert with unequal
     * widths (a 435 MHz uplink band and a 2 m downlink band rarely match), and on those the shortcut
     * is tens of kilohertz off at the band edges. With equal widths this reduces to it exactly.
     *
     * Returns null when the transponder is not a usable inverting pair.
     */
    fun invertedUplinkHz(
        downlinkLow: Long?,
        downlinkHigh: Long?,
        uplinkLow: Long?,
        uplinkHigh: Long?,
        observedDownlinkHz: Long
    ): Long? {
        val downLow = downlinkLow ?: return null
        val downHigh = downlinkHigh ?: return null
        val upLow = uplinkLow ?: return null
        val upHigh = uplinkHigh ?: return null
        val downWidth = (downHigh - downLow).toDouble()
        val upWidth = (upHigh - upLow).toDouble()
        if (downWidth <= 0.0 || upWidth <= 0.0) return null
        val scale = upWidth / downWidth
        return (upHigh - (observedDownlinkHz - downLow) * scale).roundToLong()
    }

    /** Nominal (un-Doppler-shifted) uplink for a given nominal downlink on this transponder. */
    fun nominalUplinkHz(radio: SatRadio, downlinkHz: Long): Long? {
        if (!radio.isInverted) return radio.uplinkLow ?: radio.uplinkHigh
        return invertedUplinkHz(
            radio.downlinkLow, radio.downlinkHigh, radio.uplinkLow, radio.uplinkHigh, downlinkHz
        )
    }

    /**
     * Builds the curve for one transceiver over a sampled pass.
     *
     * The nominal frequency is the low edge of the band, or the high edge when only that is published.
     * Using an edge rather than the centre is intentional: edges are what the source publishes, and
     * inventing a centre would imply a precision the data does not have.
     */
    fun forTransponder(
        radio: SatRadio,
        samples: List<PassSample>,
        invertUplink: Boolean = radio.isInverted
    ): List<DopplerPoint> {
        val downlinkNominal = radio.downlinkLow ?: radio.downlinkHigh
        val uplinkNominal = if (invertUplink && downlinkNominal != null) {
            nominalUplinkHz(radio.copy(isInverted = true), downlinkNominal)
        } else {
            radio.uplinkLow ?: radio.uplinkHigh
        }
        if (downlinkNominal == null && uplinkNominal == null) return emptyList()
        return samples.map { sample ->
            DopplerPoint(
                time = sample.time,
                downlinkHz = downlinkNominal?.let { correctedDownlinkHz(it, sample.rangeRateKmS) },
                uplinkHz = uplinkNominal?.let { correctedUplinkHz(it, sample.rangeRateKmS) }
            )
        }
    }
}
