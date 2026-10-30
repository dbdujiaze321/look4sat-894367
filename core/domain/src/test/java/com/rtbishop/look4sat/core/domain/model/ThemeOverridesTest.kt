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

class ThemeOverridesTest {

    @Test
    fun `round trip keeps every field`() {
        val theme = ThemeOverrides(
            primary = "#FF8800",
            background = "#101010",
            radarTrack = "#00FF00",
            cornerRadiusDp = 8
        )
        assertEquals(theme, ThemeOverrides.parse(ThemeOverrides.encode(theme)))
    }

    @Test
    fun `unknown keys from a newer version are ignored`() {
        val json = """{"version":2,"primary":"#123456","someFutureToken":"#ABCDEF"}"""
        val parsed = ThemeOverrides.parse(json)
        assertEquals("#123456", parsed.primary)
        assertNull(parsed.background)
    }

    @Test
    fun `garbage degrades to no overrides instead of throwing`() {
        assertEquals(ThemeOverrides.EMPTY, ThemeOverrides.parse("not json at all"))
        assertEquals(ThemeOverrides.EMPTY, ThemeOverrides.parse(""))
        assertEquals(ThemeOverrides.EMPTY, ThemeOverrides.parse(null))
        assertEquals(ThemeOverrides.EMPTY, ThemeOverrides.parse("{}"))
    }

    @Test
    fun `corner radius is clamped into the supported range`() {
        assertEquals(0, ThemeOverrides(cornerRadiusDp = -40).cornerRadius())
        assertEquals(ThemeOverrides.MAX_RADIUS_DP, ThemeOverrides(cornerRadiusDp = 400).cornerRadius())
        assertNull(ThemeOverrides(cornerRadiusDp = null).cornerRadius())
    }

    @Test
    fun `empty detection drives the fast path in the theme`() {
        assertTrue(ThemeOverrides().isEmpty())
        assertTrue(!ThemeOverrides(primary = "#FFFFFF").isEmpty())
    }
}

class ThemeColorTest {

    @Test
    fun `six digits become opaque`() {
        assertEquals(0xFFFFE082.toInt(), ThemeColor.parse("#FFE082"))
        assertEquals(0xFFFFE082.toInt(), ThemeColor.parse("ffe082"))
    }

    @Test
    fun `eight digits keep their alpha`() {
        assertEquals(0x80FFE082.toInt(), ThemeColor.parse("#80FFE082"))
    }

    @Test
    fun `invalid input returns null instead of throwing`() {
        assertNull(ThemeColor.parse("#GGGGGG"))
        assertNull(ThemeColor.parse("#12345"))
        assertNull(ThemeColor.parse(""))
        assertNull(ThemeColor.parse(null))
    }

    @Test
    fun `format produces eight digits`() {
        assertEquals("#FFFFE082", ThemeColor.format(0xFFFFE082.toInt()))
    }
}
