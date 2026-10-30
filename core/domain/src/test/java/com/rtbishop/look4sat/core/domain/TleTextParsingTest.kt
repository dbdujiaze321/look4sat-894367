package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pasted element sets arrive in whatever shape the messenger decided to give them: blank lines
 * between blocks, CRLF endings, a trailing newline, sometimes a leading "0 " name prefix. The parser
 * has to survive all of that, because the alternative is a user pasting a TLE and being told it is
 * invalid while the text is plainly fine.
 */
@ExperimentalCoroutinesApi
class TleTextParsingTest {

    // One scheduler for both the parser's dispatcher and runTest: mixing two makes the test
    // framework refuse to run at all.
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val parser = DataParser(dispatcher)

    private val line1 = "1 25544U 98067A   21320.51955234  .00001288  00000+0  31985-4 0  9990"
    private val line2 = "2 25544  51.6447 309.4881 0004694 203.6966 299.8876 15.48582035312205"

    private fun block(name: String, catnum: String = "25544"): List<String> = listOf(
        name,
        line1.replaceFirst("25544", catnum),
        line2.replaceFirst("25544", catnum)
    )

    private suspend fun parse(text: String) = parser.parseTLEStream(text.byteInputStream())

    @Test
    fun `blank lines between blocks do not shift the pairing`() = runTest(dispatcher) {
        val text = (block("ISS (ZARYA)") + "" + block("CSS (TIANHE)", "43017") + "").joinToString("\n")
        val entries = parse(text)
        assertEquals(2, entries.size)
        assertEquals(25544, entries[0].catnum)
        assertEquals("ISS (ZARYA)", entries[0].name)
        assertEquals(43017, entries[1].catnum)
        assertEquals("CSS (TIANHE)", entries[1].name)
    }

    @Test
    fun `windows line endings from a chat app parse normally`() = runTest(dispatcher) {
        val text = block("ISS (ZARYA)").joinToString("\r\n") + "\r\n"
        val entries = parse(text)
        assertEquals(1, entries.size)
        assertEquals("ISS (ZARYA)", entries[0].name)
    }

    @Test
    fun `a leading blank line is ignored`() = runTest(dispatcher) {
        val entries = parse("\n\n" + block("ISS (ZARYA)").joinToString("\n"))
        assertEquals(1, entries.size)
    }

    @Test
    fun `several satellites in one paste all survive`() = runTest(dispatcher) {
        val text = (block("A", "11111") + block("B", "22222") + block("C", "33333")).joinToString("\n")
        val entries = parse(text)
        assertEquals(3, entries.size)
        assertEquals(listOf(11111, 22222, 33333), entries.map { it.catnum })
    }

    @Test
    fun `a three line name prefix is stripped`() = runTest(dispatcher) {
        val entries = parse(block("0 ISS (ZARYA)").joinToString("\n"))
        assertEquals("ISS (ZARYA)", entries.first().name)
    }

    @Test
    fun `an element pair with no name above it is skipped, not named after its data`() = runTest(dispatcher) {
        // line1 starts with "1", so a naive scan would call this satellite "1 25544U ...".
        val entries = parse(listOf(line1, line2).joinToString("\n"))
        assertTrue("nothing should be invented from a truncated paste", entries.isEmpty())
    }

    @Test
    fun `a truncated block does not poison the block that follows it`() = runTest(dispatcher) {
        val text = (listOf(line1, line2) + block("ISS (ZARYA)")).joinToString("\n")
        val entries = parse(text)
        assertEquals(1, entries.size)
        assertEquals("ISS (ZARYA)", entries[0].name)
    }

    @Test
    fun `a name starting with a digit two is still a name`() = runTest(dispatcher) {
        val entries = parse(block("2M-1").joinToString("\n"))
        assertEquals(1, entries.size)
        assertEquals("2M-1", entries[0].name)
    }

    @Test
    fun `comments and headers between blocks are treated as names and discarded`() = runTest(dispatcher) {
        val text = (listOf("# Celestrak GP data", "") + block("ISS (ZARYA)")).joinToString("\n")
        val entries = parse(text)
        assertEquals(1, entries.size)
        assertEquals("ISS (ZARYA)", entries[0].name)
    }
}
