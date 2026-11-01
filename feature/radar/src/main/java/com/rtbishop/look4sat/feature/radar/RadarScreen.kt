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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.model.RadarIconStyle
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.domain.utility.toDegrees
import com.rtbishop.look4sat.core.presentation.CustomRadarIcon
import com.rtbishop.look4sat.core.presentation.EmptyListCard
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.NextPassRow
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.TimerRow
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.formatFrequency
import com.rtbishop.look4sat.core.presentation.getDefaultPass
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import com.rtbishop.look4sat.core.presentation.layoutPadding
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class RadarPage(val title: String) {
    Transceivers("Transceivers"),
    Calculator("Calculator"),
    Sstv("SSTV")
}

@Composable
fun RadarDestination(navigateUp: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: RadarViewModel = viewModel(factory = RadarViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Sync actual permission state on every recomposition so it survives screen re-entry
    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(hasPermission) {
        viewModel.onAction(RadarAction.SstvPermissionResult(hasPermission))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onAction(RadarAction.SstvPermissionResult(granted)) }
    val saveImage = {
        viewModel.onAction(
            RadarAction.SstvSaveImage(
                context.getString(R.string.sstv_image_saved),
                context.getString(R.string.sstv_image_save_failed)
            )
        )
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveImage()
        } else {
            viewModel.onAction(
                RadarAction.ShowToast(context.getString(R.string.sstv_storage_permission_denied))
            )
        }
    }
    RadarScreen(
        uiState, viewModel.timeNow, viewModel::onAction, navigateUp,
        requestMicPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        requestSaveImage = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                saveImage()
            }
        }
    )
}

@Composable
private fun RadarScreen(
    uiState: RadarState,
    timeNow: StateFlow<Long>,
    onAction: (RadarAction) -> Unit,
    navigateUp: () -> Unit,
    requestMicPermission: () -> Unit,
    requestSaveImage: () -> Unit
) {
    val upcomingPass = uiState.currentPass ?: getDefaultPass()
    val addToCalendar: () -> Unit = {
        uiState.currentPass?.let { onAction(RadarAction.AddToCalendar(it.name, it.aosTime, it.losTime)) }
    }
    Column(
        modifier = Modifier
            .layoutPadding()
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            .keepScreenOn(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val isVertical = isVerticalLayout()
        if (isVertical) {
            TopBar {
                IconCard(
                    action = navigateUp,
                    resId = R.drawable.ic_back,
                    contentDescription = stringResource(R.string.cd_back)
                )
                TimerRow(timeString = uiState.currentTime, isTimeAos = uiState.isTimeAos)
                IconCard(
                    action = addToCalendar,
                    resId = R.drawable.ic_calendar,
                    contentDescription = stringResource(R.string.cd_calendar)
                )
            }
            TopBar { NextPassRow(pass = upcomingPass, timeNow = timeNow) }
        } else {
            TopBar {
                IconCard(
                    action = navigateUp,
                    resId = R.drawable.ic_back,
                    contentDescription = stringResource(R.string.cd_back)
                )
                TimerRow(timeString = uiState.currentTime, isTimeAos = uiState.isTimeAos)
                NextPassRow(pass = upcomingPass, timeNow = timeNow, modifier = Modifier.weight(1f))
                IconCard(
                    action = addToCalendar,
                    resId = R.drawable.ic_calendar,
                    contentDescription = stringResource(R.string.cd_calendar)
                )
            }
        }
        if (isVertical) {
            RadarCard(uiState, timeNow, Modifier.weight(1f))
            PagerCard(uiState, onAction, requestMicPermission, requestSaveImage, Modifier.weight(1f))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RadarCard(uiState, timeNow, Modifier.weight(1f))
                PagerCard(uiState, onAction, requestMicPermission, requestSaveImage, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PagerCard(
    uiState: RadarState,
    onAction: (RadarAction) -> Unit,
    requestMicPermission: () -> Unit,
    requestSaveImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCalculatorPage = remember(uiState.transceivers.transmitters) {
        uiState.transceivers.transmitters.any(DopplerFrequencyCalculator::isNamedLinearTransponder)
    }
    val pages = remember(hasCalculatorPage) {
        buildList {
            add(RadarPage.Transceivers)
            if (hasCalculatorPage) add(RadarPage.Calculator)
            add(RadarPage.Sstv)
        }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pages.size) {
        val lastPage = pages.lastIndex
        if (pagerState.currentPage > lastPage) pagerState.scrollToPage(lastPage)
    }

    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            val selectedTabIndex = pagerState.currentPage.coerceIn(0, pages.lastIndex)
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                pages.forEachIndexed { index, page ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(text = page.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                when (pages[pageIndex]) {
                    RadarPage.Transceivers -> TransceiversPage(
                        transceivers = uiState.transceivers.transmitters,
                        selectedUuid = uiState.transceivers.selectedUuid,
                        radioControl = uiState.radioControl,
                        onAction = onAction
                    )
                    RadarPage.Calculator -> CalculatorPage(
                        transceivers = uiState.transceivers.transmitters,
                        selectedUuid = uiState.transceivers.selectedUuid,
                        orbitalPos = uiState.orbitalPos,
                        onAction = onAction,
                        calculatorOffsetKHz = uiState.calculatorOffsetKHz
                    )
                    RadarPage.Sstv -> SstvPage(
                        sstv = uiState.sstv,
                        dopplerFrequency = uiState.transceivers.selectedFrequency?.let { formatFrequency(it) },
                        onAction = onAction,
                        requestMicPermission = requestMicPermission,
                        requestSaveImage = requestSaveImage
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarCard(uiState: RadarState, timeNow: StateFlow<Long>, modifier: Modifier = Modifier) {
    val satellitePos = uiState.orbitalPos
    // Long pressing the marker opens a transient card; tapping it dismisses it again.
    var showMiniCard by remember { mutableStateOf(false) }
    val now by timeNow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Decoded once per style change: decoding a PNG inside the draw block would allocate a bitmap
    // on every animation frame.
    val customIcon = remember(uiState.radarIconStyle) {
        if (uiState.radarIconStyle == RadarIconStyle.CUSTOM) CustomRadarIcon.load(context) else null
    }
    val shouldAnimateBorder = satellitePos?.aboveHorizon == true && satellitePos.eclipsed
    // Always call these composables unconditionally — conditional composable calls violate
    // Compose's slot-table stability rules and can crash or produce incorrect state
    val infiniteTransition = rememberInfiniteTransition(label = "eclipsedBorder")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, delayMillis = 25, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eclipsedBorderAlpha"
    )
    val borderModifier = if (shouldAnimateBorder) {
        Modifier.border(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha),
            shape = MaterialTheme.shapes.medium
        )
    } else Modifier
    ElevatedCard(modifier = modifier.then(borderModifier)) {
        Box(contentAlignment = Alignment.Center) {
            val position = uiState.orbitalPos
            if (position == null) {
                ElevatedCard(modifier = Modifier.fillMaxSize()) {
                    EmptyListCard(message = "")
                }
            } else {
                RadarViewCompose(
                    item = position,
                    items = uiState.satTrack,
                    azimElev = uiState.orientationValues,
                    shouldShowSweep = uiState.shouldShowSweep,
                    shouldUseCompass = uiState.shouldUseCompass,
                    shouldFlipRadar = uiState.shouldFlipRadar,
                    modifier = Modifier.align(Alignment.Center),
                    sunPosition = uiState.sunPosition,
                    moonPosition = uiState.moonPosition,
                    category = uiState.satelliteCategory,
                    iconStyle = uiState.radarIconStyle,
                    showLabel = uiState.radarShowLabels,
                    label = uiState.satelliteLabel,
                    iconScale = uiState.radarIconScale,
                    customIcon = customIcon,
                    onLongPressSatellite = { showMiniCard = true }
                )
                PositionOverlay(position)
                val miniCardPass = uiState.currentPass
                if (showMiniCard && miniCardPass != null) {
                    SatelliteMiniCard(
                        pass = miniCardPass,
                        transceivers = uiState.transceivers.transmitters,
                        now = now,
                        onDismiss = { showMiniCard = false },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

/**
 * Transient card shown when the radar marker is long pressed.
 *
 * Deliberately small and dismissible with a single tap: it must never cover the radar while the
 * user is actually aiming an antenna.
 */
@Composable
private fun SatelliteMiniCard(
    pass: OrbitalPass,
    transceivers: List<SatRadio>,
    now: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val downlink = transceivers.firstNotNullOfOrNull { it.downlinkLow }
    Card(modifier = modifier.padding(12.dp), onClick = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = pass.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.pass_satId, pass.catNum),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            val aosDelta = pass.aosTime - now
            val losDelta = pass.losTime - now
            Text(
                text = stringResource(
                    if (aosDelta >= 0) R.string.radar_mini_aos else R.string.radar_mini_aos_passed,
                    countdown(aosDelta)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    if (losDelta >= 0) R.string.radar_mini_los else R.string.radar_mini_los_passed,
                    countdown(losDelta)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (downlink != null) {
                Text(
                    text = stringResource(
                        R.string.radar_mini_transceivers,
                        transceivers.size,
                        formatFrequency(downlink)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * mm:ss for short waits, h:mm:ss beyond an hour. Always positive: the caller decides whether the
 * value is a countdown or an elapsed time, because "in 6 minutes" and "6 minutes ago" are different
 * sentences and the difference must survive translation.
 */
private fun countdown(millis: Long): String {
    val totalSeconds = kotlin.math.abs(millis) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun PositionOverlay(position: OrbitalPos) {
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadarLabel(
                value = stringResource(R.string.radar_az_value, position.azimuth.toDegrees()),
                label = stringResource(R.string.radar_az_text),
                alignment = Alignment.Start,
                labelFirst = false
            )
            RadarLabel(
                value = stringResource(R.string.radar_az_value, position.elevation.toDegrees()),
                label = stringResource(R.string.radar_el_text),
                alignment = Alignment.End,
                labelFirst = false
            )
        }
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadarLabel(
                value = stringResource(R.string.radar_alt_value, position.altitude),
                label = stringResource(R.string.radar_alt_text),
                alignment = Alignment.Start,
                labelFirst = true
            )
            RadarLabel(
                value = stringResource(R.string.radar_alt_value, position.distance),
                label = stringResource(R.string.radar_dist_text),
                alignment = Alignment.End,
                labelFirst = true
            )
        }
    }
}

@Composable
private fun RadarLabel(
    value: String,
    label: String,
    alignment: Alignment.Horizontal,
    labelFirst: Boolean
) {
    Column(horizontalAlignment = alignment) {
        if (labelFirst) {
            Text(text = label, fontSize = 15.sp)
            Text(text = value, fontSize = 18.sp)
        } else {
            Text(text = value, fontSize = 18.sp)
            Text(text = label, fontSize = 15.sp)
        }
    }
}
