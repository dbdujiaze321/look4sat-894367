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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rtbishop.look4sat.core.domain.analysis.DopplerPlan
import com.rtbishop.look4sat.core.domain.analysis.DopplerPoint
import com.rtbishop.look4sat.core.domain.analysis.OrbitElements
import com.rtbishop.look4sat.core.domain.analysis.PassMetrics
import com.rtbishop.look4sat.core.domain.analysis.PassSampler
import com.rtbishop.look4sat.core.domain.analysis.PassStatistics
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PassAnalysisState(
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val isUtc: Boolean = false,
    val pass: OrbitalPass? = null,
    val metrics: PassMetrics? = null,
    val elements: OrbitElements? = null,
    val samples: List<com.rtbishop.look4sat.core.domain.analysis.PassSample> = emptyList(),
    val radios: List<SatRadio> = emptyList(),
    val selectedRadioUuid: String? = null,
    val plan: List<DopplerPoint> = emptyList(),
    val referenceHz: Long? = null,
    val statistics: PassStatistics? = null,
    /** Lead time used for the calendar reminder; mirrors the pass alarm setting. */
    val reminderMinutes: Int = 10
) {
    val selectedRadio: SatRadio? get() = radios.firstOrNull { it.uuid == selectedRadioUuid }
}

/**
 * Loads one pass in full detail.
 *
 * Deliberately independent of the pass list: the list is recomputed on every filter change and
 * carries only what a row needs, while this screen wants 121 samples of the same pass. Recomputing
 * them here costs milliseconds and keeps [com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo]
 * from having to hold a per-pass curve for every satellite in the selection.
 */
class PassAnalysisViewModel(
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    private val catNum: Int,
    private val aosTime: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        PassAnalysisState(
            isUtc = settingsRepo.otherSettings.value.stateOfUtc,
            reminderMinutes = settingsRepo.otherSettings.value.passAlarmLeadMinutes
        )
    )
    val uiState: StateFlow<PassAnalysisState> = _uiState.asStateFlow()

    init {
        load()
    }

    /**
     * Drops every other satellite from the selection.
     *
     * The repository already watches the selection and recalculates on change, so this one call is
     * the whole feature: no second prediction path, no chance of disagreeing with the pass list.
     */
    fun focusOnlyThisSatellite() {
        settingsRepo.setSelectedIds(listOf(catNum))
    }

    fun selectRadio(uuid: String) {
        val state = _uiState.value
        val radio = state.radios.firstOrNull { it.uuid == uuid } ?: return
        val reference = radio.downlinkLow ?: radio.downlinkHigh ?: radio.uplinkLow ?: radio.uplinkHigh
        _uiState.update {
            it.copy(
                selectedRadioUuid = uuid,
                plan = DopplerPlan.forTransponder(radio, it.samples),
                referenceHz = reference
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            val passes = satelliteRepo.passes.value
            val pass = passes.firstOrNull { it.catNum == catNum && it.aosTime == aosTime }
            if (pass == null) {
                _uiState.update { it.copy(isLoading = false, isMissing = true) }
                return@launch
            }
            val station = settingsRepo.stationPosition.value
            val now = System.currentTimeMillis()
            val payload = withContext(Dispatchers.Default) {
                val samples = PassSampler.sample(
                    obj = pass.orbitalObject,
                    station = station,
                    aosTime = pass.aosTime,
                    losTime = pass.losTime
                )
                val metrics = if (samples.size >= 2) {
                    PassSampler.metrics(pass.orbitalObject, station, pass.aosTime, pass.losTime, samples)
                } else {
                    null
                }
                Triple(samples, metrics, OrbitElements.from(pass.orbitalObject.data, now))
            }
            val radios = runCatching { satelliteRepo.getRadiosWithId(catNum) }.getOrDefault(emptyList())
            val preferred = radios.firstOrNull { it.downlinkLow != null || it.downlinkHigh != null }
                ?: radios.firstOrNull()
            val reference = preferred?.let { it.downlinkLow ?: it.downlinkHigh ?: it.uplinkLow ?: it.uplinkHigh }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isMissing = false,
                    pass = pass,
                    samples = payload.first,
                    metrics = payload.second,
                    elements = payload.third,
                    radios = radios,
                    selectedRadioUuid = preferred?.uuid,
                    plan = if (preferred != null) DopplerPlan.forTransponder(preferred, payload.first) else emptyList(),
                    referenceHz = reference,
                    statistics = PassStatistics.from(passes.filter { item -> item.catNum == catNum })
                )
            }
        }
    }

    companion object {
        fun factory(container: IMainContainer, catNum: Int, aosTime: Long) = viewModelFactory {
            initializer {
                PassAnalysisViewModel(
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo,
                    catNum = catNum,
                    aosTime = aosTime
                )
            }
        }
    }
}
