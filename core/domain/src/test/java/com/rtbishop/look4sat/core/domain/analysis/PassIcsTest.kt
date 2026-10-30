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
package com.rtbishop.look4sat.core.domain.analysis

import com.rtbishop.look4sat.core.domain.predict.NearEarthObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassIcsTest {

    private val aos = 1_767_225_600_000L // 2026-01-01T00:00:00Z

    private fun pass(name: String = "ISS (ZARYA)", catNum: Int = 25544, offsetMinutes: Long = 0): OrbitalPass {
        val data = OrbitalData(
            name = name,
            epoch = 26001.0,
            meanmo = 15.5,
            eccn = 0.001,
            incl = 51.6,
            raan = 0.0,
            argper = 0.0,
            meanan = 0.0,
            catnum = catNum,
            bstar = 0.0001
        )
        val start = aos + offsetMinutes * 60_000L
        return OrbitalPass(
            aosTime = start,
            aosAzimuth = 210.0,
            losTime = start + 600_000L,
            losAzimuth = 30.0,
            maxElevation = 45.0,
            orbitalObject = NearEarthObject(data)
        )
    }

    @Test
    fun `file is wrapped in a calendar and uses CRLF`() {
        val ics = PassIcs.build(listOf(pass()), reminderMinutes = 10, generatedAt = aos)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertFalse("bare LF would be rejected by strict parsers", Regex("(?<!\r)\n").containsMatchIn(ics))
        assertTrue(ics.contains("VERSION:2.0\r\n"))
        assertTrue(ics.contains("PRODID:-//Look4Sat//Pass calendar//EN\r\n"))
    }

    @Test
    fun `one VEVENT per pass with UTC start and end`() {
        val ics = PassIcs.build(listOf(pass(), pass(catNum = 43017, offsetMinutes = 90)))
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertEquals(2, Regex("END:VEVENT").findAll(ics).count())
        assertTrue(ics.contains("DTSTART:20260101T000000Z"))
        assertTrue(ics.contains("DTEND:20260101T001000Z"))
        assertTrue(ics.contains("DTSTART:20260101T013000Z"))
    }

    @Test
    fun `uid is stable per satellite and pass`() {
        val ics = PassIcs.build(listOf(pass()))
        assertTrue(ics.contains("UID:25544-$aos@look4sat"))
    }

    @Test
    fun `summary and description carry the numbers worth reading on a lock screen`() {
        val ics = PassIcs.build(listOf(pass()))
        assertTrue(ics.contains("SUMMARY:ISS (ZARYA) 45°"))
        val description = ics.lines().first { it.startsWith("DESCRIPTION:Max elevation") }
        assertTrue(description.contains("45.0°"))
        assertTrue(description.contains("visible for 10 min"))
        assertTrue(description.contains("Azimuth at AOS 210°"))
        assertTrue(description.contains("at LOS 30°"))
    }

    @Test
    fun `reminder becomes a negative duration trigger`() {
        val ics = PassIcs.build(listOf(pass()), reminderMinutes = 15)
        assertTrue(ics.contains("BEGIN:VALARM"))
        assertTrue(ics.contains("TRIGGER:-PT15M"))
    }

    @Test
    fun `zero reminder emits no alarms at all`() {
        val ics = PassIcs.build(listOf(pass()), reminderMinutes = 0)
        assertFalse(ics.contains("BEGIN:VALARM"))
    }

    @Test
    fun `reminder is clamped to a sane range`() {
        // 999 minutes would otherwise become a sixteen hour reminder.
        assertTrue(PassIcs.build(listOf(pass()), reminderMinutes = 999).contains("TRIGGER:-PT120M"))
        assertTrue(PassIcs.build(listOf(pass()), reminderMinutes = -5).contains("BEGIN:VEVENT"))
    }

    @Test
    fun `commas and semicolons in a name are escaped, not left structural`() {
        val ics = PassIcs.build(listOf(pass("CSS, (TIANHE); TEST")))
        assertTrue(ics.contains("SUMMARY:CSS\\, (TIANHE)\\; TEST 45°"))
        assertFalse(ics.contains("SUMMARY:CSS, (TIANHE); TEST"))
    }

    @Test
    fun `an empty list still produces a valid calendar`() {
        val ics = PassIcs.build(emptyList())
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertFalse(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
    }

    @Test
    fun `file name is dated from the earliest pass in the list`() {
        val name = PassIcs.fileName(listOf(pass(offsetMinutes = 90), pass(offsetMinutes = 0)))
        assertEquals("look4sat-passes-20260101.ics", name)
        assertEquals("look4sat-passes.ics", PassIcs.fileName(emptyList()))
    }
}
