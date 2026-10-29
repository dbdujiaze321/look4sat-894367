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
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dashboard state. Deliberately derived from the repository's existing pass list instead of
 * running a second prediction: recalculating passes twice would double the CPU cost for the same
 * numbers and could disagree with the pass page.
 */
data class WorkbenchState(
    val topPasses: List<OrbitalPass> = emptyList(),
    val nextPass: OrbitalPass? = null,
    val countdown: String = "--:--:--",
    val isCalculating: Boolean = true,
    val satelliteCount: Int = 0,
    val dataAgeDays: Int? = null,
    val daysUntilStale: Int? = null,
    val highPrecisionDaysLeft: Double? = null,
    val isRefreshing: Boolean = false,
    val message: String? = null
)

class WorkbenchViewModel(
    private val satelliteRepo: ISatelliteRepo,
    private val settingsRepo: ISettingsRepo,
    private val databaseRepo: IDatabaseRepo
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkbenchState())
    val uiState: StateFlow<WorkbenchState> = _uiState

    private var passes: List<OrbitalPass> = emptyList()
    private var databaseState: DatabaseState? = null

    init {
        viewModelScope.launch {
            satelliteRepo.passes.collect {
                passes = it
                refreshDerived()
            }
        }
        viewModelScope.launch {
            satelliteRepo.isCalculating.collect { calculating ->
                _uiState.update { it.copy(isCalculating = calculating) }
            }
        }
        viewModelScope.launch {
            satelliteRepo.satellites.collect { list ->
                _uiState.update { it.copy(satelliteCount = list.size) }
            }
        }
        viewModelScope.launch {
            settingsRepo.databaseState.collect {
                databaseState = it
                refreshDerived()
            }
        }
        // One ticker for the countdown; everything else is event driven.
        viewModelScope.launch {
            while (isActive) {
                refreshDerived()
                delay(1_000)
            }
        }
    }

    fun refreshTleData() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, message = null) }
            val result = runCatching { databaseRepo.updateFromRemote() }
            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    message = if (result.isSuccess) null else "refresh-failed"
                )
            }
            if (result.isSuccess) {
                // Reloading the repository recomputes passes with whatever filter the user has
                // configured; no need to duplicate the filter plumbing here.
                satelliteRepo.initRepository()
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private fun refreshDerived() {
        val now = System.currentTimeMillis()
        val upcoming = passes.filter { it.losTime > now }
        val next = upcoming.minByOrNull { it.aosTime }
        val next24h = passes.filter { it.aosTime in now..(now + DAY_MS) && it.losTime > now }
        val top = next24h
            .sortedWith(
                compareByDescending<OrbitalPass> { it.maxElevation }
                    .thenByDescending { it.losTime - it.aosTime }
            )
            .take(3)

        val updateTimestamp = databaseState?.updateTimestamp ?: 0L
        val hasData = updateTimestamp > 0L
        val ageDays = if (hasData) ((now - updateTimestamp) / DAY_MS).toInt() else null
        val highPrecisionLeft = if (hasData) {
            (HIGH_PRECISION_WINDOW_DAYS - (now - updateTimestamp).toDouble() / DAY_MS).coerceAtLeast(0.0)
        } else {
            null
        }
        val untilStale = ageDays?.let { (STALE_AFTER_DAYS - it).coerceAtLeast(0) }

        _uiState.update {
            it.copy(
                topPasses = top,
                nextPass = next,
                countdown = next?.let { pass -> formatCountdown(pass.aosTime - now) } ?: "--:--:--",
                dataAgeDays = ageDays,
                daysUntilStale = untilStale,
                highPrecisionDaysLeft = highPrecisionLeft
            )
        }
    }

    companion object {
        private const val DAY_MS = 86_400_000L

        /** Segmented accuracy policy: the validated window is the first two days after a TLE epoch. */
        private const val HIGH_PRECISION_WINDOW_DAYS = 2.0

        /** Mirrors SatEntry.STALE_AFTER_DAYS: past this the records are only flagged, not deleted. */
        private const val STALE_AFTER_DAYS = 30

        fun formatCountdown(millis: Long): String {
            val total = (millis / 1000).coerceAtLeast(0)
            val days = total / 86_400
            val hours = (total % 86_400) / 3_600
            val minutes = (total % 3_600) / 60
            val seconds = total % 60
            return if (days > 0) {
                String.format(Locale.US, "%dd %02d:%02d:%02d", days, hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            }
        }

        fun factory(container: IMainContainer) = viewModelFactory {
            initializer {
                WorkbenchViewModel(
                    satelliteRepo = container.satelliteRepo,
                    settingsRepo = container.settingsRepo,
                    databaseRepo = container.databaseRepo
                )
            }
        }
    }
}
