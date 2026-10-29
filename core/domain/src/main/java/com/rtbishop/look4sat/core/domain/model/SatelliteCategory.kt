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
 * Coarse purpose groups used by the satellite list grouping mode and by the radar icon renderer.
 *
 * Classification is name based on purpose. Catalogue names are the only field every source
 * publishes, and they are stable enough for this job: a satellite renamed beyond recognition only
 * falls back to [OTHER] instead of disappearing from the list.
 *
 * Order matters: [values] is the display order of the grouped list, most specific first.
 */
enum class SatelliteCategory(val sortOrder: Int) {
    SPACE_STATION(0),
    AMATEUR(1),
    WEATHER(2),
    NAVIGATION(3),
    SCIENCE(4),
    COMMUNICATION(5),
    OTHER(6);

    companion object {
        /** Manned stations. Matched before amateur, because "ISS" appears in many amateur entries. */
        private val stationTokens = listOf(
            "ISS (ZARYA)", "ISS(ZARYA)", "ZARYA", "TIANGONG", "CSS (TIANHE)", "TIANHE",
            "WENTIAN", "MENGTIAN", "SALYUT", "SKYLAB", "TIANZHOU"
        )

        /**
         * Amateur radio satellites. The Oscar series uses two letter prefixes ("AO-7", "SO-50"),
         * which are matched as whole tokens to avoid hitting words like "POES" or "NOAA".
         */
        private val amateurTokens = listOf(
            "AMSAT", "OSCAR", "FUNcube", "FUN-CUBE", "CUBEBEL", "RADFXSAT", "FOX-1",
            "BEESAT", "SONATE", "LILACSAT", "CAS-", "XW-2", "XW-3", "HO-", "EO-88",
            "JY1SAT", "AO-91", "AO-92", "QO-100", "ES'HAIL", "OSCAR 7"
        )

        private val weatherTokens = listOf(
            "NOAA", "METEOR", "METOP", "FENGYUN", "FENGYUN-", "FY-1", "FY-2", "FY-3", "FY-4",
            "GOES", "ELEKTRO", "HIMAWARI", "DMSP", "SUOMI", "JPSS", "SENTINEL-3", "SENTINEL-5P",
            "OCEANSAT", "SCATSAT", "INSAT-3D", "METEOSAT", "GK-2A", "ARCTIC WEATHER"
        )

        private val navigationTokens = listOf(
            "NAVSTAR", "GPS", "GLONASS", "GALILEO", "BEIDOU", "BDS-", "COMPASS-", "IRNSS",
            "NAVIC", "QZS", "MICHIBIKI", "SBAS", "EGNOS", "WAAS", "GSAT-"
        )

        private val scienceTokens = listOf(
            "HUBBLE", "HST", "CHANDRA", "SWIFT", "FERMI", "XMM", "INTEGRAL", "NUSTAR",
            "TERRA", "AQUA", "LANDSAT", "SENTINEL", "ICESAT", "GRACE", "SWARM", "CLUSTER",
            "AKEBONO", "HINODE", "YOHKOH", "TESS", "GAIA", "SPEKTR", "SPECTR", "MILLIMETRON"
        )

        private val communicationTokens = listOf(
            "INTELSAT", "EUTELSAT", "INMARSAT", "IRIDIUM", "GLOBALSTAR", "STARLINK", "ONEWEB",
            "TDRS", "SES-", "TURKSAT", "CHINASAT", "APSTAR", "ASIASAT", "THURAYA", "SKYNET",
            "TELSTAR", "HOTBIRD", "ASTRA", "AMOS-", "EUTELSAT", "VIASAT", "O3B", "YAMAL",
            "EXPRESS-", "ABS-", "BELINTERSAT", "PAKSAT", "SUPERSAT", "JCSAT", "BSAT"
        )

        /** Two letter Oscar prefixes, only valid when followed by a dash and digits. */
        private val oscarPrefixes = Regex("""(^|\s)([A-Z]{2})-\d""")
        private val oscarLetters = setOf(
            "AO", "SO", "FO", "RS", "HO", "PO", "IO", "EO", "TO", "BO", "LO", "NO", "UO", "WO",
            "QO", "JO", "GO", "DO", "MO", "CO", "ZO", "YO", "XO", "KO", "VO"
        )

        fun classify(rawName: String): SatelliteCategory {
            val name = rawName.uppercase()
            // Amateur first: "AMSAT-OSCAR 7" also contains "OSCAR 7" and must not become science.
            if (matchesAny(name, amateurTokens) || matchesOscar(name)) return AMATEUR
            if (matchesAny(name, stationTokens)) return SPACE_STATION
            if (matchesAny(name, weatherTokens)) return WEATHER
            if (matchesAny(name, scienceTokens)) return SCIENCE
            if (matchesAny(name, navigationTokens)) return NAVIGATION
            if (matchesAny(name, communicationTokens)) return COMMUNICATION
            return OTHER
        }

        private fun matchesAny(name: String, tokens: List<String>): Boolean =
            tokens.any { name.contains(it.uppercase()) }

        private fun matchesOscar(name: String): Boolean =
            oscarPrefixes.findAll(name).any { it.groupValues[2] in oscarLetters }

        fun ordered(): List<SatelliteCategory> = SatelliteCategory.entries.sortedBy { it.sortOrder }
    }
}
