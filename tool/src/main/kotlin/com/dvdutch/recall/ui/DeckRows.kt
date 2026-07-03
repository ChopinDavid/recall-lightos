package com.dvdutch.recall.ui

import com.dvdutch.recall.api.Deck

/**
 * A deck presented as a single list row: its display label (the leaf segment of a
 * `::`-separated Anki deck name), the indent [depth] derived from that name, and
 * whether it is a top-level deck (rendered with a title-weight variant).
 *
 * Pure and Compose-free so the `::`-tree flattening is unit-testable on the JVM.
 */
data class DeckRow(
    val id: Long,
    /** Leaf segment of the `::`-separated name, e.g. `Lesson 01`. */
    val label: String,
    /** Number of `::` ancestors; 0 for a top-level deck. */
    val depth: Int,
    val new: Int,
    val learning: Int,
    val review: Int,
    /**
     * True when some other deck is a `::`-child of this one, so this row gets a
     * tappable collapse/expand glyph. Defaults false for [deckRows] (which is
     * collapse-unaware); [visibleDeckRows] computes it from the full deck list.
     */
    val hasChildren: Boolean = false,
    /**
     * True when [hasChildren] and this deck is in the expanded set — its direct
     * children are shown and the glyph reads `−`. Always false for a leaf.
     */
    val isExpanded: Boolean = false,
) {
    /** True for a top-level deck (no `::` ancestor) — rendered heavier. */
    val isTopLevel: Boolean get() = depth == 0

    /** Whether this deck has anything due right now. */
    val hasDue: Boolean get() = new > 0 || learning > 0 || review > 0
}

/**
 * The right-aligned counts summary shown on a deck row: `new · learning · review`.
 * Rendered verbatim regardless of zeros so the three columns line up across rows.
 */
fun DeckRow.countsLabel(): String = "$new · $learning · $review"

/**
 * Flattens the bridge's flat deck list into indented [DeckRow]s, preserving the
 * server's order. Indent [DeckRow.depth] is the count of `::` separators in the
 * name and the label is the final segment; a name with no `::` is depth 0 and
 * rendered as its whole self.
 */
fun deckRows(decks: List<Deck>): List<DeckRow> = decks.map { deck ->
    val segments = deck.name.split("::")
    DeckRow(
        id = deck.id,
        label = segments.last(),
        depth = segments.size - 1,
        new = deck.new,
        learning = deck.learning,
        review = deck.review,
    )
}

/**
 * Collapse-aware flattening: from the full flat deck list plus the set of deck ids
 * the user has EXPANDED, emit only the rows that should be visible, each tagged with
 * whether it has children ([DeckRow.hasChildren]) and whether it is expanded
 * ([DeckRow.isExpanded]).
 *
 * Encoding note: the set holds EXPANDED ids, so a parent ABSENT from the set reads as
 * collapsed. An empty set therefore means "all parents collapsed" — the default first
 * load shows only top-level decks. (Storing the expanded set is the simplest correct
 * encoding of a "default collapsed" rule: absence = the default.)
 *
 * Visibility rule: a row is visible iff every one of its `::` ancestors is expanded.
 * Expanding a parent reveals only its direct children; a grandchild stays hidden until
 * its own parent is also expanded, so expand never cascades past a collapsed level.
 */
fun visibleDeckRows(decks: List<Deck>, expandedIds: Set<Long>): List<DeckRow> {
    // A deck is a parent iff some other deck's name starts with `${name}::` (a real
    // `::`-boundary, so "Russian" is not a parent of "Russians").
    val names = decks.map { it.name }
    fun hasChildren(name: String): Boolean {
        val prefix = "$name::"
        return names.any { it.startsWith(prefix) }
    }

    val expandedNames = decks.filter { it.id in expandedIds }.map { it.name }.toSet()
    // Every ancestor of `name` must be expanded for `name` to be visible. Ancestors are
    // the successive `::` prefixes: "A::B::C" -> ["A", "A::B"].
    fun allAncestorsExpanded(name: String): Boolean {
        val segments = name.split("::")
        var acc = ""
        for (i in 0 until segments.size - 1) {
            acc = if (acc.isEmpty()) segments[i] else "$acc::${segments[i]}"
            if (acc !in expandedNames) return false
        }
        return true
    }

    return deckRows(decks).zip(decks) { row, deck ->
        val children = hasChildren(deck.name)
        row.copy(
            hasChildren = children,
            isExpanded = children && deck.id in expandedIds,
        )
    }.filterIndexed { index, _ -> allAncestorsExpanded(decks[index].name) }
}
