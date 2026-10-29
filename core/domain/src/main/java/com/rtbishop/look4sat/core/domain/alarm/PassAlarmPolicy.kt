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
package com.rtbishop.look4sat.core.domain.alarm

import com.rtbishop.look4sat.core.domain.predict.OrbitalPass

/** The pass a reminder should be armed for, and when the alarm has to fire. */
data class AlarmTarget(
    val catNum: Int,
    val name: String,
    val aosTime: Long,
    val maxElevation: Double,
    val triggerAt: Long
)

/**
 * Decides which pass to remind the user about.
 *
 * Kept in the domain module, free of Android types, so the selection rules can be unit tested: the
 * alarms themselves cannot be verified without a device, but "which pass and at what time" can.
 */
object PassAlarmPolicy {

    const val DEFAULT_LEAD_MINUTES = 10
    const val MAX_LEAD_MINUTES = 120

    /**
     * Slack that keeps an alarm which just fired from being re-armed for the same pass when the
     * receiver immediately asks for the following one.
     */
    const val REARM_SLACK_MS = 30_000L

    /**
     * Earliest pass whose reminder time is still in the future. Decayed passes are ignored: their
     * elements are stale enough that the predicted AOS is not worth waking the user for.
     */
    fun nextTarget(
        passes: List<OrbitalPass>,
        leadMinutes: Int,
        now: Long,
        slack: Long = REARM_SLACK_MS
    ): AlarmTarget? {
        val leadMs = leadMinutes.coerceIn(0, MAX_LEAD_MINUTES) * 60_000L
        return passes.asSequence()
            .filter { pass -> !pass.hasDecayed && pass.aosTime > now }
            .map { pass ->
                AlarmTarget(
                    catNum = pass.catNum,
                    name = pass.name,
                    aosTime = pass.aosTime,
                    maxElevation = pass.maxElevation,
                    triggerAt = pass.aosTime - leadMs
                )
            }
            .filter { target -> target.triggerAt > now + slack }
            .minByOrNull { target -> target.triggerAt }
    }
}
