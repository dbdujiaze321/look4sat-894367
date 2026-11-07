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
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.data.database.entity.SatEntry
import com.rtbishop.look4sat.core.domain.merge.TleMergeEngine
import com.rtbishop.look4sat.core.domain.merge.TleSourceBatch
import com.rtbishop.look4sat.core.domain.analysis.StalenessCalibration
import com.rtbishop.look4sat.core.domain.analysis.StalenessModel
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipInputStream

class DatabaseRepo(
    private val dispatcher: CoroutineDispatcher,
    private val dataParser: DataParser,
    private val localSource: ILocalSource,
    private val remoteSource: IRemoteSource,
    private val settingsRepo: ISettingsRepo
) : IDatabaseRepo {


    override suspend fun updateTLEFromFile(uri: String): Int = withContext(dispatcher) {
        var importedCount = 0
        remoteSource.getFileStream(uri)?.let { stream ->
            val parsed = parseSatelliteStream(uri, unwrapIfZipped(uri, stream))
            val entries = TleMergeEngine.merge(
                listOf(TleSourceBatch("file", LOCAL_FILE_PRIORITY, parsed))
            ).entries
            insertFresherEntries(entries)
            // report what the file contained: a valid file holding only stale data is not an error
            importedCount = entries.size
        }
        setUpdateSuccessful(System.currentTimeMillis())
        importedCount
    }

    /**
     * Imports element sets from a string instead of a file.
     *
     * Same parsing and same merge rules as the file path, because a pasted TLE and a dropped .txt
     * must behave identically: stale elements lose to fresher ones already in the database, and the
     * message must say so rather than silently "succeed" with zero rows.
     */
    override suspend fun updateTLEFromText(text: String): Int = withContext(dispatcher) {
        val parsed = dataParser.parseTLEStream(text.byteInputStream())
        val entries = TleMergeEngine.merge(
            listOf(TleSourceBatch("pasted", LOCAL_FILE_PRIORITY, parsed))
        ).entries
        if (entries.isNotEmpty()) {
            insertFresherEntries(entries)
            setUpdateSuccessful(System.currentTimeMillis())
        }
        entries.size
    }

    /**
     * The whole catalogue as CSV.
     *
     * Deliberately reuses the importer's column names so the file is a real backup rather than a
     * one-way report: it can be imported on another device, or after a data refresh, without a
     * conversion step. Only fields that are actually stored are written.
     */
    override suspend fun exportElementsCsv(): String = withContext(dispatcher) {
        val ids = localSource.getEntriesList().map { it.catnum }
        val entries = localSource.getEntriesWithIds(ids).map { it.data }
        com.rtbishop.look4sat.core.domain.analysis.ElementCsv.build(entries)
    }

    override suspend fun updateTransceiversFromFile(uri: String): Int = withContext(dispatcher) {
        var importedCount = 0
        remoteSource.getFileStream(uri)?.let { stream ->
            val transceivers = dataParser.parseJSONStream(unwrapIfZipped(uri, stream))
            localSource.insertRadios(transceivers, isCustom = true)
            importedCount = transceivers.size
        }
        setUpdateSuccessful(System.currentTimeMillis())
        importedCount
    }

    override suspend fun updateFromRemote() = withContext(dispatcher) {
        val settings = settingsRepo.dataSourcesSettings.value
        fun normalizeUrl(url: String) = if (url.startsWith("http")) url else "https://$url"
        val tleUrls = settings.satelliteUrls.filterIndexed { i, url -> url.isNotBlank() && settings.isSatelliteEnabled(i) }
        val radioUrls = settings.transceiversUrls.filterIndexed { i, url -> url.isNotBlank() && settings.isTransceiverEnabled(i) }
        // launch all network requests concurrently, keeping the raw url as key for status reporting
        val tleJobs = tleUrls.map { url -> async { url to remoteSource.getNetworkStream(normalizeUrl(url)) } }
        val radioJobs = radioUrls.map { url -> async { url to remoteSource.getNetworkStream(normalizeUrl(url)) } }
        val tleResults = tleJobs.awaitAll()
        val radioResults = radioJobs.awaitAll()
        // report the HTTP status code of every source (200, 404, ...)
        settingsRepo.updateDataSourcesStatus(
            (tleResults + radioResults).associate { (url, result) -> url to result.code }
        )
        // parse fetched data concurrently, merging satellites by freshness instead of source order
        val parsedPerSource = tleResults.mapIndexed { index, pair ->
            val url = pair.first
            val result = pair.second
            val entries = result.stream?.let {
                val nUrl = normalizeUrl(url)
                parseSatelliteStream(nUrl, unwrapIfZipped(nUrl, it))
            }.orEmpty()
            // CelesTrak is the reference catalogue and wins every same-epoch tie; the rest keep
            // the order the user configured, so a mirror can be promoted by moving it up.
            val normUrl = normalizeUrl(url)
            val priority = if (normUrl.contains("celestrak", ignoreCase = true)) 0 else index + 1
            TleSourceBatch(url, priority, entries)
        }
        val importedRadios = radioResults.flatMap { (url, result) ->
            result.stream?.let { val nUrl = normalizeUrl(url); dataParser.parseJSONStream(unwrapIfZipped(nUrl, it)) }.orEmpty()
        }.filter { it.uuid.isNotBlank() }.distinctBy { it.uuid }
        // insert parsed data into the database
        val outcome = TleMergeEngine.merge(parsedPerSource)
        insertFresherEntries(outcome.entries)
        // transceivers are a full snapshot: sources publish active entries only, so a retired
        // transceiver simply disappears from the feed and has to be dropped locally as well.
        // Imported ones are kept: no source can refresh them, so nothing would bring them back
        if (importedRadios.isNotEmpty()) {
            val stored = localSource.getRadiosTotal()
            // A healthy feed returns the whole catalogue; a truncated response must not wipe the
            // frequencies the radar page and the Doppler calculator run on.
            if (stored == 0 || importedRadios.size * 2 >= stored) {
                localSource.deleteManagedRadios()
                localSource.insertRadios(importedRadios, isCustom = false)
            } else {
                localSource.insertRadios(importedRadios, isCustom = false)
            }
        }
        // keep the previous timestamp when every source failed, so the next launch retries
        val hasFetchedData = outcome.entries.isNotEmpty() || importedRadios.isNotEmpty()
        val previousTimestamp = settingsRepo.databaseState.value.updateTimestamp
        if (hasFetchedData) markStaleEntries(outcome.entries)
        setUpdateSuccessful(if (hasFetchedData) System.currentTimeMillis() else previousTimestamp)
    }

    override suspend fun clearAllData() = withContext(dispatcher) {
        localSource.deleteEntries()
        localSource.deleteRadios()
        setUpdateSuccessful(0L)
    }

    private suspend fun parseSatelliteStream(url: String, stream: InputStream): List<OrbitalData> {
        val bufferedStream = stream.buffered()
        return when {
            hasCsvHint(url) || looksLikeCsv(bufferedStream) -> dataParser.parseCSVStream(bufferedStream)
            else -> dataParser.parseTLEStream(bufferedStream)
        }
    }

    private fun hasCsvHint(url: String): Boolean {
        return url.contains("FORMAT=csv", ignoreCase = true) ||
            url.endsWith(".csv", ignoreCase = true) ||
            url.endsWith(".csv.zip", ignoreCase = true)
    }

    private fun looksLikeCsv(stream: InputStream): Boolean {
        if (!stream.markSupported()) return false
        stream.mark(4096)
        val preview = ByteArray(4096)
        val length = stream.read(preview)
        stream.reset()
        if (length <= 0) return false
        val line = preview.decodeToString(0, length).lineSequence().firstOrNull()?.trim().orEmpty()
        return line.contains("OBJECT_NAME", ignoreCase = true) ||
            line.contains("NORAD_CAT_ID", ignoreCase = true) ||
            line.count { it == ',' } >= 4
    }

    private companion object {
        /** Manually imported files sit below every network source when epochs tie. */
        const val LOCAL_FILE_PRIORITY = 100
    }

    /** Stores the entries that are newer than the ones already saved, renaming the rest in place. */
    /**
     * Turns every replaced element set into one labelled sample for the staleness model.
     *
     * When a newer set for a satellite arrives, the old set's error at the new epoch is measurable
     * exactly: propagate both and project the difference on the velocity. That is the only ground
     * truth this app can obtain offline, and it is free — the update already touched these rows.
     *
     * Only the satellites the operator actually tracks are measured. Measuring all seventeen
     * thousand would cost two propagator constructions each on the update path for samples that
     * describe nobody's pass list.
     */
    private suspend fun recordStalenessSamples(incoming: List<OrbitalData>) {
        val tracked = settingsRepo.selectedIds.value.toSet()
        if (tracked.isEmpty()) return
        val candidates = incoming.filter { it.catnum in tracked }
        if (candidates.isEmpty()) return
        val storedByCatnum = localSource.getEntriesWithIds(candidates.map { it.catnum })
            .associateBy { it.data.catnum }
        var calibration = StalenessCalibration.decode(settingsRepo.otherSettings.value.stalenessJson)
        var added = 0
        for (entry in candidates) {
            val old = storedByCatnum[entry.catnum]?.data ?: continue
            val sample = StalenessModel.measure(old, entry) ?: continue
            calibration = calibration.withSample(sample)
            added++
        }
        if (added > 0) {
            settingsRepo.updateOtherSettings { it.copy(stalenessJson = calibration.encode()) }
        }
    }

    private suspend fun insertFresherEntries(entries: List<OrbitalData>) {
        val storedEpochs = localSource.getEntriesEpochs()
        val (fresherEntries, staleEntries) = entries.partition { entry ->
            val storedEpoch = storedEpochs[entry.catnum]
            storedEpoch == null || entry.epochDaynum > OrbitalData.epochToDaynum(storedEpoch)
        }
        recordStalenessSamples(fresherEntries)
        localSource.insertEntries(fresherEntries)
        // a reordered source list has to rename satellites right away, even the ones holding
        // elements that are newer than the ones just parsed
        if (staleEntries.isNotEmpty()) {
            val storedNames = localSource.getEntriesNames()
            val renamedEntries = staleEntries.filter { entry -> entry.name != storedNames[entry.catnum] }
            if (renamedEntries.isNotEmpty()) {
                localSource.renameEntries(renamedEntries.associate { it.catnum to it.name })
            }
        }
    }

    /**
     * Soft aging. Satellites that no enabled source has refreshed for [SatEntry.STALE_AFTER_DAYS]
     * days are flagged, never deleted: a source that is down, blocked or simply drops a record is
     * not evidence that the satellite decayed, and the catalogue must survive a failed update.
     */
    private suspend fun markStaleEntries(refreshed: List<OrbitalData>) {
        val currentDaynum = OrbitalData.timeToDaynum(System.currentTimeMillis())
        val staleIds = localSource.getEntriesEpochs()
            .filterValues { epoch -> currentDaynum - OrbitalData.epochToDaynum(epoch) > SatEntry.STALE_AFTER_DAYS }
            .keys.toList()
        if (staleIds.isNotEmpty()) localSource.markEntriesStale(staleIds)
        val refreshedIds = refreshed.map { it.catnum }
        if (refreshedIds.isNotEmpty()) localSource.clearStaleEntries(refreshedIds)
    }

    private suspend fun setUpdateSuccessful(timestamp: Long) {
        settingsRepo.updateDatabaseState(
            DatabaseState(localSource.getRadiosTotal(), localSource.getEntriesTotal(), timestamp)
        )
    }

    private fun unwrapIfZipped(url: String, stream: InputStream): InputStream =
        if (url.endsWith(".zip", ignoreCase = true)) ZipInputStream(stream).apply { nextEntry } else stream
}
