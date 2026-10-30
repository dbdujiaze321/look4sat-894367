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
package com.rtbishop.look4sat.core.domain.model

import com.rtbishop.look4sat.core.domain.utility.positionToQth
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** One named observing site. Altitude is metres above the WGS-84 ellipsoid, as GeoPos expects. */
@Serializable
data class SavedStation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0
) {
    /** Six character Maidenhead locator, or an empty string when the coordinates are impossible. */
    val locator: String get() = positionToQth(latitude, longitude).orEmpty()
}

enum class StationError {
    NAME_BLANK,
    NAME_TOO_LONG,
    LATITUDE_RANGE,
    LONGITUDE_RANGE,
    ALTITUDE_RANGE
}

/**
 * The set of sites the operator uses, plus which one is current.
 *
 * A single station position is enough for a fixed home shack, but not for the cases this app is
 * actually used in: a rover moving between grid squares, or someone comparing their home antenna
 * with a club station. Keeping the named sites means switching is one tap instead of retyping
 * coordinates, and it makes the stored position auditable — you can see which site the predictions
 * were made for.
 *
 * The whole book is one JSON string in preferences, for the same reason the theme overrides are: the
 * schema can gain fields later without a migration step, and an unreadable value degrades to an empty
 * book instead of crashing the settings screen.
 */
@Serializable
data class StationBook(
    val stations: List<SavedStation> = emptyList(),
    val activeId: String? = null
) {
    fun active(): SavedStation? = activeId?.let { id -> stations.firstOrNull { it.id == id } }

    /** Replaces a station with the same id, otherwise appends. */
    fun addOrReplace(station: SavedStation): StationBook {
        val existing = stations.indexOfFirst { it.id == station.id }
        val updated = if (existing >= 0) {
            stations.toMutableList().also { it[existing] = station }
        } else {
            stations + station
        }
        return copy(stations = updated, activeId = activeId ?: station.id)
    }

    fun remove(id: String): StationBook = copy(
        stations = stations.filterNot { it.id == id },
        // Removing the current site must not leave a dangling id: the UI would show no selection
        // while still predicting from the old coordinates.
        activeId = if (activeId == id) null else activeId
    )

    fun rename(id: String, name: String): StationBook = copy(
        stations = stations.map { if (it.id == id) it.copy(name = name) else it }
    )

    /** Selecting an unknown id is a no-op rather than a crash: stored books can be edited by hand. */
    fun select(id: String): StationBook =
        if (stations.any { it.id == id }) copy(activeId = id) else this

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val EMPTY = StationBook()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String?): StationBook {
            if (raw.isNullOrBlank()) return EMPTY
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(EMPTY)
        }

        fun newId(): String = UUID.randomUUID().toString()

        /** Null when the values are acceptable, otherwise the first problem found. */
        fun validate(name: String, latitude: Double, longitude: Double, altitudeMeters: Double): StationError? =
            when {
                name.isBlank() -> StationError.NAME_BLANK
                name.trim().length > 24 -> StationError.NAME_TOO_LONG
                latitude.isNaN() || latitude < -90.0 || latitude > 90.0 -> StationError.LATITUDE_RANGE
                longitude.isNaN() || longitude < -180.0 || longitude > 180.0 -> StationError.LONGITUDE_RANGE
                altitudeMeters.isNaN() || altitudeMeters < -500.0 || altitudeMeters > 10_000.0 ->
                    StationError.ALTITUDE_RANGE
                else -> null
            }
    }
}
