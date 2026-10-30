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
package com.rtbishop.look4sat.core.presentation

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.rtbishop.look4sat.core.domain.model.ThemeColor
import com.rtbishop.look4sat.core.domain.model.ThemeOverrides
import com.rtbishop.look4sat.core.domain.model.ThemePreset

/**
 * Colours used by the radar canvas only.
 *
 * Kept out of [ColorScheme] because the radar needs more roles than Material exposes (grid,
 * track trail, satellite body) and because the three presets disagree about them independently of
 * the surrounding UI. Every field is a theme token: nothing in the drawing code is a literal.
 */
data class RadarPalette(
    /** Polar grid: rings and the cross hair. */
    val grid: Color,
    /** Bearing labels and tick marks, quieter than [grid]. */
    val labels: Color,
    /** Solid part of the pass track. */
    val track: Color,
    /** Dashed overlay that animates along the track. */
    val trackTrail: Color,
    /** Satellite icon fill. */
    val satellite: Color,
    /** Satellite icon outline; also the halo colour. */
    val satelliteOutline: Color,
    /** Text under the satellite (catalogue number). */
    val satelliteLabel: Color,
    /** The aim marker when the compass is driven by the sensors. */
    val aim: Color,
    /** Radar sweep fan. */
    val sweep: Color,
    /** Sun and moon glyphs. */
    val sun: Color,
    val moon: Color
)

private val defaultDarkRadar = RadarPalette(
    grid = Color(0xFFE0E0E0),
    labels = Color(0xB3E0E0E0),
    track = Color(0xFFDC0000),
    trackTrail = Color(0xFFFFE082),
    satellite = Color(0xFFFFE082),
    satelliteOutline = Color(0xFFFFE082),
    satelliteLabel = Color(0xFFE0E0E0),
    aim = Color(0xFFDC0000),
    sweep = Color(0xFFFFE082),
    sun = Color(0xFFFFE082),
    moon = Color(0xFFE0E0E0)
)

private val defaultLightRadar = RadarPalette(
    grid = Color(0xFF4C4639),
    labels = Color(0xFF7D7667),
    track = Color(0xFFDC0000),
    trackTrail = Color(0xFF715C0C),
    satellite = Color(0xFF715C0C),
    satelliteOutline = Color(0xFF715C0C),
    satelliteLabel = Color(0xFF1E1B13),
    aim = Color(0xFFDC0000),
    sweep = Color(0xFF715C0C),
    sun = Color(0xFF715C0C),
    moon = Color(0xFF4C4639)
)

private val highContrastRadar = RadarPalette(
    grid = Color(0xFFFFFFFF),
    labels = Color(0xFFFFFFFF),
    track = Color(0xFFFF3B30),
    trackTrail = Color(0xFFFFFFFF),
    satellite = Color(0xFFFFFF00),
    satelliteOutline = Color(0xFFFFFFFF),
    satelliteLabel = Color(0xFFFFFFFF),
    aim = Color(0xFFFF3B30),
    sweep = Color(0xFFFFFF00),
    sun = Color(0xFFFFFF00),
    moon = Color(0xFFFFFFFF)
)

private val redNightRadar = RadarPalette(
    grid = Color(0xFF8B0000),
    labels = Color(0xFFB30000),
    track = Color(0xFFFF2A2A),
    trackTrail = Color(0xFF7A0000),
    satellite = Color(0xFFFF3B30),
    satelliteOutline = Color(0xFFFF6B60),
    satelliteLabel = Color(0xFFC1121F),
    aim = Color(0xFFFF0000),
    sweep = Color(0xFF8B0000),
    sun = Color(0xFFFF4444),
    moon = Color(0xFFB30000)
)

/** Theme tokens available to any composable that draws the radar. */
val LocalRadarPalette = staticCompositionLocalOf { defaultDarkRadar }

/** Default scheme, kept here so previews and the app share one definition. */
fun baseColorScheme(isDarkTheme: Boolean): ColorScheme = if (isDarkTheme) baseDarkScheme else baseLightScheme

/** Hex to Compose colour, tolerant of malformed input coming from an imported theme file. */
private fun String?.toColorOrNull(): Color? = ThemeColor.parse(this)?.let { Color(it) }

/** Resolves the Material scheme for a preset, then layers any user overrides on top. */
fun colorSchemeFor(
    preset: ThemePreset,
    isDarkTheme: Boolean,
    overrides: ThemeOverrides = ThemeOverrides.EMPTY
): ColorScheme {
    val base = when (preset) {
        ThemePreset.DEFAULT -> baseColorScheme(isDarkTheme)
        ThemePreset.HIGH_CONTRAST -> if (isDarkTheme) highContrastDarkScheme else highContrastLightScheme
        ThemePreset.RED_NIGHT -> redNightScheme
    }
    if (overrides.isEmpty()) return base
    return base.copy(
        primary = overrides.primary.toColorOrNull() ?: base.primary,
        secondary = overrides.secondary.toColorOrNull() ?: base.secondary,
        background = overrides.background.toColorOrNull() ?: base.background,
        surface = overrides.surface.toColorOrNull() ?: base.surface
    )
}

/** Resolves the radar tokens for a preset, then layers any user overrides on top. */
fun radarPaletteFor(
    preset: ThemePreset,
    isDarkTheme: Boolean,
    overrides: ThemeOverrides = ThemeOverrides.EMPTY
): RadarPalette {
    val base = when (preset) {
        ThemePreset.DEFAULT -> if (isDarkTheme) defaultDarkRadar else defaultLightRadar
        ThemePreset.HIGH_CONTRAST -> highContrastRadar
        ThemePreset.RED_NIGHT -> redNightRadar
    }
    if (overrides.isEmpty()) return base
    return base.copy(
        grid = overrides.radarGrid.toColorOrNull() ?: base.grid,
        track = overrides.radarTrack.toColorOrNull() ?: base.track,
        trackTrail = overrides.radarTrack.toColorOrNull() ?: base.trackTrail,
        satellite = overrides.radarSatellite.toColorOrNull() ?: base.satellite,
        satelliteOutline = overrides.radarSatellite.toColorOrNull() ?: base.satelliteOutline
    )
}

private val highContrastDarkScheme = darkColorScheme(
    primary = Color(0xFFFFFF00),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF64B5F6),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFFFFB000),
    onTertiaryContainer = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0A0A0A),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFFFFFFF),
    surfaceTint = Color(0x00000000),
    error = Color(0xFFFF3B30),
    outline = Color(0xFFFFFFFF),
    scrim = Color(0xFF000000),
    surfaceContainer = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainerHighest = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color(0xFF000000)
)

private val highContrastLightScheme = lightColorScheme(
    primary = Color(0xFF0038A8),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF1A1A1A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E6E6),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFF0038A8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF8A5A00),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFF000000),
    surfaceTint = Color(0x00000000),
    error = Color(0xFFB00020),
    outline = Color(0xFF000000),
    scrim = Color(0xFF000000)
)

private val redNightScheme = darkColorScheme(
    primary = Color(0xFFFF3B30),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFFC1121F),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2A0000),
    onSecondaryContainer = Color(0xFFFF6B60),
    tertiary = Color(0xFFFF6B60),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF5A0000),
    onTertiaryContainer = Color(0xFFFFB3AD),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFF3B30),
    surface = Color(0xFF0A0000),
    onSurface = Color(0xFFFF6B60),
    surfaceVariant = Color(0xFF1A0000),
    onSurfaceVariant = Color(0xFFC1121F),
    surfaceTint = Color(0x00000000),
    error = Color(0xFFFF3B30),
    outline = Color(0xFF7A0000),
    scrim = Color(0xFF000000),
    surfaceContainer = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0000),
    surfaceContainerHighest = Color(0xFF1A0000),
    surfaceContainerLowest = Color(0xFF000000)
)
