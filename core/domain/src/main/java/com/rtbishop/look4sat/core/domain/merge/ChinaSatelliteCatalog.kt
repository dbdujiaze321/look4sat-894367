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
package com.rtbishop.look4sat.core.domain.merge

/**
 * Recognises Chinese satellites in the merged catalogue.
 *
 * Purpose is regression detection, not classification: the app must never make a satellite
 * unreachable because its name arrived in a different encoding or a different transliteration
 * (TIANGONG vs 天宫, BEIDOU vs 北斗). Both spellings are matched, and the check runs against the
 * final merged catalogue rather than a single feed, so a source that renames a satellite cannot
 * silently drop it from the searchable list.
 */
object ChinaSatelliteCatalog {

    /**
     * Full program names. Word boundaries keep short acronyms such as "CSS" from matching
     * unrelated names like "CSSWE".
     */
    private val programNames = Regex(
        "\\b(TIANGONG|TIANHE|WENTIAN|MENGTIAN|TIANZHOU|SHENZHOU|BEIDOU|BDS|COMPASS" +
            "|GAOFEN|FENGYUN|HAIYANG|YAOGAN|TIANLIAN|CHINASAT|ZHONGXING|APSTAR|ASIASAT" +
            "|FENGHUO|JILIN|HULIANWANG|ZHUHAI|SHIYAN|TANSUO|MACAU SCIENCE|LUOJIA)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Short program codes: GF-1, FY-4A, YG-35, SY-2, TX-2, JL-3, BDS-3 ... */
    private val shortCodes = Regex(
        "\\b(GF|FY|YG|SY|TX|JL|BD|HY|TY|LT|ZTZ)-?\\s?\\d",
        RegexOption.IGNORE_CASE
    )

    /** Native-script names, in case a feed ever publishes them untransliterated. */
    private val nativeNames = Regex("(天宫|天和|问天|梦天|神舟|天舟|北斗|高分|风云|遥感|天链|中星|亚太|吉林|鸿雁)")

    fun isChineseSatellite(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return programNames.containsMatchIn(trimmed) ||
            shortCodes.containsMatchIn(trimmed) ||
            nativeNames.containsMatchIn(trimmed)
    }

    /** Counts how many of [names] are recognised; used by the regression test and the sync log. */
    fun countIn(names: Collection<String>): Int = names.count(::isChineseSatellite)
}
