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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.analysis.DopplerPoint
import com.rtbishop.look4sat.core.domain.analysis.ElementFreshness
import com.rtbishop.look4sat.core.domain.analysis.OrbitClass
import com.rtbishop.look4sat.core.domain.analysis.OrbitElements
import com.rtbishop.look4sat.core.domain.analysis.PassCsv
import com.rtbishop.look4sat.core.domain.analysis.PassMetrics
import com.rtbishop.look4sat.core.domain.analysis.PassSample
import com.rtbishop.look4sat.core.domain.analysis.PassStatistics
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.displayLocale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

@Composable
fun PassAnalysisRoute(catNum: Int, aosTime: Long, navigateUp: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: PassAnalysisViewModel = viewModel(
        factory = PassAnalysisViewModel.factory(container, catNum, aosTime)
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PassAnalysisScreen(
        state = uiState,
        onSelectRadio = viewModel::selectRadio,
        navigateUp = navigateUp
    )
}

@Composable
private fun PassAnalysisScreen(
    state: PassAnalysisState,
    onSelectRadio: (String) -> Unit,
    navigateUp: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            AnalysisHeader(state = state, navigateUp = navigateUp)
            when {
                state.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.isMissing || state.pass == null -> Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.analysis_missing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> AnalysisContent(state = state, onSelectRadio = onSelectRadio)
            }
        }
    }
}

@Composable
private fun AnalysisHeader(state: PassAnalysisState, navigateUp: () -> Unit) {
    val pass = state.pass
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconCard(action = navigateUp, resId = R.drawable.ic_back)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pass?.name ?: stringResource(R.string.analysis_title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            state.elements?.let { elements ->
                Text(
                    text = "${pass?.catNum ?: 0} · ${stringResource(elements.orbitalClass.labelRes())}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (pass != null && state.metrics != null) {
            ExportAction(state = state)
        }
    }
}

@Composable
private fun ExportAction(state: PassAnalysisState) {
    val context = LocalContext.current
    val pass = state.pass ?: return
    val metrics = state.metrics ?: return
    val csv = remember(pass, metrics, state.samples, state.referenceHz) {
        PassCsv.build(
            pass = pass,
            metrics = metrics,
            samples = state.samples,
            nominalDownlinkHz = state.referenceHz
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
        }
    }
    TextButton(onClick = { launcher.launch(PassCsv.fileName(pass, metrics)) }) {
        Text(text = stringResource(R.string.analysis_export), fontSize = 13.sp)
    }
}

@Composable
private fun AnalysisContent(state: PassAnalysisState, onSelectRadio: (String) -> Unit) {
    val pass = state.pass ?: return
    val metrics = state.metrics
    // Assigned to a named val on purpose: `apply { ... }` followed by a lambda on the next line is
    // parsed as a second argument list, and the formatter would be invoked instead of returned.
    val timeLabel: (Long) -> String = remember(state.isUtc) {
        val format = SimpleDateFormat("HH:mm:ss", displayLocale())
        format.timeZone = if (state.isUtc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val label: (Long) -> String = { time -> format.format(Date(time)) }
        label
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (metrics != null) {
            item { GeometryCard(metrics = metrics, timeLabel = timeLabel) }
        }
        state.elements?.let { item { OrbitCard(elements = it) } }
        if (state.samples.size >= 2 && metrics != null) {
            item {
                SectionCard(title = stringResource(R.string.analysis_elevation)) {
                    ElevationChart(
                        samples = state.samples,
                        tcaTime = metrics.tcaTime,
                        timeLabel = timeLabel
                    )
                }
            }
            item {
                DopplerCard(
                    state = state,
                    metrics = metrics,
                    timeLabel = timeLabel,
                    onSelectRadio = onSelectRadio
                )
            }
            item {
                SectionCard(title = stringResource(R.string.analysis_sky)) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        SkyPlot(samples = state.samples)
                    }
                }
            }
            item { SamplesCard(samples = state.samples, timeLabel = timeLabel) }
        }
        state.statistics?.let { item { StatisticsCard(statistics = it) } }
        item { Spacer(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun GeometryCard(metrics: PassMetrics, timeLabel: (Long) -> String) {
    SectionCard(title = stringResource(R.string.analysis_geometry)) {
        MetricPair(
            stringResource(R.string.analysis_aos) to timeLabel(metrics.aosTime),
            stringResource(R.string.analysis_los) to timeLabel(metrics.losTime)
        )
        MetricPair(
            stringResource(R.string.analysis_tca) to timeLabel(metrics.tcaTime),
            stringResource(R.string.analysis_duration) to formatDuration(metrics.durationSeconds)
        )
        MetricPair(
            stringResource(R.string.analysis_max_elev) to "${formatDegrees(metrics.maxElevationDeg)}°",
            stringResource(R.string.analysis_az_tca) to "${formatDegrees(metrics.azimuthAtTcaDeg)}°"
        )
        MetricPair(
            stringResource(R.string.analysis_min_range) to "${formatKm(metrics.minRangeKm)} km",
            stringResource(R.string.analysis_peak_rate) to "${formatRate(metrics.peakRangeRateKmS)} km/s"
        )
        MetricPair(
            stringResource(R.string.analysis_sweep) to "${metrics.azimuthSweepDeg.toInt()}°",
            stringResource(R.string.analysis_sunlit) to "${(metrics.sunlitFraction * 100).toInt()}%"
        )
    }
}

@Composable
private fun OrbitCard(elements: OrbitElements) {
    SectionCard(title = stringResource(R.string.analysis_orbit)) {
        MetricPair(
            stringResource(R.string.analysis_period) to "${formatKm(elements.periodMinutes)} min",
            stringResource(R.string.analysis_revs) to String.format(Locale.ROOT, "%.2f", elements.revolutionsPerDay)
        )
        MetricPair(
            stringResource(R.string.analysis_perigee) to "${elements.perigeeAltitudeKm.toInt()} km",
            stringResource(R.string.analysis_apogee) to "${elements.apogeeAltitudeKm.toInt()} km"
        )
        MetricPair(
            stringResource(R.string.analysis_incl) to "${formatDegrees(elements.inclinationDeg)}°",
            stringResource(R.string.analysis_ecc) to String.format(Locale.ROOT, "%.5f", elements.eccentricity)
        )
        MetricPair(
            stringResource(R.string.analysis_node) to "${formatDrift(elements.nodalRegressionDegPerDay)}°/d",
            stringResource(R.string.analysis_apsis) to "${formatDrift(elements.apsidalRotationDegPerDay)}°/d"
        )
        MetricPair(
            stringResource(R.string.analysis_age) to String.format(Locale.ROOT, "%.2f d", elements.elementAgeDays),
            stringResource(R.string.analysis_freshness) to stringResource(elements.freshness.labelRes())
        )
    }
}

@Composable
private fun DopplerCard(
    state: PassAnalysisState,
    metrics: PassMetrics,
    timeLabel: (Long) -> String,
    onSelectRadio: (String) -> Unit
) {
    SectionCard(title = stringResource(R.string.analysis_doppler)) {
        if (state.radios.isEmpty()) {
            Text(
                text = stringResource(R.string.analysis_no_radios),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items = state.radios, key = { it.uuid }) { radio ->
                FilterChip(
                    selected = radio.uuid == state.selectedRadioUuid,
                    onClick = { onSelectRadio(radio.uuid) },
                    label = {
                        Text(
                            text = radioLabel(radio),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
        val reference = state.referenceHz
        if (reference != null) {
            Text(
                text = "${stringResource(R.string.analysis_peak_doppler)} " +
                    "±${metrics.peakDopplerHz(reference)} Hz @ " +
                    String.format(Locale.ROOT, "%.3f", reference / 1_000_000.0) + " MHz",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.plan.size >= 2) {
                DopplerChart(plan = state.plan, nominalHz = reference, timeLabel = timeLabel)
                DopplerTable(plan = state.plan, metrics = metrics, reference = reference)
            }
        }
    }
}

@Composable
private fun DopplerTable(plan: List<DopplerPoint>, metrics: PassMetrics, reference: Long) {
    val rows = listOf(
        stringResource(R.string.analysis_aos) to metrics.aosTime,
        stringResource(R.string.analysis_tca) to metrics.tcaTime,
        stringResource(R.string.analysis_los) to metrics.losTime
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TableCell("", weight = 0.7f, isHeader = true)
            TableCell("RX Δ", weight = 1f, isHeader = true)
            TableCell("TX Δ", weight = 1f, isHeader = true)
        }
        rows.forEach { (label, time) ->
            val point = nearest(plan, time)
            Row(modifier = Modifier.fillMaxWidth()) {
                TableCell(label, weight = 0.7f)
                TableCell(point?.downlinkHz?.let { formatDelta(it - reference) } ?: "—", weight = 1f)
                TableCell(point?.uplinkHz?.let { formatDelta(it - reference) } ?: "—", weight = 1f)
            }
        }
    }
}

private fun nearest(plan: List<DopplerPoint>, time: Long): DopplerPoint? =
    plan.minByOrNull { abs(it.time - time) }

@Composable
private fun SamplesCard(samples: List<PassSample>, timeLabel: (Long) -> String) {
    // One row per minute at most: the chart carries the shape, and a hundred rows of numbers on a
    // phone is a scroll bar, not a table.
    val rows = remember(samples) {
        val step = maxOf(1, samples.size / 20)
        samples.filterIndexed { index, _ -> index % step == 0 }
    }
    SectionCard(title = stringResource(R.string.analysis_samples)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TableCell("UTC", weight = 1.1f, isHeader = true)
            TableCell("Az", weight = 0.8f, isHeader = true)
            TableCell("El", weight = 0.8f, isHeader = true)
            TableCell("km", weight = 1f, isHeader = true)
            TableCell("km/s", weight = 1f, isHeader = true)
        }
        rows.forEach { sample ->
            val color = if (sample.sunlit) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TableCell(timeLabel(sample.time), weight = 1.1f, color = color)
                TableCell(sample.azimuthDeg.toInt().toString(), weight = 0.8f, color = color)
                TableCell(sample.elevationDeg.toInt().toString(), weight = 0.8f, color = color)
                TableCell(sample.rangeKm.toInt().toString(), weight = 1f, color = color)
                TableCell(
                    String.format(Locale.ROOT, "%.2f", sample.rangeRateKmS),
                    weight = 1f,
                    color = color
                )
            }
        }
        Text(
            text = stringResource(R.string.analysis_samples_hint),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatisticsCard(statistics: PassStatistics) {
    SectionCard(title = stringResource(R.string.analysis_stats)) {
        MetricPair(
            stringResource(R.string.analysis_pass_count) to "${statistics.passCount}",
            stringResource(R.string.analysis_visible_total) to formatDuration(statistics.visibleSeconds)
        )
        MetricPair(
            stringResource(R.string.analysis_median_elev) to "${formatDegrees(statistics.medianMaxElevationDeg)}°",
            stringResource(R.string.analysis_best_elev) to "${formatDegrees(statistics.bestElevationDeg)}°"
        )
    }
}

@Composable
private fun MetricPair(left: Pair<String, String>, right: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCell(label = left.first, value = left.second, modifier = Modifier.weight(1f))
        MetricCell(label = right.first, value = right.second, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    isHeader: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        fontSize = 11.sp,
        maxLines = 1,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (isHeader) FontWeight.Medium else FontWeight.Normal,
        color = if (isHeader) MaterialTheme.colorScheme.onSurfaceVariant else color
    )
}

@Composable
private fun radioLabel(radio: SatRadio): String {
    val downlink = radio.downlinkLow ?: radio.downlinkHigh
    val uplink = radio.uplinkLow ?: radio.uplinkHigh
    val frequencies = when {
        downlink != null && uplink != null ->
            "${MHz(uplink)}↑ ${MHz(downlink)}↓"
        downlink != null -> "${MHz(downlink)}↓"
        uplink != null -> "${MHz(uplink)}↑"
        else -> "—"
    }
    val mode = radio.downlinkMode ?: radio.uplinkMode
    val name = radio.info.ifBlank { mode ?: "" }.take(18)
    return if (name.isEmpty()) frequencies else "$name · $frequencies"
}

private fun MHz(hz: Long): String = String.format(Locale.ROOT, "%.4f", hz / 1_000_000.0)

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, remainder)
}

private fun formatDegrees(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private fun formatKm(value: Double): String = String.format(Locale.ROOT, "%.0f", value)

private fun formatRate(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private fun formatDrift(value: Double): String = String.format(Locale.ROOT, "%+.3f", value)

private fun formatDelta(value: Long): String = String.format(Locale.ROOT, "%+d", value)

private fun OrbitClass.labelRes(): Int = when (this) {
    OrbitClass.LEO -> R.string.analysis_class_leo
    OrbitClass.MEO -> R.string.analysis_class_meo
    OrbitClass.GEO -> R.string.analysis_class_geo
    OrbitClass.HEO -> R.string.analysis_class_heo
}

private fun ElementFreshness.labelRes(): Int = when (this) {
    ElementFreshness.FRESH -> R.string.analysis_fresh_fresh
    ElementFreshness.GOOD -> R.string.analysis_fresh_good
    ElementFreshness.USABLE -> R.string.analysis_fresh_usable
    ElementFreshness.STALE -> R.string.analysis_fresh_stale
}
