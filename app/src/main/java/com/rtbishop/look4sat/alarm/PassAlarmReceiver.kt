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
package com.rtbishop.look4sat.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider

/**
 * Posts the reminder for the pass that was armed, then arms the following one.
 *
 * The next pass is taken from the repository rather than from the alarm extras: the list may have
 * been recalculated since this alarm was scheduled, and re-arming from stale data would either fire
 * for a pass that no longer exists or skip the one that replaced it.
 */
class PassAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PASS_ALARM) return
        val provider = context.applicationContext as? IContainerProvider ?: return
        val container = provider.getMainContainer()
        val settings = container.settingsRepo.otherSettings.value
        if (!settings.passAlarmEnabled) return
        val name = intent.getStringExtra(PassAlarmScheduler.EXTRA_NAME)
        val aosTime = intent.getLongExtra(PassAlarmScheduler.EXTRA_AOS, 0L)
        val maxElevation = intent.getDoubleExtra(PassAlarmScheduler.EXTRA_MAX_ELEVATION, 0.0)
        if (name != null && aosTime > 0L) {
            PassAlarmScheduler.showReminder(
                context = context,
                name = name,
                maxElevation = maxElevation,
                aosTime = aosTime,
                leadMinutes = settings.passAlarmLeadMinutes
            )
        }
        PassAlarmScheduler.scheduleNext(
            context = context,
            passes = container.satelliteRepo.passes.value,
            leadMinutes = settings.passAlarmLeadMinutes,
            enabled = settings.passAlarmEnabled
        )
    }

    companion object {
        const val ACTION_PASS_ALARM = "com.rtbishop.look4sat.PASS_ALARM"
    }
}
