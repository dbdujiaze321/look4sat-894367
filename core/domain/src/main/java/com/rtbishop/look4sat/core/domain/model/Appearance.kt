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

/**
 * Built-in appearance presets. The stored value is the enum name, so adding a preset later cannot
 * break an existing preference: unknown names fall back to [DEFAULT] on read.
 */
enum class ThemePreset {
    /** The upstream look: warm amber on charcoal, good for a dimmed shack in the evening. */
    DEFAULT,

    /** Daylight readable: near black on white, thicker outlines, no low contrast greys. */
    HIGH_CONTRAST,

    /** Red on black for dark adaptation in the field. Only the red channel is meaningful. */
    RED_NIGHT;

    companion object {
        fun fromName(name: String?): ThemePreset =
            ThemePreset.entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * How the radar draws a satellite.
 *
 * - [DOT] keeps the upstream behaviour: a filled circle with a halo.
 * - [OUTLINE] draws a category specific contour, so a weather satellite reads differently from a
 *   space station at a glance without needing a legend.
 * - [GLYPH] draws a solid silhouette, which stays readable on a washed out outdoor screen.
 * - [CUSTOM] draws a user supplied PNG; falls back to [OUTLINE] when no image is stored.
 */
enum class RadarIconStyle {
    DOT,
    OUTLINE,
    GLYPH,
    CUSTOM;

    companion object {
        fun fromName(name: String?): RadarIconStyle =
            RadarIconStyle.entries.firstOrNull { it.name == name } ?: OUTLINE
    }
}
