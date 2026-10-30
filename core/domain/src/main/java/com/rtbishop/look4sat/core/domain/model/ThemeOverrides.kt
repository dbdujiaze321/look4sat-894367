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
package com.rtbishop.look4sat.core.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-token overrides on top of a [ThemePreset].
 *
 * Every field is optional: `null` means "inherit the preset", which is what makes a theme file
 * forward compatible — a file written by an older version simply does not mention the tokens added
 * later, and they keep coming from the preset instead of turning black.
 *
 * Colours are `#RRGGBB` or `#AARRGGBB` strings rather than ints so the exported file stays readable
 * and hand-editable, which is the whole point of exporting it.
 */
@Serializable
data class ThemeOverrides(
    val version: Int = 1,
    val primary: String? = null,
    val secondary: String? = null,
    val background: String? = null,
    val surface: String? = null,
    @SerialName("radar_grid") val radarGrid: String? = null,
    @SerialName("radar_track") val radarTrack: String? = null,
    @SerialName("radar_satellite") val radarSatellite: String? = null,
    @SerialName("corner_radius_dp") val cornerRadiusDp: Int? = null
) {
    fun isEmpty(): Boolean = this == EMPTY

    /** Clamped corner radius in dp, or null to keep the preset shape. */
    fun cornerRadius(): Int? = cornerRadiusDp?.coerceIn(MIN_RADIUS_DP, MAX_RADIUS_DP)

    companion object {
        const val MIN_RADIUS_DP = 0
        const val MAX_RADIUS_DP = 28

        val EMPTY = ThemeOverrides()

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            prettyPrint = true
        }

        /** Never throws: a corrupt or foreign file degrades to "no overrides", never to a crash. */
        fun parse(raw: String?): ThemeOverrides {
            if (raw.isNullOrBlank()) return EMPTY
            return try {
                json.decodeFromString(serializer(), raw)
            } catch (_: Exception) {
                EMPTY
            }
        }

        fun encode(theme: ThemeOverrides): String = json.encodeToString(serializer(), theme)
    }
}

/**
 * Hex colour parsing kept out of the UI layer so it can be unit tested without Android.
 * Accepts `#RRGGBB`, `RRGGBB`, `#AARRGGBB`, `AARRGGBB`; returns ARGB in an Int, or null.
 */
object ThemeColor {

    fun parse(value: String?): Int? {
        val text = value?.trim()?.removePrefix("#") ?: return null
        if (text.length != 6 && text.length != 8) return null
        val digits = text.toLongOrNull(radix = 16) ?: return null
        return if (text.length == 6) {
            (0xFF000000L or digits).toInt()
        } else {
            digits.toInt()
        }
    }

    fun format(argb: Int): String = "#%08X".format(argb)
}
