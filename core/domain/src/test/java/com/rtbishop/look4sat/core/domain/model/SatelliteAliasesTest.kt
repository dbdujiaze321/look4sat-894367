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

class SatelliteAliasesTest {

    @Test
    fun `tiangong is reachable by its chinese name`() {
        val aliases = SatelliteAliases.chineseAliases("CSS (TIANHE)")
        assertTrue(aliases, aliases.contains("天和"))
        assertTrue(aliases, aliases.contains("空间站"))
    }

    @Test
    fun `beidou is reachable by its chinese name`() {
        assertTrue(SatelliteAliases.chineseAliases("BEIDOU-3 M1").contains("北斗"))
    }

    @Test
    fun `weather and remote sensing programs are covered`() {
        assertTrue(SatelliteAliases.chineseAliases("FENGYUN 3D").contains("风云"))
        assertTrue(SatelliteAliases.chineseAliases("GAOFEN 3").contains("高分"))
        assertTrue(SatelliteAliases.chineseAliases("YAOGAN-35").contains("遥感"))
    }

    @Test
    fun `short codes match too`() {
        assertTrue(SatelliteAliases.chineseAliases("FY-4A").contains("风云"))
        assertTrue(SatelliteAliases.chineseAliases("GF-1").contains("高分"))
    }

    @Test
    fun `unrelated satellites get no alias text`() {
        assertEquals("", SatelliteAliases.chineseAliases("ISS (ZARYA)"))
        assertEquals("", SatelliteAliases.chineseAliases(""))
    }

    @Test
    fun `aliases are space separated so the tokenizer treats them separately`() {
        val aliases = SatelliteAliases.chineseAliases("TIANGONG")
        assertTrue(aliases, aliases.endsWith("天宫 空间站 tianhe"))
    }
}
