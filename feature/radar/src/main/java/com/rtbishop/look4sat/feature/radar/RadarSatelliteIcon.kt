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
package com.rtbishop.look4sat.feature.radar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.rtbishop.look4sat.core.domain.model.RadarIconStyle
import com.rtbishop.look4sat.core.domain.model.SatelliteCategory
import com.rtbishop.look4sat.core.presentation.RadarPalette
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Draws the satellite marker on the radar.
 *
 * Every style is a pure function of the arguments: no state, no allocation of shaders, so the
 * canvas can be redrawn at animation frame rate. The contour is a category-aware set of primitives
 * rather than a bitmap, which keeps the APK small and every screen density crisp.
 */
internal fun DrawScope.drawSatelliteIcon(
    position: Offset,
    iconSizePx: Float,
    rotationDeg: Float,
    category: SatelliteCategory,
    style: RadarIconStyle,
    palette: RadarPalette,
    haloRadiusPx: Float,
    isEclipsed: Boolean,
    customIcon: ImageBitmap? = null
) {
    if (style == RadarIconStyle.DOT) {
        drawCircle(palette.satellite, 16f, position)
        if (!isEclipsed) drawCircle(palette.satelliteOutline.copy(alpha = 1f - haloRadiusPx / 64f), haloRadiusPx, position)
        return
    }

    // The halo marks the satellite as lit; an eclipsed satellite gets none, same as upstream.
    if (!isEclipsed) {
        drawCircle(palette.satellite.copy(alpha = 0.18f), iconSizePx * 0.9f, position)
    }

    if (style == RadarIconStyle.CUSTOM && customIcon != null) {
        // Drawn at 1.4x the vector size: a bitmap glyph reads smaller than a stroked contour at the
        // same bounding box, because the contour carries empty space between its lines.
        val side = (iconSizePx * 1.4f).roundToInt().coerceAtLeast(8)
        rotate(degrees = rotationDeg, pivot = position) {
            drawImage(
                image = customIcon,
                dstOffset = IntOffset(
                    (position.x - side / 2f).roundToInt(),
                    (position.y - side / 2f).roundToInt()
                ),
                dstSize = IntSize(side, side),
                filterQuality = FilterQuality.Medium
            )
        }
        return
    }

    val half = iconSizePx / 2f
    rotate(degrees = rotationDeg, pivot = position) {
        translate(position.x, position.y) {
            val parts = satelliteParts(category, half)
            val strokeWidth = (iconSizePx * 0.13f).coerceAtLeast(2.5f)
            parts.forEach { part ->
                if (style == RadarIconStyle.GLYPH) {
                    drawPath(part, palette.satellite)
                } else {
                    drawPath(part, palette.satelliteOutline, style = Stroke(width = strokeWidth))
                }
            }
        }
    }
}

/**
 * Category silhouettes, expressed in a local space centred on the origin with an extent of
 * roughly [-half, half]. Outlines are stroked and glyphs are filled from the same geometry, so the
 * two styles can never drift apart.
 */
private fun satelliteParts(category: SatelliteCategory, half: Float): List<Path> = when (category) {
    SatelliteCategory.SPACE_STATION -> listOf(
        // One merged silhouette. Drawing the parts as separate stroked rectangles made the icon
        // read as a blob on a 1080p phone: the strokes of the overlapping parts crossed each other.
        union(
            rectPath(-0.14f * half, -0.55f * half, 0.14f * half, 0.55f * half),
            rectPath(-0.42f * half, -0.09f * half, -0.14f * half, 0.09f * half),
            rectPath(0.14f * half, -0.09f * half, 0.42f * half, 0.09f * half),
            rectPath(-0.95f * half, -0.32f * half, -0.42f * half, 0.32f * half),
            rectPath(0.42f * half, -0.32f * half, 0.95f * half, 0.32f * half)
        )
    )

    SatelliteCategory.AMATEUR -> listOf(
        union(
            rectPath(-0.45f * half, -0.16f * half, 0.45f * half, 0.42f * half),
            rectPath(-0.07f * half, -0.62f * half, 0.07f * half, -0.16f * half),
            circlePath(0f, -0.72f * half, 0.22f * half)
        )
    )

    SatelliteCategory.WEATHER -> listOf(
        union(
            circlePath(0f, 0f, 0.78f * half),
            rectPath(-0.78f * half, -0.10f * half, 0.78f * half, 0.10f * half)
        ),
        circlePath(0f, 0f, 0.24f * half)
    )

    SatelliteCategory.NAVIGATION -> listOf(
        union(
            diamondPath(half * 0.86f),
            rectPath(-0.09f * half, -0.55f * half, 0.09f * half, 0.55f * half)
        )
    )

    SatelliteCategory.SCIENCE -> listOf(
        union(
            hexagonPath(half * 0.82f),
            rectPath(-0.86f * half, -0.08f * half, 0.86f * half, 0.08f * half)
        )
    )

    SatelliteCategory.COMMUNICATION -> listOf(
        union(
            rectPath(-0.30f * half, -0.44f * half, 0.30f * half, 0.44f * half),
            rectPath(-0.44f * half, -0.08f * half, -0.30f * half, 0.08f * half),
            rectPath(0.30f * half, -0.08f * half, 0.44f * half, 0.08f * half),
            rectPath(-0.95f * half, -0.30f * half, -0.44f * half, 0.30f * half),
            rectPath(0.44f * half, -0.30f * half, 0.95f * half, 0.30f * half)
        )
    )

    SatelliteCategory.OTHER -> listOf(
        rectPath(-0.62f * half, -0.62f * half, 0.62f * half, 0.62f * half)
    )
}

/**
 * Merges overlapping primitives into a single contour.
 *
 * Without this, stroking a body rectangle and a panel rectangle that overlap draws both outlines
 * through each other, which on a 26 dp marker looks like a blob rather than a satellite.
 */
private fun union(vararg parts: Path): Path {
    var accumulator = parts.first()
    for (index in 1 until parts.size) {
        val merged = Path()
        merged.op(accumulator, parts[index], PathOperation.Union)
        accumulator = merged
    }
    return accumulator
}

private fun rectPath(left: Float, top: Float, right: Float, bottom: Float): Path = Path().apply {
    addRect(Rect(left, top, right, bottom))
}

private fun circlePath(cx: Float, cy: Float, r: Float): Path = Path().apply {
    addOval(Rect(Offset(cx - r, cy - r), Size(r * 2f, r * 2f)))
}

private fun diamondPath(r: Float): Path = Path().apply {
    moveTo(0f, -r)
    lineTo(r, 0f)
    lineTo(0f, r)
    lineTo(-r, 0f)
    close()
}

private fun hexagonPath(r: Float): Path = Path().apply {
    moveTo(0f, -r)
    lineTo(r * 0.87f, -r * 0.5f)
    lineTo(r * 0.87f, r * 0.5f)
    lineTo(0f, r)
    lineTo(-r * 0.87f, r * 0.5f)
    lineTo(-r * 0.87f, -r * 0.5f)
    close()
}

/**
 * Angle, in degrees, at which the icon should be drawn so it points along the ground track.
 *
 * The track samples are one per second, so the nearest neighbour of the current position is a good
 * enough tangent. Falls back to 0 (icon pointing up) when the track has a single sample, which
 * happens for a pass that has just started.
 */
internal fun motionAngleDegrees(
    item: com.rtbishop.look4sat.core.domain.predict.OrbitalPos,
    track: List<com.rtbishop.look4sat.core.domain.predict.OrbitalPos>,
    positionOf: (com.rtbishop.look4sat.core.domain.predict.OrbitalPos) -> Offset
): Float {
    if (track.size < 2) return 0f
    val nearest = track.indices.minByOrNull { kotlin.math.abs(track[it].time - item.time) } ?: return 0f
    val neighbour = when {
        nearest + 1 <= track.lastIndex -> nearest + 1
        nearest - 1 >= 0 -> nearest - 1
        else -> return 0f
    }
    val current = positionOf(track[nearest])
    val next = positionOf(track[neighbour])
    val dx = next.x - current.x
    val dy = next.y - current.y
    if (dx == 0f && dy == 0f) return 0f
    // The icon art points up (-Y), hence the +90 offset before rounding.
    val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
    return ((degrees / 15f).roundToInt() * 15f) % 360f
}
