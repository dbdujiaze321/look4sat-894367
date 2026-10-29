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
import kotlinx.coroutines.launch

/**
 * Re-arms the pass reminder after a reboot or an app update.
 *
 * Pending alarms do not survive either event, so without this the feature would silently stop
 * working after the first restart. The repository has to be reloaded first: passes are calculated
 * from the database and are empty in a cold process.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val provider = context.applicationContext as? IContainerProvider ?: return
        val container = provider.getMainContainer()
        val settings = container.settingsRepo.otherSettings.value
        if (!settings.passAlarmEnabled) return
        val pendingResult = goAsync()
        container.appScope.launch {
            try {
                container.satelliteRepo.initRepository()
                PassAlarmScheduler.scheduleNext(
                    context = context,
                    passes = container.satelliteRepo.passes.value,
                    leadMinutes = settings.passAlarmLeadMinutes,
                    enabled = settings.passAlarmEnabled
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
