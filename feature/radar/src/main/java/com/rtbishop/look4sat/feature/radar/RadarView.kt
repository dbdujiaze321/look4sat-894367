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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StampedPathEffectStyle
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.model.RadarIconStyle
import com.rtbishop.look4sat.core.domain.model.SatelliteCategory
import com.rtbishop.look4sat.core.domain.predict.CelestialComputer
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.predict.PI_2
import com.rtbishop.look4sat.core.domain.utility.toRadians
import com.rtbishop.look4sat.core.presentation.LocalRadarPalette
import com.rtbishop.look4sat.core.presentation.R
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val CIRCLES = 3
private const val STROKE_WIDTH = 6f
private const val SWEEP_DURATION_MS = 8_000
private const val MIN_ELEVATION_SPAN = 15f
private const val MAX_ELEVATION_SPAN = 90f

/** How far the finger may be from the satellite marker and still count as hitting it. */
private const val HIT_SLOP_DP = 32f

@Composable
fun RadarViewCompose(
    item: OrbitalPos,
    items: List<OrbitalPos>,
    azimElev: Pair<Float, Float>,
    shouldShowSweep: Boolean,
    shouldUseCompass: Boolean,
    shouldFlipRadar: Boolean,
    modifier: Modifier = Modifier,
    sunPosition: CelestialComputer.SunPosition? = null,
    moonPosition: CelestialComputer.MoonPosition? = null,
    category: SatelliteCategory = SatelliteCategory.OTHER,
    iconStyle: RadarIconStyle = RadarIconStyle.OUTLINE,
    showLabel: Boolean = false,
    label: String = "",
    iconScale: Float = 1f,
    onLongPressSatellite: () -> Unit = {}
) {
    val palette = LocalRadarPalette.current
    val animTransition = rememberInfiniteTransition(label = "animScale")
    val animScale by animTransition.animateFloat(
        initialValue = 16f,
        targetValue = 64f,
        animationSpec = infiniteRepeatable(tween(1000)),
        label = "animScale"
    )
    // Drive the sweep from the animation framework to eliminate state mutation inside the draw block
    val sweepTransition = rememberInfiniteTransition(label = "sweep")
    val sweepDegrees by sweepTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SWEEP_DURATION_MS, easing = LinearEasing)),
        label = "sweepDegrees"
    )
    val measurer = rememberTextMeasurer()
    val sunPainter = painterResource(R.drawable.ic_sun)
    val moonPainter = painterResource(R.drawable.ic_moon)

    // View state that survives recomposition but not process death: a pinch is a transient
    // gesture, not a preference, so it is deliberately not written to settings.
    var elevationSpan by remember { mutableFloatStateOf(MAX_ELEVATION_SPAN) }
    var userRotation by remember { mutableFloatStateOf(0f) }

    // Track path cache — keyed by canvas size, track identity and the current zoom level so it
    // rebuilds when the satellite track data arrives asynchronously after the first composition
    var cachedRadius by remember { mutableFloatStateOf(0f) }
    var cachedSpan by remember { mutableFloatStateOf(0f) }
    var cachedItemsRef by remember { mutableStateOf<List<OrbitalPos>>(emptyList()) }
    var cachedSweepColor by remember { mutableStateOf(Color.Unspecified) }
    var trackPath by remember { mutableStateOf(Path()) }
    var trackEffect by remember { mutableStateOf(PathEffect.cornerPathEffect(0f)) }
    // ShaderBrush is cached to avoid allocating a new GPU shader object every frame
    var cachedSweepBrush by remember { mutableStateOf<ShaderBrush?>(null) }

    val baseRotation = (if (shouldUseCompass) -azimElev.first else 0f) + userRotation
    val rotation = if (shouldFlipRadar) baseRotation + 180f else baseRotation

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                val slop = HIT_SLOP_DP.dp.toPx()
                detectTapGestures(
                    onDoubleTap = {
                        elevationSpan = MAX_ELEVATION_SPAN
                        userRotation = 0f
                    },
                    onLongPress = { touch ->
                        val radius = minOf(size.width, size.height) / 2f * 0.95f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val target = polarOffset(
                            azimRad = item.azimuth,
                            elevRad = item.elevation,
                            radius = radius,
                            elevationSpanDeg = elevationSpan
                        ).rotateDegrees(rotation) + center
                        val dx = touch.x - target.x
                        val dy = touch.y - target.y
                        if (sqrt(dx * dx + dy * dy) <= slop) onLongPressSatellite()
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) {
                        elevationSpan = (elevationSpan / zoom)
                            .coerceIn(MIN_ELEVATION_SPAN, MAX_ELEVATION_SPAN)
                    }
                    if (pan.x != 0f || pan.y != 0f) {
                        // A horizontal drag swings the azimuth datum; it is normalised into
                        // (-180, 180] so a long drag cannot accumulate without bound.
                        userRotation = normaliseDegrees(userRotation + pan.x * 0.35f)
                    }
                }
            }
    ) {
        val radius = size.minDimension / 2f * 0.95f
        // Rebuild track path and sweep brush when canvas size, zoom or track data changes
        if (radius != cachedRadius || items !== cachedItemsRef || elevationSpan != cachedSpan) {
            trackPath = createTrackPath(items, radius, elevationSpan)
            trackEffect = createTrackEffect(trackPath)
            cachedSweepBrush = makeSweepBrush(center, palette.sweep)
            cachedRadius = radius
            cachedSpan = elevationSpan
            cachedItemsRef = items
            cachedSweepColor = palette.sweep
        } else if (palette.sweep != cachedSweepColor) {
            // Rebuild brush on theme change without waiting for a size change
            cachedSweepBrush = makeSweepBrush(center, palette.sweep)
            cachedSweepColor = palette.sweep
        }
        rotate(rotation) {
            if (shouldShowSweep) {
                cachedSweepBrush?.let { drawSweep(center, sweepDegrees, radius, it) }
            }
            drawRadar(radius, palette.grid)
            drawElevationLabels(radius, elevationSpan, palette.labels, measurer)
            translate(center.x, center.y) {
                drawTrack(trackPath, trackEffect, palette.track, palette.trackTrail)
                if (item.elevation > 0) {
                    val iconSize = 26.dp.toPx() * iconScale
                    val angle = motionAngleDegrees(item, items) { pos ->
                        polarOffset(pos.azimuth, pos.elevation, radius, elevationSpan)
                    }
                    val position = polarOffset(item.azimuth, item.elevation, radius, elevationSpan)
                    if (iconStyle == RadarIconStyle.DOT) {
                        drawCircle(palette.satellite, 16f, position)
                        if (!item.eclipsed) {
                            drawCircle(palette.satelliteOutline.copy(alpha = 1f - (animScale / 64f)), animScale, position)
                        }
                    } else {
                        drawSatelliteIcon(
                            position = position,
                            iconSizePx = iconSize,
                            rotationDeg = if (shouldFlipRadar) angle + 180f else angle,
                            category = category,
                            style = iconStyle,
                            palette = palette,
                            haloRadiusPx = animScale,
                            isEclipsed = item.eclipsed
                        )
                    }
                    if (showLabel && label.isNotEmpty()) {
                        drawSatelliteLabel(position, iconSize, label, palette.satelliteLabel, measurer)
                    }
                }
                sunPosition?.let { sun ->
                    if (sun.elevation > 0) drawBodyIcon(sun.azimuth, sun.elevation, radius, elevationSpan, palette.sun, sunPainter, 52f)
                }
                moonPosition?.let { moon ->
                    if (moon.elevation > 0) {
                        drawBodyIcon(
                            moon.azimuth,
                            moon.elevation,
                            radius,
                            elevationSpan,
                            palette.moon,
                            moonPainter,
                            52f
                        )
                    }
                }
                if (shouldUseCompass) drawAim(azimElev.first, azimElev.second, radius, elevationSpan, palette.aim)
            }
        }
    }
}

private fun DrawScope.drawRadar(radius: Float, color: Color) {
    val step = radius / CIRCLES
    for (i in 0 until CIRCLES) {
        drawCircle(color, radius - step * i, style = Stroke(STROKE_WIDTH))
    }
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), STROKE_WIDTH)
    drawLine(color, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), STROKE_WIDTH)
}

private fun DrawScope.drawElevationLabels(
    radius: Float,
    elevationSpan: Float,
    color: Color,
    measurer: TextMeasurer
) {
    val step = radius / CIRCLES
    val degStep = elevationSpan / CIRCLES
    val style = TextStyle(color, 15.sp)
    for (i in 0 until CIRCLES) {
        val textY = (radius - step * i) - 32f
        drawText(measurer, " ${(degStep * (CIRCLES - i)).toInt()}°", Offset(center.x, textY), style = style)
    }
}

private fun DrawScope.drawTrack(path: Path, effect: PathEffect, color: Color, effectColor: Color) {
    drawPath(path, color, style = Stroke(STROKE_WIDTH))
    drawPath(path, effectColor, style = Stroke(pathEffect = effect))
}

private fun DrawScope.drawSatelliteLabel(
    position: Offset,
    iconSize: Float,
    text: String,
    color: Color,
    measurer: TextMeasurer
) {
    val layout = measurer.measure(text, TextStyle(color, 11.sp))
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            position.x - layout.size.width / 2f,
            position.y + iconSize * 0.55f
        )
    )
}

private fun DrawScope.drawAim(azim: Float, elev: Float, radius: Float, elevationSpan: Float, color: Color) {
    val size = 36f
    val azimRad = azim.toDouble().toRadians()
    val elevRad = elev.toDouble().toRadians().coerceAtMost(0.0)
    val pos = polarOffset(azimRad, -elevRad, radius, elevationSpan)
    drawLine(color, Offset(pos.x - size, pos.y), Offset(pos.x + size, pos.y), STROKE_WIDTH)
    drawLine(color, Offset(pos.x, pos.y - size), Offset(pos.x, pos.y + size), STROKE_WIDTH)
    drawCircle(color, size / 2, pos, style = Stroke(STROKE_WIDTH))
}

private fun makeSweepBrush(center: Offset, color: Color): ShaderBrush {
    val colors = listOf(Color.Transparent, color.copy(alpha = 0.5f), color)
    val colorStops = listOf(0.64f, 0.995f, 1f)
    return ShaderBrush(SweepGradientShader(center, colors, colorStops))
}

private fun DrawScope.drawSweep(center: Offset, degrees: Float, radius: Float, brush: ShaderBrush) {
    rotate(-90 + degrees, center) { drawCircle(brush, radius, style = Fill) }
}

private fun createTrackPath(positions: List<OrbitalPos>, radius: Float, elevationSpan: Float): Path {
    val trackPath = Path()
    positions.forEachIndexed { index, pos ->
        val offset = polarOffset(pos.azimuth, pos.elevation, radius, elevationSpan)
        if (index == 0) trackPath.moveTo(offset.x, offset.y) else trackPath.lineTo(offset.x, offset.y)
    }
    return trackPath
}

private fun createTrackEffect(trackPath: Path): PathEffect {
    val shapeRadius = 24f
    val angle = 120.0.toRadians()
    val shape = Path().apply {
        moveTo((shapeRadius * cos(angle)).toFloat(), (shapeRadius * sin(angle)).toFloat())
        for (i in 1 until 3) {
            lineTo(
                x = (shapeRadius * cos(angle - angle * i)).toFloat(),
                y = (shapeRadius * sin(angle - angle * i)).toFloat()
            )
        }
        close()
    }
    val trackLength = PathMeasure().apply { setPath(trackPath, false) }.length
    return PathEffect.stampedPathEffect(shape, trackLength / 2f, trackLength / 4f, StampedPathEffectStyle.Rotate)
}

private fun DrawScope.drawBodyIcon(
    azimDeg: Double,
    elevDeg: Double,
    radius: Float,
    elevationSpan: Float,
    color: Color,
    painter: Painter,
    iconSize: Float
) {
    val azimRad = azimDeg.toRadians()
    val elevRad = elevDeg.toRadians()
    val pos = polarOffset(azimRad, elevRad, radius, elevationSpan)
    val half = iconSize / 2f
    withTransform({
        translate(pos.x - half, pos.y - half)
    }) {
        with(painter) {
            draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(color))
        }
    }
}

/**
 * Maps azimuth and elevation onto the canvas.
 *
 * [elevationSpanDeg] is the elevation at the outer ring: 90 is the upstream behaviour, smaller
 * values magnify the sky near the zenith so a high pass can be watched in detail.
 */
private fun polarOffset(azimRad: Double, elevRad: Double, radius: Float, elevationSpanDeg: Float): Offset {
    val span = elevationSpanDeg.toDouble().toRadians()
    val fraction = ((span - elevRad) / span).coerceIn(0.0, 1.0)
    val r = radius * fraction
    return Offset(
        x = (r * cos(PI_2 - azimRad)).toFloat(),
        y = -(r * sin(PI_2 - azimRad)).toFloat()
    )
}

private fun Offset.rotateDegrees(degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad)
    val s = sin(rad)
    return Offset((x * c - y * s).toFloat(), (x * s + y * c).toFloat())
}

private fun normaliseDegrees(value: Float): Float {
    var result = value % 360f
    if (result > 180f) result -= 360f
    if (result < -180f) result += 360f
    return result
}
