package com.dvdutch.recall.ui

import com.dvdutch.recall.api.DetailsNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [splitListMarker]: a list item's marker run is set apart from the item's text. */
class ListItemLayoutTest {

    @Test
    fun `a marker run is split from the item text`() {
        val marker = TextRun("1. ", marker = true)
        val body = listOf(TextRun("one "), TextRun("two", b = true))
        assertEquals(marker to body, splitListMarker(TextNode(listOf(marker) + body, indent = 1)))
    }

    @Test
    fun `an item without a marker keeps all its runs as text`() {
        val runs = listOf(TextRun("этот", b = true))
        assertEquals(null to runs, splitListMarker(TextNode(runs, indent = 1)))
        assertEquals(null to emptyList(), splitListMarker(TextNode(emptyList())))
    }

    @Test
    fun `right-to-left text is told by its first directional letter`() {
        assertTrue(startsRtl(listOf(TextRun("كتاب (kitāb)"))))
        assertTrue(startsRtl(listOf(TextRun("", audioTrack = 0), TextRun(" (1) שלום"))))
        assertFalse(startsRtl(listOf(TextRun("The word كتاب"))))
        assertFalse(startsRtl(listOf(TextRun("123 "), TextRun("abc"))))
        assertFalse(startsRtl(emptyList()))
    }

    @Test
    fun `a details summary line splits its item marker off and leads with the chevron`() {
        val runs = listOf(TextRun("• ", marker = true), TextRun("Translation"))
        val (marker, text) = splitListMarker(runs)
        assertEquals(TextRun("• ", marker = true), marker)
        assertEquals("▸ Translation", detailsSummaryText(open = false, runs = text).text)
        assertEquals("▾ Translation", detailsSummaryText(open = true, runs = text).text)
        assertEquals("▸ ", detailsSummaryText(open = false, runs = emptyList()).text)
    }

    @Test
    fun `disclosure state follows each section object, defaulting to its open attribute`() {
        val a = DetailsNode(listOf(TextRun("Etymology")), emptyList())
        val twin = a.copy()
        val opened = DetailsNode(listOf(TextRun("s")), emptyList(), open = true)
        val state = DisclosureState()
        assertFalse(state.isOpen(a))
        assertTrue(state.isOpen(opened))
        state.toggle(a)
        assertTrue(state.isOpen(a))
        // An equal section on another card (a different object) keeps its own state.
        assertFalse(state.isOpen(twin))
        state.toggle(opened)
        assertFalse(state.isOpen(opened))
        state.toggle(a)
        assertFalse(state.isOpen(a))
    }
}
