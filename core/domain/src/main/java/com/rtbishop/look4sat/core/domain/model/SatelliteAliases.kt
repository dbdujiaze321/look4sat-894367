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

/**
 * Extra searchable text for satellites whose catalogue name is English-only.
 *
 * Celestrak and SatNOGS publish Latin transliterations (TIANGONG, BEIDOU, FENGYUN), so a Chinese
 * user typing 天宫 or 北斗 finds nothing even though the satellite is in the list. Appending the
 * native names to the searchable text fixes that in one place, without touching the stored names
 * and without a transliteration table for every language.
 *
 * Cost is deliberately kept trivial: a handful of `contains` checks per item, no regex and no
 * allocation beyond the returned string, because this runs for every visible item on every
 * keystroke.
 */
object SatelliteAliases {

    /** Catalogue token to the terms a user is likely to type. */
    private val groups: List<Pair<String, List<String>>> = listOf(
        "TIANGONG" to listOf("天宫", "空间站", "tianhe"),
        "TIANHE" to listOf("天和", "空间站"),
        "WENTIAN" to listOf("问天"),
        "MENGTIAN" to listOf("梦天"),
        "SHENZHOU" to listOf("神舟"),
        "TIANZHOU" to listOf("天舟"),
        "BEIDOU" to listOf("北斗", "bds"),
        "BDS" to listOf("北斗"),
        "GAOFEN" to listOf("高分"),
        "GF-" to listOf("高分"),
        "FENGYUN" to listOf("风云", "fy"),
        "FY-" to listOf("风云"),
        "YAOGAN" to listOf("遥感"),
        "YG-" to listOf("遥感"),
        "HAIYANG" to listOf("海洋"),
        "HY-" to listOf("海洋"),
        "TIANLIAN" to listOf("天链"),
        "CHINASAT" to listOf("中星"),
        "APSTAR" to listOf("亚太"),
        "ASIASAT" to listOf("亚洲"),
        "JILIN" to listOf("吉林"),
        "LUOJIA" to listOf("珞珈"),
        "SHIYAN" to listOf("试验"),
        "TANSUO" to listOf("探索"),
        "FENGHUO" to listOf("天问"),
        "HULIANWANG" to listOf("鸿雁", "互联网")
    )

    /**
     * Native names matching [name], separated by spaces so the tokenizer treats each one as its
     * own term. Returns an empty string when nothing matches, which the caller can append blindly.
     */
    fun chineseAliases(name: String): String {
        if (name.isEmpty()) return ""
        val upper = name.uppercase()
        val builder = StringBuilder()
        groups.forEach { (token, aliases) ->
            if (upper.contains(token)) {
                aliases.forEach { alias ->
                    if (builder.isNotEmpty()) builder.append(' ')
                    builder.append(alias)
                }
            }
        }
        return builder.toString()
    }
}
