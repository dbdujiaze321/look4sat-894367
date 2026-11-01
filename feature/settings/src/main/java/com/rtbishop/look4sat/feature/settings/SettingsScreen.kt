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
package com.rtbishop.look4sat.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.analysis.ElementCsv
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.StationBook
import com.rtbishop.look4sat.core.domain.model.RadarIconStyle
import com.rtbishop.look4sat.core.domain.model.ThemePreset
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.presentation.CardButton
import com.rtbishop.look4sat.core.presentation.CustomRadarIcon
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.PrimaryIconCard
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.ScreenColumn
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.WhatsNewDialog
import com.rtbishop.look4sat.core.presentation.infiniteMarquee
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsDestination() {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        loadElementsCsv = { container.databaseRepo.exportElementsCsv() }
    )
}

@Composable
private fun SettingsScreen(
    uiState: SettingsState,
    onAction: (SettingsAction) -> Unit,
    loadElementsCsv: suspend () -> String
) {
    val dialogs = rememberDialogVisibility()
    // Android 13+ needs an explicit grant before a reminder can be posted at all.
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val requestNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissions = rememberSettingsPermissions(
        sendAction = onAction,
        onBluetoothGranted = { dialogs.bluetooth = true },
        onNetworkGranted = { dialogs.network = true }
    )

    // Dialogs
    if (dialogs.position) {
        PositionDialog(
            uiState.positionSettings.stationPos.latitude,
            uiState.positionSettings.stationPos.longitude,
            dismiss = { dialogs.position = false },
            save = { lat, lon -> onAction(SettingsAction.SetGeoPosition(lat, lon)) }
        )
    }
    if (dialogs.locator) {
        LocatorDialog(
            uiState.positionSettings.stationPos.qthLocator,
            dismiss = { dialogs.locator = false },
            save = { onAction(SettingsAction.SetQthPosition(it)) }
        )
    }
    if (dialogs.dataSources) {
        DataSourcesDialog(
            satelliteUrls = uiState.dataSourcesSettings.satelliteUrls,
            transceiversUrls = uiState.dataSourcesSettings.transceiversUrls,
            satelliteEnabled = uiState.dataSourcesSettings.satelliteEnabled,
            transceiversEnabled = uiState.dataSourcesSettings.transceiversEnabled,
            statusCodes = uiState.dataSourcesStatus,
            onImportTle = { permissions.launchTleImport(); dialogs.dataSources = false },
            onImportTransceivers = { permissions.launchTransceiverImport(); dialogs.dataSources = false },
            onDismiss = { dialogs.dataSources = false },
            onSave = { satUrls, txUrls, satEnabled, txEnabled ->
                val newSettings = DataSourcesSettings(
                    satelliteUrls = satUrls,
                    transceiversUrls = txUrls,
                    satelliteEnabled = satEnabled,
                    transceiversEnabled = txEnabled
                )
                if (newSettings != uiState.dataSourcesSettings) onAction(SettingsAction.UpdateDataSources(newSettings))
                onAction(SettingsAction.UpdateFromWeb)
            }
        )
    }
    if (dialogs.network) {
        NetworkOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.network = false },
            onSave = { rotState, rotAddr, rotPort, rotFmt, freqState, freqAddr, freqPort, freqFmt, freqOffsetHz ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            rotatorState = rotState, rotatorAddress = rotAddr,
                            rotatorPort = rotPort, rotatorFormat = rotFmt,
                            frequencyState = freqState, frequencyAddress = freqAddr,
                            frequencyPort = freqPort, frequencyFormat = freqFmt,
                            frequencyOffsetHz = freqOffsetHz
                        )
                    )
                )
            }
        )
    }
    if (dialogs.bluetooth) {
        BluetoothOutputDialog(
            initialSettings = uiState.rcSettings,
            onDismiss = { dialogs.bluetooth = false },
            onSave = { rotState, rotAddr, rotFmt, freqState, freqAddr, freqFmt ->
                onAction(
                    SettingsAction.UpdateRC(
                        uiState.rcSettings.copy(
                            bluetoothRotatorState = rotState, bluetoothRotatorAddress = rotAddr,
                            bluetoothRotatorFormat = rotFmt, bluetoothFrequencyState = freqState,
                            bluetoothFrequencyAddress = freqAddr, bluetoothFrequencyFormat = freqFmt
                        )
                    )
                )
            }
        )
    }
    if (dialogs.radioControl) {
        RadioControlDialog(
            initialSettings = uiState.radioControlSettings,
            pairedBluetoothDevices = uiState.pairedBluetoothDevices,
            onDismiss = { dialogs.radioControl = false },
            onSave = { onAction(SettingsAction.UpdateRadioControl(it)) }
        )
    }
    if (dialogs.whatsNew) {
        WhatsNewDialog(onDismiss = { dialogs.whatsNew = false })
    }

    // URLs for top bar
    val uriHandler = LocalUriHandler.current
//    val appUrl = stringResource(R.string.prefs_app_url)
    val donateUrl = stringResource(R.string.prefs_donate_url)
    val fdroidTitle = stringResource(R.string.prefs_fdroid_title)
    val fdroidUrl = stringResource(R.string.prefs_fdroid_url)
    val gitHubTitle = stringResource(R.string.prefs_github_title)
    val gitHubUrl = stringResource(R.string.prefs_github_url)
    val licenseUrl = stringResource(R.string.prefs_license_url)
    val privacyUrl = stringResource(R.string.prefs_privacy_url)
    val safeOpenUri: (String) -> Unit = { url ->
        try { uriHandler.openUri(url) } catch (_: Exception) {}
    }

    ScreenColumn(
        topBar = { isVerticalLayout ->
            if (isVerticalLayout) {
                TopBar {
                    TopCard(
                        onClick = { dialogs.whatsNew = true },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryIconCard(onClick = { safeOpenUri(donateUrl) }, resId = R.drawable.ic_pound)
                }
                TopBar {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(fdroidUrl) },
                            resId = R.drawable.ic_fdroid,
                            text = fdroidTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(
                        action = { safeOpenUri(licenseUrl) },
                        resId = R.drawable.ic_license,
                        contentDescription = stringResource(R.string.cd_license)
                    )
                    IconCard(
                        action = { safeOpenUri(privacyUrl) },
                        resId = R.drawable.ic_policy,
                        contentDescription = stringResource(R.string.cd_privacy)
                    )
                }
            } else {
                TopBar {
                    PrimaryIconCard(onClick = { safeOpenUri(donateUrl) }, resId = R.drawable.ic_pound)
                    TopCard(
                        onClick = { dialogs.whatsNew = true },
                        version = uiState.appVersionName,
                        modifier = Modifier.weight(1f)
                    )
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotCard(
                            onClick = { safeOpenUri(fdroidUrl) },
                            resId = R.drawable.ic_fdroid,
                            text = fdroidTitle,
                            modifier = Modifier.weight(1f)
                        )
                        BotCard(
                            onClick = { safeOpenUri(gitHubUrl) },
                            resId = R.drawable.ic_github,
                            text = gitHubTitle,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    IconCard(
                        action = { safeOpenUri(licenseUrl) },
                        resId = R.drawable.ic_license,
                        contentDescription = stringResource(R.string.cd_license)
                    )
                    IconCard(
                        action = { safeOpenUri(privacyUrl) },
                        resId = R.drawable.ic_policy,
                        contentDescription = stringResource(R.string.cd_privacy)
                    )
                }
            }
        }
    ) { _ ->
        val isVerticalLayout = isVerticalLayout()
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isVerticalLayout) 1 else 2),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clip(MaterialTheme.shapes.medium)
        ) {
            item {
                LocationCard(
                    settings = uiState.positionSettings,
                    stations = StationBook.decode(uiState.otherSettings.stationsJson),
                    setGpsPos = permissions.launchLocation,
                    showPosDialog = { dialogs.position = true },
                    showLocDialog = { dialogs.locator = true },
                    dismissPosMessage = { onAction(SettingsAction.DismissPosMessages) },
                    onAction = onAction
                )
            }
            item {
                // Resolved here: the import callback is not a composable scope, so stringResource
                // cannot be called from inside it.
                val pasteError = stringResource(R.string.prefs_data_paste_error)
                DataCard(
                    settings = uiState.dataSettings,
                    updateFromWeb = { onAction(SettingsAction.UpdateFromWeb) },
                    clearAllData = { onAction(SettingsAction.ClearAllData) },
                    showDataSourcesDialog = { dialogs.dataSources = true },
                    onImportTle = { text ->
                        onAction(SettingsAction.ImportTleText(text, pasteError))
                    },
                    loadElementsCsv = loadElementsCsv
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutputCard(
                    onNetworkClick = permissions.launchNetwork,
                    onBluetoothClick = permissions.launchBluetooth,
                    onRadioControlClick = { dialogs.radioControl = true }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                AppearanceCard(uiState.otherSettings, onAction, requestNotificationPermission)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (isVerticalLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OtherCard(uiState.otherSettings, onAction)
                        CardCredits()
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    ) {
                        OtherCard(uiState.otherSettings, onAction, modifier = Modifier.weight(1f).fillMaxHeight())
                        CardCredits(modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LocationCardPreview() = MainTheme {
    val stationPos = GeoPos(0.0, 0.0, 0.0, "IO91vl", 0L)
    val settings = PositionSettings(true, stationPos, 0)
    LocationCard(settings = settings, stations = StationBook.EMPTY, setGpsPos = {}, showPosDialog = {}, {}, {}) {}
}

@Composable
private fun LocationCard(
    settings: PositionSettings,
    stations: StationBook,
    setGpsPos: () -> Unit,
    showPosDialog: () -> Unit,
    showLocDialog: () -> Unit,
    dismissPosMessage: () -> Unit,
    onAction: (SettingsAction) -> Unit
) {
    var showStations by remember { mutableStateOf(false) }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_loc_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.stationPos.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.prefs_lat_prefix, settings.stationPos.latitude))
                Text(text = stringResource(R.string.prefs_lon_prefix, settings.stationPos.longitude))
                Text(text = stringResource(R.string.prefs_qth_prefix, settings.stationPos.qthLocator))
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                CardButton(
                    onClick = setGpsPos,
                    text = stringResource(id = R.string.prefs_loc_gps_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showPosDialog,
                    text = stringResource(id = R.string.prefs_loc_input_title),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                CardButton(
                    onClick = showLocDialog,
                    text = stringResource(id = R.string.prefs_loc_qth_title),
                    modifier = Modifier.weight(1f)
                )
            }
            // A fourth button would squeeze "QTH 网格" to an ellipsis on a 360 dp screen, so the
            // saved sites get their own line instead of sharing that row.
            TextButton(onClick = { showStations = true }) {
                val active = stations.active()
                val suffix = active?.name?.let { " · $it" }.orEmpty()
                Text(
                    text = stringResource(id = R.string.prefs_loc_sites_title) +
                        " (${stations.stations.size})$suffix",
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    if (showStations) {
        StationBookDialog(
            book = stations,
            defaultName = positionToQth(settings.stationPos.latitude, settings.stationPos.longitude)
                ?: "",
            dismiss = { showStations = false },
            onSelect = { onAction(SettingsAction.SelectStation(it)) },
            onSaveCurrent = { onAction(SettingsAction.SaveStation(it)) },
            onDelete = { onAction(SettingsAction.DeleteStation(it)) }
        )
    }
    if (settings.messageResId != 0) {
        val errorString = stringResource(id = settings.messageResId)
        LaunchedEffect(key1 = settings.messageResId) {
            onAction(SettingsAction.ShowToast(errorString))
            dismissPosMessage()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DataCardPreview() = MainTheme {
    val settings = DataSettings(true, 5000, 2500, 0L)
    DataCard(
        settings = settings,
        updateFromWeb = {},
        clearAllData = {},
        showDataSourcesDialog = {},
        onImportTle = {},
        loadElementsCsv = { "" }
    )
}

@Composable
private fun DataCard(
    settings: DataSettings,
    updateFromWeb: () -> Unit,
    clearAllData: () -> Unit,
    showDataSourcesDialog: () -> Unit,
    onImportTle: (String) -> Unit,
    loadElementsCsv: suspend () -> String
) {
    var showPaste by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Built before the file picker opens: if the export fails there is nothing to save, and asking
    // for a location first would leave an empty file behind.
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val payload = pendingCsv
        pendingCsv = null
        if (uri == null || payload == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
        }
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.prefs_data_title),
                    color = MaterialTheme.colorScheme.primary
                )
                UpdateIndicator(isUpdating = settings.isUpdating, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = formatUpdateTime(updateTime = settings.timestamp))
            Spacer(modifier = Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.prefs_data_entries, settings.entriesTotal))
                Text(text = stringResource(R.string.prefs_data_radios, settings.radiosTotal))
            }
            Spacer(modifier = Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = updateFromWeb,
                    text = stringResource(id = R.string.prefs_data_update),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = showDataSourcesDialog,
                    text = stringResource(id = R.string.prefs_data_import),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = clearAllData,
                    text = stringResource(id = R.string.prefs_data_clear),
                    modifier = Modifier.weight(1f)
                )
            }
            // Same reason as the saved sites: a fourth button would ellipsise the labels, so the
            // paste and export paths share a line of their own.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { showPaste = true }) {
                    Text(text = stringResource(id = R.string.prefs_data_paste), fontSize = 12.sp)
                }
                TextButton(onClick = {
                    scope.launch {
                        val csv = runCatching { loadElementsCsv() }.getOrNull() ?: return@launch
                        pendingCsv = csv
                        exportLauncher.launch(ElementCsv.fileName())
                    }
                }) {
                    Text(text = stringResource(id = R.string.prefs_data_export), fontSize = 12.sp)
                }
            }
        }
    }
    if (showPaste) {
        TlePasteDialog(dismiss = { showPaste = false }, onImport = onImportTle)
    }
}

@Preview(showBackground = true)
@Composable
private fun OutputCardPreview() = MainTheme { OutputCard({}, {}, {}) }

@Composable
private fun OutputCard(
    onNetworkClick: () -> Unit,
    onBluetoothClick: () -> Unit,
    onRadioControlClick: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(id = R.string.prefs_data_output_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardButton(
                    onClick = onNetworkClick,
                    text = stringResource(id = R.string.prefs_net_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onBluetoothClick,
                    text = stringResource(id = R.string.prefs_bt_output),
                    modifier = Modifier.weight(1f)
                )
                CardButton(
                    onClick = onRadioControlClick,
                    text = stringResource(id = R.string.prefs_cat_output),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OtherCardPreview() = MainTheme {
    val values = OtherSettings(
        stateOfAutoUpdate = true,
        stateOfSensors = true,
        stateOfSweep = true,
        stateOfUtc = false,
        stateOfLightTheme = false,
        stateOfNightMode = false,
        shouldSeeWarning = false,
        shouldSeeWhatsNew = false
    )
    OtherCard(settings = values, onAction = {})
}

@Composable
private fun OtherCard(settings: OtherSettings, onAction: (SettingsAction) -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(id = R.string.prefs_other_title),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Data preferences: auto-update + UTC clock
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchTile(R.string.prefs_other_switch_update, settings.stateOfAutoUpdate) {
                    onAction(SettingsAction.ToggleUpdate(it))
                }
                SwitchTile(R.string.prefs_other_switch_utc, settings.stateOfUtc) {
                    onAction(SettingsAction.ToggleUtc(it))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Appearance: light theme + night filter
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchTile(R.string.prefs_other_switch_light_theme, settings.stateOfLightTheme) {
                    onAction(SettingsAction.ToggleLightTheme(it))
                }
                SwitchTile(
                    R.string.prefs_other_switch_night_mode,
                    settings.stateOfNightMode,
                    enabled = !settings.stateOfLightTheme
                ) {
                    onAction(SettingsAction.ToggleNightMode(it))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Radar behavior: sweep animation + sensors, followed by the compass offset sliders
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchTile(R.string.prefs_other_switch_sweep, settings.stateOfSweep) {
                    onAction(SettingsAction.ToggleSweep(it))
                }
                SwitchTile(R.string.prefs_other_switch_sensors, settings.stateOfSensors) {
                    onAction(SettingsAction.ToggleSensor(it))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Compass calibration sliders at the bottom
            CompassOffsetRow(
                labelResId = R.string.prefs_other_compass_offset_az,
                value = settings.radarCompassOffset,
                range = -180f..180f
            ) { onAction(SettingsAction.SetRadarCompassOffset(it)) }
            Spacer(modifier = Modifier.height(4.dp))
            CompassOffsetRow(
                labelResId = R.string.prefs_other_compass_offset_elev,
                value = settings.radarCompassOffsetElev,
                range = -90f..90f
            ) { onAction(SettingsAction.SetRadarCompassOffsetElev(it)) }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppearanceCardPreview() = MainTheme {
    val values = OtherSettings(
        stateOfAutoUpdate = true,
        stateOfSensors = true,
        stateOfSweep = true,
        stateOfUtc = false,
        stateOfLightTheme = false,
        shouldSeeWarning = false,
        shouldSeeWhatsNew = false
    )
    AppearanceCard(values, {}, {})
}

/**
 * Theme preset, radar marker and list grouping.
 *
 * Every control writes through [SettingsAction] into the same preference store the rest of the app
 * reads, so a change is visible on the next recomposition without restarting the activity.
 */
@Composable
private fun AppearanceCard(
    settings: OtherSettings,
    onAction: (SettingsAction) -> Unit,
    requestNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCustomIcon by remember { mutableStateOf(CustomRadarIcon.exists(context)) }
    val pickCustomIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            hasCustomIcon = CustomRadarIcon.save(context, uri)
            if (!hasCustomIcon) {
                onAction(SettingsAction.ShowToast(context.getString(R.string.prefs_radar_custom_failed)))
            }
        }
    }
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = stringResource(id = R.string.prefs_radar_title),
                color = MaterialTheme.colorScheme.primary
            )
            Text(text = stringResource(id = R.string.prefs_theme_preset))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.themePreset == preset,
                        onClick = { onAction(SettingsAction.SetThemePreset(preset)) },
                        label = { Text(text = stringResource(id = preset.labelRes())) }
                    )
                }
            }
            Text(text = stringResource(id = R.string.prefs_radar_icon_style))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RadarIconStyle.entries.forEach { style ->
                    FilterChip(
                        selected = settings.radarIconStyle == style,
                        onClick = { onAction(SettingsAction.SetRadarIconStyle(style)) },
                        label = { Text(text = stringResource(id = style.labelRes())) }
                    )
                }
            }
            if (settings.radarIconStyle == RadarIconStyle.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { pickCustomIcon.launch("image/*") }) {
                        Text(text = stringResource(R.string.prefs_radar_custom_pick))
                    }
                    if (hasCustomIcon) {
                        TextButton(onClick = {
                            CustomRadarIcon.clear(context)
                            hasCustomIcon = false
                        }) {
                            Text(text = stringResource(R.string.prefs_radar_custom_clear))
                        }
                    }
                }
            }
            // One switch per row: side by side, the longer Chinese label ran underneath the
            // neighbouring switch and the pair read as a single confused line on a 1080 px screen.
            Row(modifier = Modifier.fillMaxWidth()) {
                SwitchTile(R.string.prefs_radar_labels, settings.radarShowLabels) {
                    onAction(SettingsAction.ToggleRadarLabels(it))
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                SwitchTile(R.string.prefs_sat_grouped, settings.satellitesGrouped) {
                    onAction(SettingsAction.ToggleSatellitesGrouped(it))
                }
            }
            Text(
                text = stringResource(id = R.string.prefs_radar_icon_scale) +
                    "  ${(settings.radarIconScale * 100).toInt()}%"
            )
            Slider(
                value = settings.radarIconScale,
                onValueChange = { onAction(SettingsAction.SetRadarIconScale(it)) },
                valueRange = 0.6f..1.8f
            )
            ThemeEditor(settings = settings, onAction = onAction)
            Row(modifier = Modifier.fillMaxWidth()) {
                SwitchTile(R.string.prefs_alarm_switch, settings.passAlarmEnabled) { checked ->
                    if (checked) requestNotificationPermission()
                    onAction(SettingsAction.TogglePassAlarm(checked))
                }
            }
            if (settings.passAlarmEnabled) {
                Text(
                    text = stringResource(id = R.string.prefs_alarm_lead) +
                        "  ${settings.passAlarmLeadMinutes}"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 5, 10, 15, 30).forEach { minutes ->
                        FilterChip(
                            selected = settings.passAlarmLeadMinutes == minutes,
                            onClick = { onAction(SettingsAction.SetPassAlarmLead(minutes)) },
                            label = { Text(text = "$minutes") }
                        )
                    }
                }
            }
        }
    }
}

private fun ThemePreset.labelRes(): Int = when (this) {
    ThemePreset.DEFAULT -> R.string.prefs_theme_default
    ThemePreset.HIGH_CONTRAST -> R.string.prefs_theme_high_contrast
    ThemePreset.RED_NIGHT -> R.string.prefs_theme_red_night
}

private fun RadarIconStyle.labelRes(): Int = when (this) {
    RadarIconStyle.DOT -> R.string.prefs_radar_icon_dot
    RadarIconStyle.OUTLINE -> R.string.prefs_radar_icon_outline
    RadarIconStyle.GLYPH -> R.string.prefs_radar_icon_glyph
    RadarIconStyle.CUSTOM -> R.string.prefs_radar_icon_custom
}

@Composable
private fun CompassOffsetRow(
    labelResId: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(id = labelResId),
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
                .infiniteMarquee(),
            maxLines = 1
        )
        Text(text = "${value.toInt()}°")
    }
    Spacer(modifier = Modifier.height(4.dp))
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range
    )
}

@Composable
private fun RowScope.SwitchTile(
    labelResId: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
    ) {
        Text(
            text = stringResource(id = labelResId),
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp)
                .infiniteMarquee(),
            maxLines = 1
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun formatUpdateTime(updateTime: Long): String {
    val timePattern = stringResource(id = R.string.prefs_updated_time)
    val placeholder = stringResource(id = R.string.pass_time_placeholder)
    val updateDate = remember(updateTime) {
        if (updateTime != 0L) {
            SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(updateTime))
        } else {
            placeholder
        }
    }
    return stringResource(id = R.string.prefs_updated_title, updateDate)
}

@Composable
private fun UpdateIndicator(isUpdating: Boolean, modifier: Modifier = Modifier) = if (isUpdating) {
    LinearProgressIndicator(modifier = modifier.padding(start = 6.dp))
} else {
    LinearProgressIndicator(
        progress = { 0f },
        drawStopIndicator = {},
        modifier = modifier.padding(start = 6.dp)
    )
}

@Preview(showBackground = true)
@Composable
private fun CardCreditsPreview() = MainTheme { CardCredits() }

@Composable
private fun CardCredits(modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .fillMaxHeight()
        ) {
            Text(
                text = stringResource(id = R.string.prefs_outro_title),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(id = R.string.prefs_outro_thanks)
            )
            Text(
                text = stringResource(id = R.string.prefs_outro_data),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(id = R.string.prefs_outro_license),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TopCard(onClick: () -> Unit, modifier: Modifier = Modifier, version: String) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(
                painter = painterResource(id = R.drawable.ic_sputnik),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.prefs_app_title, version),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .infiniteMarquee()
            )
        }
    }
}

@Composable
private fun BotCard(onClick: () -> Unit, resId: Int, text: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(48.dp)
                .clickable { onClick() }) {
            Spacer(Modifier)
            Icon(painter = painterResource(id = resId), contentDescription = null)
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
                    .infiniteMarquee()
            )
        }
    }
}

// region Dialog visibility state holder

@Stable
private class DialogVisibility {
    var position by mutableStateOf(false)
    var locator by mutableStateOf(false)
    var dataSources by mutableStateOf(false)
    var network by mutableStateOf(false)
    var bluetooth by mutableStateOf(false)
    var radioControl by mutableStateOf(false)
    var whatsNew by mutableStateOf(false)
}

@Composable
private fun rememberDialogVisibility(): DialogVisibility {
    return rememberSaveable(saver = run {
        androidx.compose.runtime.saveable.Saver(
            save = {
                listOf(it.position, it.locator, it.dataSources, it.network, it.bluetooth, it.radioControl, it.whatsNew)
            },
            restore = {
                DialogVisibility().apply {
                    position = it[0]; locator = it[1]; dataSources = it[2]
                    network = it[3]; bluetooth = it[4]; radioControl = it[5]; whatsNew = it[6]
                }
            }
        )
    }) { DialogVisibility() }
}

// endregion

// region Permission launchers holder

@Stable
private class SettingsPermissions(
    val launchLocation: () -> Unit,
    val launchTleImport: () -> Unit,
    val launchTransceiverImport: () -> Unit,
    val launchBluetooth: () -> Unit,
    val launchNetwork: () -> Unit
)

@Composable
private fun rememberSettingsPermissions(
    sendAction: (SettingsAction) -> Unit,
    onBluetoothGranted: () -> Unit,
    onNetworkGranted: () -> Unit
): SettingsPermissions {
    val locationError = stringResource(R.string.prefs_loc_gps_error)
    val locationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) sendAction(SettingsAction.SetGpsPosition)
        else sendAction(SettingsAction.ShowToast(locationError))
    }

    val satellitesImportError = stringResource(R.string.prefs_data_import_satellites_error)
    val transceiversImportError = stringResource(R.string.prefs_data_import_transceivers_error)

    val tleRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTLEFromFile(it.toString(), satellitesImportError)) }
    }

    val transceiversRequest = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendAction(SettingsAction.UpdateTransceiversFromFile(it.toString(), transceiversImportError)) }
    }

    val bluetoothError = stringResource(R.string.prefs_bt_perm_error)
    val bluetoothPerm = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        Manifest.permission.BLUETOOTH else Manifest.permission.BLUETOOTH_CONNECT
    val bluetoothRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onBluetoothGranted()
        else sendAction(SettingsAction.ShowToast(bluetoothError))
    }

    val networkError = stringResource(R.string.prefs_net_perm_error)
    val networkRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onNetworkGranted()
        else sendAction(SettingsAction.ShowToast(networkError))
    }

    return remember {
        SettingsPermissions(
            launchLocation = {
                locationRequest.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                )
            },
            launchTleImport = { tleRequest.launch("*/*") },
            launchTransceiverImport = { transceiversRequest.launch("*/*") },
            launchBluetooth = { bluetoothRequest.launch(bluetoothPerm) },
            launchNetwork = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    networkRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    onNetworkGranted()
                }
            }
        )
    }
}

// endregion
