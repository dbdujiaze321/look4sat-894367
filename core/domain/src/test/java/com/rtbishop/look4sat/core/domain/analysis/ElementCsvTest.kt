package com.rtbishop.look4sat.core.domain.analysis

import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export only earns its place if it comes back exactly as it went out. Every assertion here is a
 * round trip through the real importer, not a comparison against a hand written string, because a
 * formatting mistake that reads fine to a human is precisely the failure mode that matters.
 */
@ExperimentalCoroutinesApi
class ElementCsvTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val parser = DataParser(dispatcher)

    private val iss = OrbitalData(
        name = "ISS (ZARYA)",
        epoch = 24001.50000000,
        meanmo = 15.49512345,
        eccn = 0.0006703,
        incl = 51.6416,
        raan = 247.4627,
        argper = 130.5360,
        meanan = 325.0288,
        catnum = 25544,
        bstar = 0.00010270,
        ndot = 0.00001288
    )

    private suspend fun roundTrip(entries: List<OrbitalData>): List<OrbitalData> =
        parser.parseCSVStream(ElementCsv.build(entries).byteInputStream())

    @Test
    fun `header matches the columns the importer looks up by name`() {
        val header = ElementCsv.build(emptyList()).lineSequence().first()
        assertEquals(ElementCsv.HEADER, header)
        for (column in listOf(
            "OBJECT_NAME", "EPOCH", "MEAN_MOTION", "ECCENTRICITY", "INCLINATION",
            "RA_OF_ASC_NODE", "ARG_OF_PERICENTER", "MEAN_ANOMALY", "NORAD_CAT_ID",
            "BSTAR", "MEAN_MOTION_DOT"
        )) {
            assertTrue("missing column $column", header.split(",").contains(column))
        }
    }

    @Test
    fun `every orbital field survives the round trip`() = runTest(dispatcher) {
        val parsed = roundTrip(listOf(iss)).single()
        assertEquals(iss.catnum, parsed.catnum)
        assertEquals(iss.name, parsed.name)
        assertEquals(iss.meanmo, parsed.meanmo, 1e-8)
        assertEquals(iss.eccn, parsed.eccn, 1e-7)
        assertEquals(iss.incl, parsed.incl, 1e-4)
        assertEquals(iss.raan, parsed.raan, 1e-4)
        assertEquals(iss.argper, parsed.argper, 1e-4)
        assertEquals(iss.meanan, parsed.meanan, 1e-4)
        assertEquals(iss.bstar, parsed.bstar, 1e-9)
        assertEquals(iss.ndot, parsed.ndot, 1e-8)
        assertEquals(iss.epoch, parsed.epoch, 1e-5)
    }

    @Test
    fun `a name containing a comma stays one column`() = runTest(dispatcher) {
        val parsed = roundTrip(listOf(iss.copy(name = "CSS, (TIANHE)"))).single()
        assertEquals("CSS, (TIANHE)", parsed.name)
        // The shifted-column failure would show up here as a wrong mean motion.
        assertEquals(iss.meanmo, parsed.meanmo, 1e-8)
    }

    @Test
    fun `a name containing a quote is doubled, not dropped`() = runTest(dispatcher) {
        val parsed = roundTrip(listOf(iss.copy(name = "AO-91 \"Fox\"?"))).single()
        assertEquals("AO-91 \"Fox\"?", parsed.name)
    }

    @Test
    fun `epoch converts to ISO with sub millisecond precision`() {
        assertEquals("2024-01-01T12:00:00.000000Z", ElementCsv.epochToIso(24001.5))
        assertEquals("2026-01-03T00:00:00.000000Z", ElementCsv.epochToIso(26003.0))
        // 1e-8 day = 0.864 ms: the smallest step the TLE epoch can express must still show up.
        assertNotEquals(
            ElementCsv.epochToIso(24001.0),
            ElementCsv.epochToIso(24001.00000001)
        )
    }

    @Test
    fun `several entries keep their order`() = runTest(dispatcher) {
        val parsed = roundTrip(listOf(iss, iss.copy(catnum = 43017, name = "CSS (TIANHE)")))
        assertEquals(2, parsed.size)
        assertEquals(listOf(25544, 43017), parsed.map { it.catnum })
        assertEquals(listOf("ISS (ZARYA)", "CSS (TIANHE)"), parsed.map { it.name })
    }

    @Test
    fun `an empty catalogue still produces a usable file`() = runTest(dispatcher) {
        val text = ElementCsv.build(emptyList())
        assertEquals(1, text.trim().lineSequence().count())
        assertTrue(roundTrip(emptyList()).isEmpty())
    }

    @Test
    fun `file name is dated in UTC and filesystem safe`() {
        assertEquals("look4sat-elements-19700101.csv", ElementCsv.fileName(0L))
        assertTrue(ElementCsv.fileName().none { it == ':' || it == ' ' })
    }

    @Test
    fun `a bstar of zero survives as zero`() = runTest(dispatcher) {
        val parsed = roundTrip(listOf(iss.copy(bstar = 0.0))).single()
        assertEquals(0.0, parsed.bstar, 1e-12)
    }
}
