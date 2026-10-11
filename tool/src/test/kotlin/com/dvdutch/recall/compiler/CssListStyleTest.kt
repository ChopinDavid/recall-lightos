package com.dvdutch.recall.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CssListStyleTest {

    @Test
    fun `simple selectors declare a type by specificity`() {
        val css = """
            ul { list-style-type: square }
            .a { list-style-type: circle }
            ol.x { list-style: upper-roman outside }
            #i { list-style-type: none !important }
            @media print { li { list-style-type: none } }
            ul li { list-style-type: disc }
        """.trimIndent()
        val sheet = CssListStyle.parse(css)
        assertEquals(ListStyleType.SQUARE, sheet.typeFor("ul", emptyList(), ""))
        assertEquals(ListStyleType.CIRCLE, sheet.typeFor("ul", listOf("a"), ""))
        assertEquals(ListStyleType.UPPER_ROMAN, sheet.typeFor("ol", listOf("a", "x"), ""))
        assertEquals(ListStyleType.NONE, sheet.typeFor("ol", listOf("x"), "i"))
        // Combinators and at-rule blocks are not honored.
        assertNull(sheet.typeFor("li", emptyList(), ""))
        assertNull(CssListStyle.parse("").typeFor("ul", emptyList(), ""))
    }

    @Test
    fun `a declaration block yields its last known keyword`() {
        assertEquals(ListStyleType.DECIMAL, CssListStyle.declaredType("list-style: inside url(x.png) decimal"))
        assertEquals(ListStyleType.NONE, CssListStyle.declaredType("list-style-type: disc; list-style-type: none"))
        assertNull(CssListStyle.declaredType("list-style-type: disclosure-closed"))
        assertNull(CssListStyle.declaredType("color: red"))
    }

    @Test
    fun `markers count like CSS`() {
        assertEquals("aa. ", ListStyleType.LOWER_ALPHA.marker(27))
        assertEquals("Z. ", ListStyleType.UPPER_ALPHA.marker(26))
        assertEquals("mcmxciv. ", ListStyleType.LOWER_ROMAN.marker(1994))
        assertEquals("4000. ", ListStyleType.UPPER_ROMAN.marker(4000))
        assertEquals("0. ", ListStyleType.DECIMAL.marker(0))
        assertEquals("", ListStyleType.NONE.marker(3))
        assertEquals(ListStyleType.DISC, ListStyleType.default(ordered = false, depth = 1))
        assertEquals(ListStyleType.CIRCLE, ListStyleType.default(ordered = false, depth = 2))
        assertEquals(ListStyleType.SQUARE, ListStyleType.default(ordered = false, depth = 5))
        assertEquals(ListStyleType.DECIMAL, ListStyleType.default(ordered = true, depth = 3))
    }
}
