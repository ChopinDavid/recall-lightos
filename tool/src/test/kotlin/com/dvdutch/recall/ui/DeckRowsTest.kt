package com.dvdutch.recall.ui

import com.dvdutch.recall.api.Deck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [deckRows] — the pure `::`-name → indented-row flattening. No
 * Compose/Android runtime is needed; the tree logic lives in a plain function.
 */
class DeckRowsTest {

    private fun deck(id: Long, name: String, new: Int = 0, learning: Int = 0, review: Int = 0) =
        Deck(id = id, name = name, new = new, learning = learning, review = review)

    @Test
    fun `top-level deck is depth zero and keeps its whole name`() {
        val rows = deckRows(listOf(deck(1, "Russian")))
        assertEquals(1, rows.size)
        assertEquals("Russian", rows[0].label)
        assertEquals(0, rows[0].depth)
        assertTrue(rows[0].isTopLevel)
    }

    @Test
    fun `child depth counts the double-colon separators and label is the leaf`() {
        val rows = deckRows(
            listOf(
                deck(1, "Russian"),
                deck(2, "Russian::Grammar"),
                deck(3, "Russian::Pimsleur Russian::1 - Russian Level 1::Lesson 01"),
            ),
        )
        assertEquals(0, rows[0].depth)
        assertEquals(1, rows[1].depth)
        assertEquals("Grammar", rows[1].label)
        assertEquals(3, rows[2].depth)
        assertEquals("Lesson 01", rows[2].label)
        assertFalse(rows[2].isTopLevel)
    }

    @Test
    fun `server order is preserved`() {
        val rows = deckRows(
            listOf(
                deck(10, "B"),
                deck(20, "A"),
                deck(30, "A::child"),
            ),
        )
        assertEquals(listOf(10L, 20L, 30L), rows.map { it.id })
    }

    @Test
    fun `counts label formats new middot learning middot review verbatim`() {
        val row = deckRows(listOf(deck(1, "Russian", new = 19, learning = 1, review = 0)))[0]
        assertEquals("19 · 1 · 0", row.countsLabel())
    }

    @Test
    fun `hasDue reflects any nonzero count`() {
        assertFalse(deckRows(listOf(deck(1, "Empty")))[0].hasDue)
        assertTrue(deckRows(listOf(deck(2, "New", new = 3)))[0].hasDue)
        assertTrue(deckRows(listOf(deck(3, "Learn", learning = 1)))[0].hasDue)
        assertTrue(deckRows(listOf(deck(4, "Rev", review = 2)))[0].hasDue)
    }
}
