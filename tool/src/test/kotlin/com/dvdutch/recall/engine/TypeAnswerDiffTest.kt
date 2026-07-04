package com.dvdutch.recall.engine

import com.dvdutch.recall.api.TextNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure tests for [parseTypeAnswerDiff]: turning rslib's `compareAnswer` HTML output
 * — spans classed `typeGood` / `typeBad` / `typeMissed`, `<br>` breaks and the
 * `typearrow` span, all inside one `<code id=typeans>` — into a [TextNode] whose runs
 * carry monochrome-safe styling. The class information (right/wrong/missed) MUST be
 * preserved as run flags; it is NOT routed through the parity compiler (which would
 * flatten it to plain mono).
 *
 * Run-flag mapping (monochrome, on the Light Phone's e-ink):
 *   - typeGood   -> mono (a correctly typed char);
 *   - typeBad    -> mono + strike (a wrong char the user typed);
 *   - typeMissed -> mono + underline (an expected char the user omitted).
 */
class TypeAnswerDiffTest {

    /** All runs of a parsed diff, flattened, for content/flag assertions. */
    private fun runsOf(html: String) =
        (parseTypeAnswerDiff(html) as TextNode).runs

    @Test
    fun `the canonical Paris diff maps every span class to its run flags`() {
        val diff =
            "<code id=typeans>" +
                "<span class=typeBad>p</span><span class=typeGood>aris</span>" +
                "<br><span id=typearrow>&darr;</span><br>" +
                "<span class=typeMissed>P</span><span class=typeGood>aris</span>" +
                "</code>"
        val runs = runsOf(diff)

        // Every run is monospace (the diff is fixed-width by design).
        assertTrue(runs.all { it.mono }, "all diff runs must be mono: $runs")

        // The typed line: a wrong 'p' (strike) then a correct 'aris'.
        val bad = runs.single { it.strike }
        assertEquals("p", bad.s)
        assertTrue(!bad.underline, "a wrong char is strike, not underline")

        // The expected line: a missed 'P' (underline) then a correct 'aris'.
        val missed = runs.single { it.underline }
        assertEquals("P", missed.s)
        assertTrue(!missed.strike, "a missed char is underline, not strike")

        // The correct 'aris' appears twice, plain mono (no strike/underline).
        val good = runs.filter { !it.strike && !it.underline && it.s == "aris" }
        assertEquals(2, good.size, "both 'aris' runs must be plain mono good runs")

        // The arrow separator is decoded to ↓ and the lines are broken by newlines.
        val text = runs.joinToString("") { it.s }
        assertTrue("↓" in text, "the typearrow &darr; must decode to ↓: $text")
        assertEquals(2, text.count { it == '\n' }, "two <br> breaks around the arrow: $text")
    }

    @Test
    fun `an all-good answer has only plain mono runs`() {
        val diff = "<code id=typeans><span class=typeGood>Paris</span></code>"
        val runs = runsOf(diff)
        assertEquals("Paris", runs.joinToString("") { it.s })
        assertTrue(runs.all { it.mono && !it.strike && !it.underline }, "all correct: $runs")
    }

    @Test
    fun `an all-missed answer underlines the whole expected string`() {
        // Nothing typed the right chars: the expected line is all typeMissed.
        val diff = "<code id=typeans><span class=typeMissed>Paris</span></code>"
        val runs = runsOf(diff)
        val missed = runs.single()
        assertEquals("Paris", missed.s)
        assertTrue(missed.mono && missed.underline && !missed.strike)
    }

    @Test
    fun `an empty diff yields an empty text node`() {
        val node = parseTypeAnswerDiff("<code id=typeans></code>") as TextNode
        assertTrue(node.runs.isEmpty(), "empty compare output -> no runs")
    }

    @Test
    fun `entities inside a span are decoded`() {
        val diff = "<code id=typeans><span class=typeGood>A&amp;B</span></code>"
        assertEquals("A&B", runsOf(diff).joinToString("") { it.s })
    }
}
