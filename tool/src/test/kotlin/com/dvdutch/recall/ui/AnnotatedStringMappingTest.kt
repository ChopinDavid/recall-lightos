package com.dvdutch.recall.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [textNodeToAnnotatedString] — the pure mapping from a
 * [TextNode]'s runs to a styled [androidx.compose.ui.text.AnnotatedString].
 * Compose UI itself is verified on the emulator in a later task; here we only
 * assert the (Compose-runtime-free) plain text, span ranges and span styles.
 */
class AnnotatedStringMappingTest {

    @Test
    fun `plain single run has text and no spans`() {
        val result = textNodeToAnnotatedString(TextNode(listOf(TextRun("hello"))))
        assertEquals("hello", result.text)
        assertTrue(result.spanStyles.isEmpty(), "plain run should carry no spans")
    }

    @Test
    fun `runs are concatenated in order`() {
        val result = textNodeToAnnotatedString(
            TextNode(listOf(TextRun("foo"), TextRun("bar"), TextRun("baz"))),
        )
        assertEquals("foobarbaz", result.text)
    }

    @Test
    fun `bold run maps to FontWeight Bold over its range`() {
        val result = textNodeToAnnotatedString(
            TextNode(listOf(TextRun("A"), TextRun("bold", b = true))),
        )
        assertEquals("Abold", result.text)
        val span = result.spanStyles.single()
        assertEquals(1, span.start)
        assertEquals(5, span.end)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
    }

    @Test
    fun `italic run maps to FontStyle Italic over its range`() {
        val result = textNodeToAnnotatedString(TextNode(listOf(TextRun("it", i = true))))
        val span = result.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(2, span.end)
        assertEquals(FontStyle.Italic, span.item.fontStyle)
    }

    @Test
    fun `mono run maps to Monospace font family`() {
        val result = textNodeToAnnotatedString(TextNode(listOf(TextRun("code", mono = true))))
        val span = result.spanStyles.single()
        assertEquals(FontFamily.Monospace, span.item.fontFamily)
    }

    @Test
    fun `strike run maps to LineThrough decoration`() {
        val result = textNodeToAnnotatedString(TextNode(listOf(TextRun("gone", strike = true))))
        val span = result.spanStyles.single()
        assertEquals(TextDecoration.LineThrough, span.item.textDecoration)
    }

    @Test
    fun `underline run maps to Underline decoration`() {
        val result = textNodeToAnnotatedString(TextNode(listOf(TextRun("miss", underline = true))))
        val span = result.spanStyles.single()
        assertEquals(TextDecoration.Underline, span.item.textDecoration)
    }

    @Test
    fun `small run maps to a reduced relative font size`() {
        val result = textNodeToAnnotatedString(TextNode(listOf(TextRun("tiny", small = true))))
        val span = result.spanStyles.single()
        // small uses a relative (em) size below 1.0 so it scales with the base style.
        assertTrue(span.item.fontSize.isEm, "small size should be relative (em)")
        assertTrue(span.item.fontSize.value < 1f, "small size should shrink the run")
    }

    @Test
    fun `multiple styles on one run collapse to a single span`() {
        val result = textNodeToAnnotatedString(
            TextNode(listOf(TextRun("x", b = true, i = true, mono = true, strike = true))),
        )
        val span = result.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(1, span.end)
        assertEquals(FontWeight.Bold, span.item.fontWeight)
        assertEquals(FontStyle.Italic, span.item.fontStyle)
        assertEquals(FontFamily.Monospace, span.item.fontFamily)
        assertEquals(TextDecoration.LineThrough, span.item.textDecoration)
    }

    @Test
    fun `multi run node yields one span per styled run with correct offsets`() {
        val result = textNodeToAnnotatedString(
            TextNode(
                listOf(
                    TextRun("plain "),
                    TextRun("bold", b = true),
                    TextRun(" and "),
                    TextRun("italic", i = true),
                ),
            ),
        )
        assertEquals("plain bold and italic", result.text)
        assertEquals(2, result.spanStyles.size)

        val bold = result.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals(6, bold.start)
        assertEquals(10, bold.end)

        val italic = result.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals(15, italic.start)
        assertEquals(21, italic.end)
    }

    @Test
    fun `bidi isolate characters pass through unmodified`() {
        // U+2068 FIRST STRONG ISOLATE, U+2069 POP DIRECTIONAL ISOLATE.
        val labelled = "⁨Париж⁩"
        val result = textNodeToAnnotatedString(TextNode(listOf(TextRun(labelled))))
        assertEquals(labelled, result.text)
        assertTrue(result.text.startsWith('⁨'))
        assertTrue(result.text.endsWith('⁩'))
    }

    @Test
    fun `unstyled runs between styled runs produce no spans`() {
        val result = textNodeToAnnotatedString(
            TextNode(listOf(TextRun("a", b = true), TextRun("b"), TextRun("c", i = true))),
        )
        assertEquals("abc", result.text)
        assertEquals(2, result.spanStyles.size)
        assertNull(
            result.spanStyles.firstOrNull { it.start == 1 && it.end == 2 },
            "middle plain run should not emit a span",
        )
    }

    @Test
    fun `empty runs list yields empty string`() {
        val result = textNodeToAnnotatedString(TextNode(emptyList()))
        assertEquals("", result.text)
        assertTrue(result.spanStyles.isEmpty())
    }
}
