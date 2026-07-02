package com.dvdutch.recall.ui

/**
 * One grade button: the [rating] posted to the bridge, the fixed human [word]
 * shown above the interval, and the [interval] label the bridge supplied in the
 * card's `next_due_labels` (e.g. `<⁨10⁩m`, `⁨3⁩d`). Bidi-isolate control
 * characters inside the interval are part of the label and pass through unchanged.
 *
 * Pure and Compose-free so the fixed ordering + label extraction is unit-testable.
 */
data class GradeButton(
    val rating: String,
    val word: String,
    val interval: String,
)

/**
 * Builds the four grade buttons in Anki's canonical left-to-right order
 * (again, hard, good, easy), pulling each interval label from [nextDueLabels] by
 * its rating key. A rating missing from the map falls back to an empty interval
 * so the button still renders (the bridge always sends all four in practice).
 */
fun gradeButtons(nextDueLabels: Map<String, String>): List<GradeButton> =
    GRADE_ORDER.map { (rating, word) ->
        GradeButton(
            rating = rating,
            word = word,
            interval = nextDueLabels[rating].orEmpty(),
        )
    }

/** The canonical rating order and the fixed word shown for each. */
private val GRADE_ORDER: List<Pair<String, String>> = listOf(
    "again" to "Again",
    "hard" to "Hard",
    "good" to "Good",
    "easy" to "Easy",
)
