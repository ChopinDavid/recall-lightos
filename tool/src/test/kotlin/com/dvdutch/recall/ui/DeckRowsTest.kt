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

    // ---- visibleDeckRows: collapse/expand filtering ----
    //
    // Collapse state is the EXPANDED set (deck ids present = expanded). A parent
    // absent from the set reads as collapsed, so an empty set = "all parents
    // collapsed" (the default: only top-level decks visible).

    private val russianTree = listOf(
        deck(1, "Russian"),
        deck(2, "Russian::Grammar"),
        deck(3, "Russian::Grammar::Cases"),
        deck(4, "Russian::Pimsleur Russian"),
        deck(5, "Diverged"),
        deck(6, "Diverged::Sub"),
    )

    @Test
    fun `empty expanded set shows only top-level decks`() {
        val rows = visibleDeckRows(russianTree, expandedIds = emptySet())
        assertEquals(listOf(1L, 5L), rows.map { it.id })
    }

    @Test
    fun `a parent with children is flagged hasChildren and starts not expanded`() {
        val rows = visibleDeckRows(russianTree, expandedIds = emptySet())
        val russian = rows.first { it.id == 1L }
        assertTrue(russian.hasChildren)
        assertFalse(russian.isExpanded)
    }

    @Test
    fun `a leaf deck is not flagged hasChildren`() {
        val rows = visibleDeckRows(listOf(deck(1, "Solo")), expandedIds = emptySet())
        assertFalse(rows[0].hasChildren)
        assertFalse(rows[0].isExpanded)
    }

    @Test
    fun `expanding a parent reveals its direct children and flags it expanded`() {
        val rows = visibleDeckRows(russianTree, expandedIds = setOf(1L))
        // Russian expanded -> its two direct children appear; grandchild (Cases)
        // stays hidden because Grammar is not expanded.
        assertEquals(listOf(1L, 2L, 4L, 5L), rows.map { it.id })
        assertTrue(rows.first { it.id == 1L }.isExpanded)
        // Grammar has a child and is currently collapsed.
        val grammar = rows.first { it.id == 2L }
        assertTrue(grammar.hasChildren)
        assertFalse(grammar.isExpanded)
    }

    @Test
    fun `expanding a parent does not auto-expand a collapsed grandchild-parent`() {
        // Expand Russian AND Grammar, but Grammar's child Cases is a leaf so it shows;
        // proves cascade only follows explicitly-expanded ancestors.
        val rows = visibleDeckRows(russianTree, expandedIds = setOf(1L, 2L))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), rows.map { it.id })
    }

    @Test
    fun `a child stays hidden if any ancestor is collapsed even when the child itself is expanded`() {
        // Grammar (2) expanded but Russian (1) collapsed: Grammar and its subtree hidden.
        val rows = visibleDeckRows(russianTree, expandedIds = setOf(2L))
        assertEquals(listOf(1L, 5L), rows.map { it.id })
    }

    @Test
    fun `deep chain reveals one level per expanded ancestor`() {
        val deep = listOf(
            deck(1, "A"),
            deck(2, "A::B"),
            deck(3, "A::B::C"),
            deck(4, "A::B::C::D"),
        )
        assertEquals(listOf(1L), visibleDeckRows(deep, emptySet()).map { it.id })
        assertEquals(listOf(1L, 2L), visibleDeckRows(deep, setOf(1L)).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), visibleDeckRows(deep, setOf(1L, 2L)).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), visibleDeckRows(deep, setOf(1L, 2L, 3L)).map { it.id })
    }

    @Test
    fun `a name-prefix that is not a deck-boundary does not count as a child`() {
        // "Russian" must not treat "Russians" as a child — only "Russian::" segments.
        val rows = visibleDeckRows(
            listOf(deck(1, "Russian"), deck(2, "Russians")),
            expandedIds = emptySet(),
        )
        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertFalse(rows.first { it.id == 1L }.hasChildren)
    }

    @Test
    fun `visible rows preserve server order and keep depth-indent and counts`() {
        val rows = visibleDeckRows(
            listOf(deck(1, "Russian", new = 5), deck(2, "Russian::Grammar", review = 2)),
            expandedIds = setOf(1L),
        )
        assertEquals(listOf(1L, 2L), rows.map { it.id })
        assertEquals(1, rows[1].depth)
        assertEquals("Grammar", rows[1].label)
        assertEquals("0 · 0 · 2", rows[1].countsLabel())
    }
}
