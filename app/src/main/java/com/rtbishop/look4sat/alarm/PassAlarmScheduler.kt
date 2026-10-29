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

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.rtbishop.look4sat.MainActivity
import com.rtbishop.look4sat.core.domain.alarm.AlarmTarget
import com.rtbishop.look4sat.core.domain.alarm.PassAlarmPolicy
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.presentation.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Schedules one alarm for the next pass and posts the reminder when it fires.
 *
 * Only a single alarm is ever pending: the pass list is recalculated often and a queue of stale
 * alarms would fire for passes that no longer exist. Every reschedule replaces the previous one.
 *
 * `setAndAllowWhileIdle` is used instead of the exact variant on purpose: a reminder that arrives a
 * minute late is harmless, while exact alarms need the SCHEDULE_EXACT_ALARM permission on
 * Android 12+, which users routinely deny and which would silently disable the whole feature.
 */
object PassAlarmScheduler {

    const val CHANNEL_ID = "pass_alarms"
    private const val REQUEST_CODE = 4101
    private const val NOTIFICATION_ID = 4101

    const val EXTRA_NAME = "pass_name"
    const val EXTRA_CATNUM = "pass_catnum"
    const val EXTRA_AOS = "pass_aos"
    const val EXTRA_MAX_ELEVATION = "pass_max_elevation"

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.pass_alarm_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.pass_alarm_channel_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Replaces any pending reminder with one for the earliest usable pass, or clears it when
     * reminders are disabled or nothing is due. [now] is injectable for tests.
     */
    fun scheduleNext(
        context: Context,
        passes: List<OrbitalPass>,
        leadMinutes: Int,
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val target = if (enabled) PassAlarmPolicy.nextTarget(passes, leadMinutes, now) else null
        if (target == null) {
            alarmManager.cancel(pendingIntent(context, null))
            return
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            target.triggerAt,
            pendingIntent(context, target)
        )
    }

    private fun pendingIntent(context: Context, target: AlarmTarget?): PendingIntent {
        val intent = Intent(context, PassAlarmReceiver::class.java).apply {
            action = PassAlarmReceiver.ACTION_PASS_ALARM
            target?.let {
                putExtra(EXTRA_NAME, it.name)
                putExtra(EXTRA_CATNUM, it.catNum)
                putExtra(EXTRA_AOS, it.aosTime)
                putExtra(EXTRA_MAX_ELEVATION, it.maxElevation)
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /**
     * Posts the reminder. Takes the fields rather than an [OrbitalPass] because the pass may have
     * left the list between scheduling and firing, while the extras are still valid.
     */
    fun showReminder(
        context: Context,
        name: String,
        maxElevation: Double,
        aosTime: Long,
        leadMinutes: Int
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = context.getString(R.string.pass_alarm_title, name, leadMinutes.toString())
        val text = context.getString(
            R.string.pass_alarm_text,
            maxElevation.roundToInt().toString(),
            timeFormat.format(Date(aosTime))
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_satellite)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission revoked between scheduling and firing: nothing to do.
        }
    }
}
