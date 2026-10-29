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

import com.rtbishop.look4sat.core.domain.predict.OrbitalData

/**
 * One catalogue feed: everything a single URL returned, plus its configured priority.
 * Lower [priority] wins ties, so CelesTrak is 0 and mirrors come after it.
 */
data class TleSourceBatch(
    val sourceId: String,
    val priority: Int,
    val entries: List<OrbitalData>
)

/** Result of a merge, carrying enough counters for the UI banner and the logs. */
data class MergeOutcome(
    val entries: List<OrbitalData>,
    /** Entries that survived, per source id — useful for "which feed is alive" reporting. */
    val acceptedPerSource: Map<String, Int>,
    /** Entries dropped because their catalogue number is provisional or their name is a placeholder. */
    val provisionalDropped: Int,
    /** Entries dropped because they had no usable mean motion. */
    val malformedDropped: Int
)

/**
 * Merges several TLE/OMM feeds into one catalogue keyed by NORAD catalogue number.
 *
 * Rules, in the order they apply:
 *  1. A record with an unusable catalogue number or a placeholder name is interim: it is counted
 *     and dropped, never written to the catalogue (it cannot be tracked reliably).
 *  2. For a given catalogue number the **newest epoch** wins. This is the only rule that decides
 *     orbital elements.
 *  3. If two sources publish the same epoch, the **higher-priority source** (lower number) wins;
 *     if the priority is also equal, the first one seen wins.
 *  4. The display name follows source priority independently of rule 2, so a rename upstream does
 *     not require the elements to move.
 *  5. A satellite present in fewer sources — or in none — is never removed here. Absence of a
 *     record is not evidence of decay; the caller keeps whatever it already had.
 */
object TleMergeEngine {

    /** NORAD catalogue numbers are five digits; anything above this is an internal placeholder. */
    const val MAX_CATNUM = 33_9999

    private val placeholderName = Regex("^(TBA|TBD|UNKNOWN|ANALYSIS|TMP)\\b", RegexOption.IGNORE_CASE)

    fun merge(batches: List<TleSourceBatch>): MergeOutcome {
        val ordered = batches.sortedBy { it.priority }
        val freshest = HashMap<Int, OrbitalData>()
        val preferredNames = HashMap<Int, String>()
        val accepted = LinkedHashMap<String, Int>()
        var provisional = 0
        var malformed = 0

        for (batch in ordered) {
            var hits = 0
            for (entry in batch.entries) {
                if (!isTrackable(entry)) {
                    if (isProvisional(entry)) provisional++ else malformed++
                    continue
                }
                hits++
                val name = entry.name.trim()
                if (name.isNotEmpty()) preferredNames.putIfAbsent(entry.catnum, name)
                val current = freshest[entry.catnum]
                if (current == null || entry.epochDaynum > current.epochDaynum) {
                    freshest[entry.catnum] = entry
                }
            }
            accepted[batch.sourceId] = (accepted[batch.sourceId] ?: 0) + hits
        }

        val merged = freshest.values.map { entry ->
            val name = preferredNames[entry.catnum]
            if (name == null || name == entry.name) entry else entry.copy(name = name)
        }
        return MergeOutcome(
            entries = merged,
            acceptedPerSource = accepted,
            provisionalDropped = provisional,
            malformedDropped = malformed
        )
    }

    /** Catalogue numbers outside the NORAD five-digit space cannot be propagated meaningfully. */
    fun isTrackable(entry: OrbitalData): Boolean =
        entry.catnum in 1..MAX_CATNUM && !isProvisional(entry) && entry.meanmo > 0.0

    /**
     * Interim catalogue entries: analyst objects that Space-Track republishes under a new number,
     * placeholder objects ("OBJECT A" awaiting identification) and TBA designators.
     */
    fun isProvisional(entry: OrbitalData): Boolean {
        if (entry.catnum <= 0 || entry.catnum > MAX_CATNUM) return true
        val name = entry.name.trim()
        if (name.isEmpty()) return true
        return placeholderName.containsMatchIn(name)
    }
}
