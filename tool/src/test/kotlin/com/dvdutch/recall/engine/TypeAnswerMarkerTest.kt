package com.dvdutch.recall.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Pure tests for [TypeAnswerMarker]: detecting, classifying, and stripping the
 * literal `[[type:...]]` marker rslib emits in place of a `{{type:Field}}`
 * replacement. The client must strip the marker (so it never renders as garbage
 * text) and, on the question side, learn WHICH field to grade against.
 */
class TypeAnswerMarkerTest {

    @Test
    fun `plain marker is detected the field is named and no-case is off`() {
        val marker = TypeAnswerMarker.find("Capital of France? [[type:Back]]")
        assertEquals("Back", marker?.field)
        assertFalse(marker!!.noCase, "a plain type marker is case-sensitive")
        assertFalse(marker.cloze, "a plain type marker is not a cloze marker")
    }

    @Test
    fun `no-case marker sets the field and the no-case flag`() {
        val marker = TypeAnswerMarker.find("[[type:nc:Back]]")
        assertEquals("Back", marker?.field)
        assertTrue(marker!!.noCase, "an nc: marker must be case-insensitive")
        assertFalse(marker.cloze)
    }

    @Test
    fun `cloze marker is flagged and carries its field name`() {
        val marker = TypeAnswerMarker.find("[[type:cloze:Text]]")
        assertEquals("Text", marker?.field)
        assertTrue(marker!!.cloze, "a cloze type marker must set the cloze flag")
        assertFalse(marker.noCase)
    }

    @Test
    fun `html with no marker yields null`() {
        assertNull(TypeAnswerMarker.find("Capital of France?"))
    }

    @Test
    fun `strip removes a plain marker and trims stray surrounding whitespace`() {
        assertEquals(
            "Capital of France?",
            TypeAnswerMarker.strip("Capital of France? [[type:Back]]"),
        )
    }

    @Test
    fun `strip removes an nc and a cloze marker too`() {
        assertEquals("Q", TypeAnswerMarker.strip("Q [[type:nc:Back]]"))
        assertEquals("Q", TypeAnswerMarker.strip("Q [[type:cloze:Text]]"))
    }

    @Test
    fun `strip leaves marker-free html untouched`() {
        assertEquals("Capital of France?", TypeAnswerMarker.strip("Capital of France?"))
    }
}
