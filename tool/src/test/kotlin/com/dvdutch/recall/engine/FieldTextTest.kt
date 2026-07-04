package com.dvdutch.recall.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure tests for [fieldTextForCompare]: reducing a note field's stored HTML to the
 * plain text the type-answer comparison expects. Anki compares the typed answer
 * against the field with HTML tags removed, entities decoded, `[sound:]`/media refs
 * dropped, and surrounding whitespace trimmed — so a field of `<b>Paris</b>` grades
 * as `Paris`.
 */
class FieldTextTest {

    @Test
    fun `plain text passes through`() {
        assertEquals("Paris", fieldTextForCompare("Paris"))
    }

    @Test
    fun `html tags are removed`() {
        assertEquals("Paris", fieldTextForCompare("<b>Paris</b>"))
        assertEquals("Paris", fieldTextForCompare("<div>Paris</div>"))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("A&B", fieldTextForCompare("A&amp;B"))
        assertEquals("café", fieldTextForCompare("caf&#233;"))
    }

    @Test
    fun `sound refs are dropped`() {
        assertEquals("Paris", fieldTextForCompare("Paris[sound:x.mp3]"))
    }

    @Test
    fun `image tags are dropped leaving the surrounding text`() {
        assertEquals("Paris", fieldTextForCompare("""Paris<img src="map.png">"""))
    }

    @Test
    fun `surrounding whitespace is trimmed and interior collapsed`() {
        assertEquals("New York", fieldTextForCompare("  New   York  "))
    }
}
