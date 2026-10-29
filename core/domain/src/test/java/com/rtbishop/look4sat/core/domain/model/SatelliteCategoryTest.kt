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
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteCategoryTest {

    @Test
    fun `manned stations are recognised`() {
        assertEquals(SatelliteCategory.SPACE_STATION, SatelliteCategory.classify("ISS (ZARYA)"))
        assertEquals(SatelliteCategory.SPACE_STATION, SatelliteCategory.classify("CSS (TIANHE)"))
        assertEquals(SatelliteCategory.SPACE_STATION, SatelliteCategory.classify("TIANGONG"))
    }

    @Test
    fun `amateur satellites win over other matches`() {
        // "AMSAT-OSCAR 7" also contains a science sounding word; amateur must win.
        assertEquals(SatelliteCategory.AMATEUR, SatelliteCategory.classify("AMSAT-OSCAR 7"))
        assertEquals(SatelliteCategory.AMATEUR, SatelliteCategory.classify("AO-7"))
        assertEquals(SatelliteCategory.AMATEUR, SatelliteCategory.classify("SO-50"))
        assertEquals(SatelliteCategory.AMATEUR, SatelliteCategory.classify("RS-44"))
        assertEquals(SatelliteCategory.AMATEUR, SatelliteCategory.classify("FUNcube-1"))
    }

    @Test
    fun `two letter prefixes need a dash and a digit`() {
        // Words that merely start with two letters and a dash are not amateur satellites.
        assertEquals(SatelliteCategory.OTHER, SatelliteCategory.classify("POES-19 DEBRIS"))
        assertEquals(SatelliteCategory.OTHER, SatelliteCategory.classify("AB-CD OBJECT"))
    }

    @Test
    fun `weather fleet is grouped together`() {
        assertEquals(SatelliteCategory.WEATHER, SatelliteCategory.classify("NOAA 19"))
        assertEquals(SatelliteCategory.WEATHER, SatelliteCategory.classify("METEOR M2-3"))
        assertEquals(SatelliteCategory.WEATHER, SatelliteCategory.classify("FENGYUN 3D"))
        assertEquals(SatelliteCategory.WEATHER, SatelliteCategory.classify("GOES 16"))
        assertEquals(SatelliteCategory.WEATHER, SatelliteCategory.classify("METOP-B"))
    }

    @Test
    fun `navigation constellations are grouped together`() {
        assertEquals(SatelliteCategory.NAVIGATION, SatelliteCategory.classify("NAVSTAR 43 (USA 132)"))
        assertEquals(SatelliteCategory.NAVIGATION, SatelliteCategory.classify("GPS BIIR-2"))
        assertEquals(SatelliteCategory.NAVIGATION, SatelliteCategory.classify("BEIDOU-3 M1"))
        assertEquals(SatelliteCategory.NAVIGATION, SatelliteCategory.classify("GLONASS-M 758"))
        assertEquals(SatelliteCategory.NAVIGATION, SatelliteCategory.classify("GALILEO 5"))
    }

    @Test
    fun `science and communication are separated`() {
        assertEquals(SatelliteCategory.SCIENCE, SatelliteCategory.classify("HUBBLE SPACE TELESCOPE"))
        assertEquals(SatelliteCategory.SCIENCE, SatelliteCategory.classify("SWIFT"))
        assertEquals(SatelliteCategory.COMMUNICATION, SatelliteCategory.classify("STARLINK-1234"))
        assertEquals(SatelliteCategory.COMMUNICATION, SatelliteCategory.classify("INTELSAT 10-02"))
        assertEquals(SatelliteCategory.COMMUNICATION, SatelliteCategory.classify("IRIDIUM 106"))
    }

    @Test
    fun `unknown names fall back to other instead of disappearing`() {
        assertEquals(SatelliteCategory.OTHER, SatelliteCategory.classify("OBJECT A"))
        assertEquals(SatelliteCategory.OTHER, SatelliteCategory.classify(""))
    }

    @Test
    fun `display order starts with stations and ends with other`() {
        val ordered = SatelliteCategory.ordered()
        assertEquals(SatelliteCategory.SPACE_STATION, ordered.first())
        assertEquals(SatelliteCategory.OTHER, ordered.last())
        assertTrue(ordered.size == SatelliteCategory.entries.size)
    }
}
