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
