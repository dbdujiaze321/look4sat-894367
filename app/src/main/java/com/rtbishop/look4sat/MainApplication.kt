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
package com.rtbishop.look4sat

import android.app.Application
import com.rtbishop.look4sat.alarm.PassAlarmScheduler
import com.rtbishop.look4sat.core.data.injection.MainContainer
import com.rtbishop.look4sat.core.domain.analysis.StalenessCalibration
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.IMainContainer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainApplication : Application(), IContainerProvider {

    private lateinit var container: IMainContainer

    override fun getMainContainer(): IMainContainer = container

    override fun onCreate() {
        super.onCreate()
        container = MainContainer(this)
        PassAlarmScheduler.ensureChannel(this)
        // trigger automatic update every 48 hours
        container.appScope.launch { checkAutoUpdate() }
        // load satellite data on every app start
        container.appScope.launch { container.satelliteRepo.initRepository() }
        watchPassAlarms()
    }

    /**
     * Keeps exactly one pass reminder armed.
     *
     * Both inputs matter: the pass list changes when the selection or the filter changes, and the
     * settings change when the user toggles the reminder or picks a different lead time. Collecting
     * them together means the alarm is always rebuilt from the current state instead of drifting.
     */
    private fun watchPassAlarms() {
        container.appScope.launch {
            combine(
                container.satelliteRepo.passes,
                container.settingsRepo.otherSettings
            ) { passes, settings -> passes to settings }
                .collect { (passes, settings) ->
                    PassAlarmScheduler.scheduleNext(
                        context = this@MainApplication,
                        passes = passes,
                        leadMinutes = settings.passAlarmLeadMinutes,
                        enabled = settings.passAlarmEnabled
                    )
                }
        }
    }

    /**
     * Two independent reasons to refresh.
     *
     * The routine timer keeps the catalogue current. The accuracy budget is the promise this app
     * makes about pass times: the staleness model knows what a given element age costs, and when it
     * costs more than ten seconds the data is refreshed even if the timer has not fired — for
     * instance after a long period without network.
     */
    private suspend fun checkAutoUpdate(timeNow: Long = System.currentTimeMillis()) {
        val settings = container.settingsRepo.otherSettings.value
        if (settings.stateOfAutoUpdate) {
            val timeDelta = timeNow - container.settingsRepo.databaseState.value.updateTimestamp
            val ageDays = timeDelta.toDouble() / 86_400_000.0
            val calibration = StalenessCalibration.decode(settings.stalenessJson)
            val overBudget = calibration.exceedsBudget(ageDays)
            if (timeDelta > 172_800_000L || overBudget) { // 48 hours in ms
                val sdf = SimpleDateFormat("d MMM yyyy - HH:mm:ss", Locale.getDefault())
                println("Started periodic data update on ${sdf.format(Date())}")
                container.databaseRepo.updateFromRemote()
            }
        }
    }
}
