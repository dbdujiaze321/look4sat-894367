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
package com.rtbishop.look4sat.feature.passes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.analysis.DopplerPoint
import com.rtbishop.look4sat.core.domain.analysis.PassSample
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Elevation against time.
 *
 * Drawn as a filled area rather than a bare line: the area under the curve *is* the observing
 * window, and a filled shape makes a 10 degree grazing pass look as uninteresting as it is.
 */
@Composable
internal fun ElevationChart(
    samples: List<PassSample>,
    tcaTime: Long,
    timeLabel: (Long) -> String,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 150.dp
) {
    if (samples.size < 2) return
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.20f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val markerColor = MaterialTheme.colorScheme.secondary
    val start = samples.first().time
    val end = samples.last().time
    val peak = samples.maxOf { it.elevationDeg }
    val top = axisTop(peak)

    Column(modifier = modifier) {
        Row(modifier = Modifier.height(chartHeight)) {
            VerticalAxis(
                labels = Triple("${top.roundToInt()}°", "${(top / 2).roundToInt()}°", "0°"),
                width = 38.dp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                drawGrid(gridColor)
                val xOf = { time: Long -> ((time - start).toFloat() / (end - start)) * size.width }
                val yOf = { value: Double -> size.height - (value / top).toFloat() * size.height }

                val line = Path()
                samples.forEachIndexed { index, sample ->
                    val x = xOf(sample.time)
                    val y = yOf(sample.elevationDeg)
                    if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(area, fillColor)
                drawPath(line, lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

                val tcaX = xOf(tcaTime.coerceIn(start, end))
                drawLine(
                    color = markerColor,
                    start = Offset(tcaX, 0f),
                    end = Offset(tcaX, size.height),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 44.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AxisLabel(timeLabel(start))
            AxisLabel(timeLabel((start + end) / 2))
            AxisLabel(timeLabel(end))
        }
    }
}

/**
 * Doppler offset against time, plotted symmetrically around zero.
 *
 * A shared axis for both directions matters: the whole point is seeing that the receive and transmit
 * curves are mirror images, so the operator knows a single tuning step cancels both.
 */
@Composable
internal fun DopplerChart(
    plan: List<DopplerPoint>,
    nominalHz: Long,
    timeLabel: (Long) -> String,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 150.dp
) {
    if (plan.size < 2) return
    val downlinkColor = MaterialTheme.colorScheme.primary
    val uplinkColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val start = plan.first().time
    val end = plan.last().time
    val peak = plan.maxOf { point ->
        maxOf(abs((point.downlinkHz ?: nominalHz) - nominalHz), abs((point.uplinkHz ?: nominalHz) - nominalHz))
    }.coerceAtLeast(200L)

    Column(modifier = modifier) {
        Row(modifier = Modifier.height(chartHeight)) {
            VerticalAxis(
                labels = Triple("+$peak Hz", "0", "-$peak Hz"),
                width = 52.dp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
            ) {
                drawGrid(gridColor)
                val xOf = { time: Long -> ((time - start).toFloat() / (end - start)) * size.width }
                val yOf = { delta: Long ->
                    size.height / 2f - (delta.toFloat() / peak) * (size.height / 2f) * 0.92f
                }
                drawLine(
                    color = gridColor.copy(alpha = 0.5f),
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 1.5f
                )
                deltas(plan, nominalHz) { it.downlinkHz }?.let { series ->
                    drawPolyline(series, xOf, yOf, downlinkColor)
                }
                deltas(plan, nominalHz) { it.uplinkHz }?.let { series ->
                    drawPolyline(series, xOf, yOf, uplinkColor)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 58.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AxisLabel(timeLabel(start))
            AxisLabel(timeLabel((start + end) / 2))
            AxisLabel(timeLabel(end))
        }
    }
}

/**
 * Offsets from the nominal frequency, or null when this direction is not published at all.
 *
 * A partly populated series is dropped rather than drawn with gaps: a curve with holes reads as a
 * prediction gap, which is a much worse lie than simply not drawing it.
 */
private fun deltas(
    plan: List<DopplerPoint>,
    nominalHz: Long,
    pick: (DopplerPoint) -> Long?
): List<Pair<Long, Long>>? {
    if (plan.any { pick(it) == null }) return null
    return plan.mapNotNull { point -> pick(point)?.let { point.time to (it - nominalHz) } }
}

private fun DrawScope.drawPolyline(
    points: List<Pair<Long, Long>>,
    xOf: (Long) -> Float,
    yOf: (Long) -> Float,
    color: Color
) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { index, (time, delta) ->
        val x = xOf(time)
        val y = yOf(delta)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round))
}

/**
 * Polar view of the pass: horizon on the outside, zenith at the centre, north up.
 *
 * Colour splits at the terminator instead of using a second line style, because at this size a dash
 * pattern is indistinguishable — but the sunlit half of a pass is the half worth looking at.
 */
@Composable
internal fun SkyPlot(
    samples: List<PassSample>,
    modifier: Modifier = Modifier,
    plotSize: Dp = 260.dp
) {
    if (samples.size < 2) return
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val sunlitColor = MaterialTheme.colorScheme.primary
    val eclipseColor = MaterialTheme.colorScheme.secondary
    val markerColor = MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier.size(plotSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(18.dp)) {
            val radius = minOf(this.size.width, this.size.height) / 2f * 0.92f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            // Horizon, then the 30 and 60 degree elevation rings.
            drawCircle(gridColor, radius, center, style = Stroke(width = 1.5f))
            drawCircle(gridColor, radius * (1f - 30f / 90f), center, style = Stroke(width = 1f))
            drawCircle(gridColor, radius * (1f - 60f / 90f), center, style = Stroke(width = 1f))
            drawLine(gridColor, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1f)
            drawLine(gridColor, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1f)

            fun project(sample: PassSample): Offset {
                val r = (radius * (1f - (sample.elevationDeg / 90.0).toFloat().coerceIn(0f, 1f))).toDouble()
                val az = Math.toRadians(sample.azimuthDeg)
                return Offset(
                    center.x + (r * sin(az)).toFloat(),
                    center.y - (r * cos(az)).toFloat()
                )
            }

            var previous = project(samples.first())
            var previousSunlit = samples.first().sunlit
            for (index in 1 until samples.size) {
                val current = project(samples[index])
                val sunlit = samples[index].sunlit
                drawLine(
                    color = if (sunlit && previousSunlit) sunlitColor else eclipseColor,
                    start = previous,
                    end = current,
                    strokeWidth = 2.5f,
                    cap = StrokeCap.Round
                )
                previous = current
                previousSunlit = sunlit
            }

            // AOS and LOS markers: filled square and hollow circle, distinguishable without colour.
            val first = project(samples.first())
            val last = project(samples.last())
            drawCircle(sunlitColor, 4f, first)
            drawCircle(markerColor, 4f, last, style = Stroke(width = 2f))
        }
        Text(
            text = "N",
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        Text("E", color = labelColor, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterEnd))
        Text("S", color = labelColor, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter))
        Text("W", color = labelColor, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart))
    }
}

/**
 * The three labels down the left edge of a chart: top, middle, bottom.
 *
 * Both charts had their own copy of this column, which is why they disagreed about the width.
 */
/** Round the top of the axis up to a whole number of 15 degree steps. */
private fun axisTop(peak: Double): Double = (ceil(peak / 15.0) * 15.0).coerceAtLeast(15.0)

@Composable
private fun VerticalAxis(labels: Triple<String, String, String>, width: Dp) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End
    ) {
        AxisLabel(labels.first)
        AxisLabel(labels.second)
        AxisLabel(labels.third)
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        maxLines = 1,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun DrawScope.drawGrid(color: Color) {
    for (step in 0..2) {
        val y = size.height * step / 2f
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f
        )
    }
    for (step in 0..2) {
        val x = size.width * step / 2f
        drawLine(
            color = color.copy(alpha = 0.6f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f
        )
    }
}
