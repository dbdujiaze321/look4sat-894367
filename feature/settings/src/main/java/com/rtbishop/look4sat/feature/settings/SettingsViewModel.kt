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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.model.SavedStation
import com.rtbishop.look4sat.core.domain.model.StationBook
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.usecase.IShowToast
import com.rtbishop.look4sat.core.domain.utility.positionToQth
import com.rtbishop.look4sat.core.presentation.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val container: IMainContainer,
    private val databaseRepo: IDatabaseRepo,
    private val settingsRepo: ISettingsRepo,
    private val showToast: IShowToast
) : ViewModel() {

    private val defaultPosSettings = PositionSettings(false, settingsRepo.stationPosition.value, 0)
    private val defaultDataSettings = DataSettings(false, 0, 0, 0L)
    private val _uiState = MutableStateFlow(
        SettingsState(
            appVersionName = settingsRepo.appVersionName,
            positionSettings = defaultPosSettings,
            dataSettings = defaultDataSettings,
            otherSettings = settingsRepo.otherSettings.value,
            rcSettings = settingsRepo.rcSettings.value,
            radioControlSettings = settingsRepo.radioControlSettings.value,
            dataSourcesSettings = settingsRepo.dataSourcesSettings.value,
            dataSourcesStatus = settingsRepo.dataSourcesStatus.value,
            pairedBluetoothDevices = container.providePairedBluetoothDevices()
        )
    )

    val uiState: StateFlow<SettingsState> = _uiState

    init {
        viewModelScope.launch {
            settingsRepo.stationPosition.collect { geoPos ->
                _uiState.update {
                    it.copy(positionSettings = it.positionSettings.copy(isUpdating = false, stationPos = geoPos))
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.databaseState.collect { state ->
                _uiState.update {
                    it.copy(
                        dataSettings = it.dataSettings.copy(
                            isUpdating = false,
                            entriesTotal = state.numberOfSatellites,
                            radiosTotal = state.numberOfRadios,
                            timestamp = state.updateTimestamp
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.rcSettings.collect { settings ->
                _uiState.update { it.copy(rcSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.otherSettings.collect { settings ->
                _uiState.update { it.copy(otherSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.dataSourcesSettings.collect { settings ->
                _uiState.update { it.copy(dataSourcesSettings = settings) }
            }
        }
        viewModelScope.launch {
            settingsRepo.dataSourcesStatus.collect { status ->
                _uiState.update { it.copy(dataSourcesStatus = status) }
            }
        }
        viewModelScope.launch {
            settingsRepo.radioControlSettings.collect { settings ->
                _uiState.update { it.copy(radioControlSettings = settings) }
            }
        }
    }


    fun onAction(action: SettingsAction) {
        when (action) {
            // Position
            SettingsAction.SetGpsPosition -> setGpsPosition()
            is SettingsAction.SetGeoPosition -> setGeoPosition(action.latitude, action.longitude)
            is SettingsAction.SetQthPosition -> setQthPosition(action.locator)
            is SettingsAction.SaveStation -> saveStation(action.name)
            is SettingsAction.SelectStation -> selectStation(action.id)
            is SettingsAction.DeleteStation -> deleteStation(action.id)
            SettingsAction.DismissPosMessages -> dismissPosMessage()
            // Data
            SettingsAction.UpdateFromWeb -> runDataUpdate { databaseRepo.updateFromRemote() }
            is SettingsAction.UpdateTLEFromFile -> runManualImport(action.invalidFileMessage) {
                databaseRepo.updateTLEFromFile(action.uri)
            }
            is SettingsAction.UpdateTransceiversFromFile -> runManualImport(action.invalidFileMessage) {
                databaseRepo.updateTransceiversFromFile(action.uri)
            }
            is SettingsAction.ImportTleText -> runManualImport(action.errorMessage) {
                databaseRepo.updateTLEFromText(action.text)
            }
            SettingsAction.ClearAllData -> viewModelScope.launch { databaseRepo.clearAllData() }
            // Toggles
            is SettingsAction.ToggleUtc -> settingsRepo.updateOtherSettings { it.copy(stateOfUtc = action.value) }
            is SettingsAction.ToggleUpdate -> settingsRepo.updateOtherSettings { it.copy(stateOfAutoUpdate = action.value) }
            is SettingsAction.ToggleSweep -> settingsRepo.updateOtherSettings { it.copy(stateOfSweep = action.value) }
            is SettingsAction.ToggleSensor -> settingsRepo.updateOtherSettings { it.copy(stateOfSensors = action.value) }
            is SettingsAction.ToggleLightTheme -> settingsRepo.updateOtherSettings {
                // Night filter (red-only overlay) is incompatible with the light theme, so turn it off.
                it.copy(stateOfLightTheme = action.value, stateOfNightMode = if (action.value) false else it.stateOfNightMode)
            }
            is SettingsAction.ToggleNightMode -> settingsRepo.updateOtherSettings { it.copy(stateOfNightMode = action.value) }
            is SettingsAction.SetRadarCompassOffset -> settingsRepo.updateOtherSettings { it.copy(radarCompassOffset = action.value) }
            is SettingsAction.SetRadarCompassOffsetElev -> settingsRepo.updateOtherSettings { it.copy(radarCompassOffsetElev = action.value) }
            is SettingsAction.SetThemePreset -> settingsRepo.updateOtherSettings { it.copy(themePreset = action.preset) }
            is SettingsAction.SetRadarIconStyle -> settingsRepo.updateOtherSettings { it.copy(radarIconStyle = action.style) }
            is SettingsAction.ToggleRadarLabels -> settingsRepo.updateOtherSettings { it.copy(radarShowLabels = action.value) }
            is SettingsAction.SetRadarIconScale -> settingsRepo.updateOtherSettings { it.copy(radarIconScale = action.value) }
            is SettingsAction.ToggleSatellitesGrouped -> settingsRepo.updateOtherSettings { it.copy(satellitesGrouped = action.value) }
            is SettingsAction.TogglePassAlarm -> settingsRepo.updateOtherSettings { it.copy(passAlarmEnabled = action.value) }
            is SettingsAction.SetPassAlarmLead -> settingsRepo.updateOtherSettings { it.copy(passAlarmLeadMinutes = action.minutes) }
            is SettingsAction.SetThemeJson -> settingsRepo.updateOtherSettings { it.copy(themeJson = action.json) }
            // Remote control & data sources
            is SettingsAction.UpdateRC -> settingsRepo.updateRCSettings(action.settings)
            is SettingsAction.UpdateRadioControl -> settingsRepo.updateRadioControlSettings(action.settings)
            is SettingsAction.UpdateDataSources -> settingsRepo.updateDataSourcesSettings(action.settings)
            // System
            is SettingsAction.ShowToast -> showToast(action.message)
        }
    }

    // region Position helpers — consolidated from 3 near-identical functions

    private fun setGpsPosition() {
        updatePosition(R.string.prefs_loc_gps_error) { settingsRepo.setStationPosition() }
    }

    private fun setGeoPosition(latitude: Double, longitude: Double) {
        updatePosition(R.string.prefs_loc_input_error) { settingsRepo.setStationPosition(latitude, longitude, 0.0) }
    }

    private fun setQthPosition(locator: String) {
        updatePosition(R.string.prefs_loc_qth_error) { settingsRepo.setStationPosition(locator) }
    }

    /**
     * Stores the current position under a name.
     *
     * Saving the same coordinates twice updates the existing entry instead of appending a duplicate:
     * the GPS jitters by a few metres between saves, so comparing exactly would never match and the
     * list would fill with near-identical rows.
     */
    private fun saveStation(name: String) {
        val position = settingsRepo.stationPosition.value
        val book = StationBook.decode(settingsRepo.otherSettings.value.stationsJson)
        val existing = book.stations.firstOrNull {
            kotlin.math.abs(it.latitude - position.latitude) < 1e-4 &&
                kotlin.math.abs(it.longitude - position.longitude) < 1e-4
        }
        // Derived from the coordinates, not from the stored locator field: that field reads
        // "null" when it was never filled in, which would become a station called "null".
        val fallback = positionToQth(position.latitude, position.longitude) ?: "QTH"
        val label = name.trim().ifBlank { fallback }
        if (StationBook.validate(label, position.latitude, position.longitude, position.altitude) != null) {
            return
        }
        val station = SavedStation(
            id = existing?.id ?: StationBook.newId(),
            name = label,
            latitude = position.latitude,
            longitude = position.longitude,
            altitudeMeters = position.altitude
        )
        val updated = book.addOrReplace(station)
        settingsRepo.updateOtherSettings { it.copy(stationsJson = updated.encode()) }
    }

    /**
     * Switches the active site and moves the station position with it.
     *
     * Both steps happen together on purpose: storing only the id would leave the predictions running
     * from the old coordinates while the UI claimed a different site was selected.
     */
    private fun selectStation(id: String) {
        val book = StationBook.decode(settingsRepo.otherSettings.value.stationsJson)
        val station = book.select(id).active() ?: return
        val applied = settingsRepo.setStationPosition(
            station.latitude, station.longitude, station.altitudeMeters
        )
        if (!applied) return
        settingsRepo.updateOtherSettings { it.copy(stationsJson = book.select(id).encode()) }
    }

    private fun deleteStation(id: String) {
        val book = StationBook.decode(settingsRepo.otherSettings.value.stationsJson).remove(id)
        settingsRepo.updateOtherSettings { it.copy(stationsJson = book.encode()) }
    }

    /**
     * Common position update logic. Calls [action], emits success or error message.
     */
    private inline fun updatePosition(errorResId: Int, action: () -> Boolean) {
        val success = action()
        val resId = if (success) R.string.prefs_loc_success else errorResId
        val isUpdating = success // GPS update is async; geo/qth are immediate
        _uiState.update {
            it.copy(positionSettings = it.positionSettings.copy(isUpdating = isUpdating, messageResId = resId))
        }
    }

    private fun dismissPosMessage() {
        _uiState.update {
            it.copy(positionSettings = it.positionSettings.copy(isUpdating = false, messageResId = 0))
        }
    }

    // endregion

    // region Data update helpers — consolidated from 3 near-identical functions

    /**
     * Common data update logic. Sets isUpdating=true, runs the suspending [block],
     * and resets isUpdating=false on failure.
     */
    private fun runDataUpdate(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            _uiState.update {
                it.copy(dataSettings = it.dataSettings.copy(isUpdating = true))
            }
            block()
        } catch (exception: Exception) {
            _uiState.update {
                it.copy(dataSettings = it.dataSettings.copy(isUpdating = false))
            }
            println(exception)
        }
    }

    private fun runManualImport(importErrorMessage: String, block: suspend () -> Int) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(dataSettings = it.dataSettings.copy(isUpdating = true)) }
                if (block() == 0) showToast(importErrorMessage)
            } catch (exception: Exception) {
                _uiState.update { it.copy(dataSettings = it.dataSettings.copy(isUpdating = false)) }
                println(exception)
            }
        }
    }

    // endregion

    companion object {

        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container = container,
                    databaseRepo = container.databaseRepo,
                    settingsRepo = container.settingsRepo,
                    showToast = container.provideShowToast()
                )
            }
        }
    }
}
