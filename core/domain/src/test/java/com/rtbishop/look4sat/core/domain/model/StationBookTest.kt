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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationBookTest {

    private fun station(id: String, name: String = "Home", lat: Double = 26.07, lon: Double = 119.30) =
        SavedStation(id = id, name = name, latitude = lat, longitude = lon, altitudeMeters = 20.0)

    @Test
    fun `garbage in preferences degrades to an empty book`() {
        assertEquals(StationBook.EMPTY, StationBook.decode(null))
        assertEquals(StationBook.EMPTY, StationBook.decode(""))
        assertEquals(StationBook.EMPTY, StationBook.decode("not json"))
        assertEquals(StationBook.EMPTY, StationBook.decode("{\"stations\":[{\"id\":1}]}"))
    }

    @Test
    fun `round trip preserves stations and the active id`() {
        val book = StationBook().addOrReplace(station("a")).addOrReplace(station("b", "Club"))
            .select("b")
        val decoded = StationBook.decode(book.encode())
        assertEquals(2, decoded.stations.size)
        assertEquals("b", decoded.activeId)
        assertEquals("Club", decoded.active()?.name)
    }

    @Test
    fun `the first station added becomes active`() {
        val book = StationBook().addOrReplace(station("a"))
        assertEquals("a", book.activeId)
    }

    @Test
    fun `adding a second station does not steal the selection`() {
        val book = StationBook().addOrReplace(station("a")).addOrReplace(station("b"))
        assertEquals("a", book.activeId)
    }

    @Test
    fun `replacing by id does not duplicate the row`() {
        val book = StationBook().addOrReplace(station("a"))
            .addOrReplace(station("a", name = "Renamed"))
        assertEquals(1, book.stations.size)
        assertEquals("Renamed", book.stations.first().name)
    }

    @Test
    fun `removing the active station clears the selection instead of dangling`() {
        val book = StationBook().addOrReplace(station("a")).addOrReplace(station("b"))
        val afterRemove = book.remove("a")
        assertEquals(1, afterRemove.stations.size)
        assertNull(afterRemove.activeId)
        assertNull(afterRemove.active())
    }

    @Test
    fun `removing an inactive station keeps the selection`() {
        val book = StationBook().addOrReplace(station("a")).addOrReplace(station("b")).select("b")
        assertEquals("b", book.remove("a").activeId)
    }

    @Test
    fun `selecting an unknown id changes nothing`() {
        val book = StationBook().addOrReplace(station("a"))
        assertEquals(book, book.select("missing"))
    }

    @Test
    fun `locator is derived from the coordinates`() {
        // Fuzhou: 26.07 N, 119.30 E sits in grid square OL96 (field O/L, square 9/6).
        assertTrue(station("a").locator.startsWith("OL96"))
        assertEquals(6, station("a").locator.length)
    }

    @Test
    fun `validation rejects impossible values`() {
        assertNull(StationBook.validate("Home", 26.07, 119.30, 20.0))
        assertEquals(StationError.NAME_BLANK, StationBook.validate("  ", 0.0, 0.0, 0.0))
        assertEquals(StationError.NAME_TOO_LONG, StationBook.validate("x".repeat(25), 0.0, 0.0, 0.0))
        assertEquals(StationError.LATITUDE_RANGE, StationBook.validate("Home", 91.0, 0.0, 0.0))
        assertEquals(StationError.LATITUDE_RANGE, StationBook.validate("Home", Double.NaN, 0.0, 0.0))
        assertEquals(StationError.LONGITUDE_RANGE, StationBook.validate("Home", 0.0, -181.0, 0.0))
        assertEquals(StationError.ALTITUDE_RANGE, StationBook.validate("Home", 0.0, 0.0, 20_000.0))
    }

    @Test
    fun `renaming keeps the id stable so the selection survives`() {
        val book = StationBook().addOrReplace(station("a")).rename("a", "Rover")
        assertEquals("Rover", book.active()?.name)
        assertEquals("a", book.activeId)
    }
}
