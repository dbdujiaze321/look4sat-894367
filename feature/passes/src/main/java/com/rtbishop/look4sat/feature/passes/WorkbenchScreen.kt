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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.analysis.PassStatistics
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.LocalSpacing
import com.rtbishop.look4sat.core.presentation.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Landing page: what is worth doing right now. Everything is derived from local data — the page
 * must render the same way in airplane mode, with the only network action being the explicit
 * "update TLE" button.
 */
@Composable
fun WorkbenchDestination(
    onOpenPasses: () -> Unit,
    onOpenPassDetail: (Int, Long) -> Unit
) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: WorkbenchViewModel = viewModel(factory = WorkbenchViewModel.factory(container))
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    WorkbenchScreen(
        state = state,
        onRefresh = viewModel::refreshTleData,
        onOpenPasses = onOpenPasses,
        onOpenPassDetail = onOpenPassDetail
    )
}

@Composable
private fun WorkbenchScreen(
    state: WorkbenchState,
    onRefresh: () -> Unit,
    onOpenPasses: () -> Unit,
    onOpenPassDetail: (Int, Long) -> Unit
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            // The activity draws edge to edge, so the title would otherwise sit under the status
            // bar. Every other screen gets this from ScreenColumn; the workbench has its own
            // scroll container and has to apply the inset itself.
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.large, vertical = spacing.medium),
        verticalArrangement = Arrangement.spacedBy(spacing.medium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.workbench_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (state.isCalculating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        NextPassCard(state = state, onOpenPassDetail = onOpenPassDetail)
        DataFreshnessCard(state = state, onRefresh = onRefresh)
        state.statistics?.takeIf { it.passCount > 0 }?.let { WindowStatsCard(it) }

        Text(
            text = stringResource(R.string.workbench_best_passes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        if (state.topPasses.isEmpty()) {
            Text(
                text = stringResource(R.string.workbench_no_passes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.topPasses.forEach { pass ->
                PassRow(pass = pass, onClick = { onOpenPassDetail(pass.catNum, pass.aosTime) })
            }
        }

        TextButton(onClick = onOpenPasses, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.workbench_all_passes))
        }
        Spacer(modifier = Modifier.height(spacing.small))
    }
}

@Composable
private fun NextPassCard(state: WorkbenchState, onOpenPassDetail: (Int, Long) -> Unit) {
    val spacing = LocalSpacing.current
    val pass = state.nextPass
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = stringResource(R.string.workbench_next_satellite),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(spacing.extraExtraSmall))
            Text(
                text = pass?.name ?: stringResource(R.string.workbench_no_passes),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(spacing.small))
            Text(
                text = state.countdown,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            if (pass != null) {
                Spacer(modifier = Modifier.height(spacing.small))
                Text(
                    text = stringResource(
                        R.string.workbench_pass_window,
                        formatTime(pass.aosTime),
                        formatTime(pass.losTime),
                        pass.maxElevation.toInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { onOpenPassDetail(pass.catNum, pass.aosTime) }) {
                    Text(stringResource(R.string.workbench_open_pass))
                }
            }
        }
    }
}

@Composable
private fun DataFreshnessCard(state: WorkbenchState, onRefresh: () -> Unit) {
    val spacing = LocalSpacing.current
    val ageDays = state.dataAgeDays
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(spacing.large)) {
            Text(
                text = if (ageDays == null) {
                    stringResource(R.string.workbench_data_never)
                } else {
                    stringResource(R.string.workbench_data_age, ageDays)
                },
                style = MaterialTheme.typography.bodyMedium
            )
            val precisionLeft = state.highPrecisionDaysLeft
            Spacer(modifier = Modifier.height(spacing.extraExtraSmall))
            Text(
                text = if (precisionLeft == null || precisionLeft <= 0.0) {
                    stringResource(R.string.workbench_high_precision_expired)
                } else {
                    stringResource(R.string.workbench_high_precision, String.format(Locale.US, "%.1f", precisionLeft))
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (precisionLeft != null && precisionLeft > 0.0) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.height(spacing.small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRefresh, enabled = !state.isRefreshing) {
                    Text(
                        stringResource(
                            if (state.isRefreshing) R.string.workbench_refreshing else R.string.workbench_refresh
                        )
                    )
                }
                Spacer(modifier = Modifier.width(spacing.medium))
                Text(
                    text = stringResource(R.string.workbench_satellite_count, state.satelliteCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.message == "refresh-failed") {
                Spacer(modifier = Modifier.height(spacing.extraExtraSmall))
                Text(
                    text = stringResource(R.string.workbench_refresh_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PassRow(pass: OrbitalPass, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = spacing.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pass.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.workbench_pass_window,
                    formatTime(pass.aosTime),
                    formatTime(pass.losTime),
                    pass.maxElevation.toInt()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(
                R.string.workbench_pass_duration,
                ((pass.losTime - pass.aosTime) / 60_000).coerceAtLeast(0)
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * What the current filter actually bought: how many passes, how much observing time, across how many
 * satellites. The pass list shows each event but never the total, and "12 passes" sounds very
 * different from "12 passes, 41 minutes of air time".
 */
@Composable
private fun WindowStatsCard(statistics: PassStatistics) {
    val spacing = LocalSpacing.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            Text(
                text = stringResource(R.string.workbench_stats_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.medium)
            ) {
                StatCell(
                    value = statistics.passCount.toString(),
                    label = stringResource(R.string.workbench_stats_passes),
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    value = formatVisible(statistics.visibleSeconds),
                    label = stringResource(R.string.workbench_stats_visible),
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    value = statistics.satelliteCount.toString(),
                    label = stringResource(R.string.workbench_stats_satellites),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Hours and minutes only: seconds are noise at this aggregate. */
private fun formatVisible(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
