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
    fun `list markers are not field text`() {
        assertEquals("Paris", fieldTextForCompare("<ul><li>Paris</li></ul>"))
        assertEquals("a b", fieldTextForCompare("<ol><li>a</li><li>b</li></ol>"))
    }

    @Test
    fun `a details section's summary and body are field text`() {
        assertEquals("Hint Paris", fieldTextForCompare("<details><summary>Hint</summary>Paris</details>"))
        // Inside a list item, the item's marker rides on the summary line; it is still not field text.
        assertEquals("Hint Paris", fieldTextForCompare("<ul><li><details><summary>Hint</summary>Paris</details></li></ul>"))
    }

    @Test
    fun `surrounding whitespace is trimmed and interior collapsed`() {
        assertEquals("New York", fieldTextForCompare("  New   York  "))
    }

    @Test
    fun `blocks and line breaks become single spaces, as in Anki`() {
        assertEquals("New York", fieldTextForCompare("<div>New</div><div>York</div>"))
        assertEquals("New York", fieldTextForCompare("New<br>York"))
        assertEquals("New York", fieldTextForCompare("New <br> <br> York"))
    }
}
