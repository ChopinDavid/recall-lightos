package com.dvdutch.recall.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fix C — the Russian sentence "Я надéялся…" wraps with a much larger inter-line
 * gap than the English sentence's wrap. ROOT CAUSE: a combining acute accent
 * (U+0301 in "надéялся") extends above the normal ascent, and Compose's DEFAULT
 * line-height distribution lets that first line's box grow to fit the mark, so the
 * wrapped line sits abnormally far below. AnkiDroid absorbs it via CSS
 * `line-height: 1.5`, which forces uniform line boxes.
 *
 * The fix keeps the base copy [TextStyle]'s explicit lineHeight but layers a
 * [LineHeightStyle] with `trim = None` and centered distribution so EVERY line box
 * is the same height regardless of combining marks — uniform wrap gap.
 *
 * [cardCopyStyle] is the (Compose-runtime-free) style factory that applies this,
 * unit-tested here; the on-screen result is verified on the emulator.
 */
class CardTextStyleTest {

    @Test
    fun appliesUniformLineHeightStyle() {
        val style = cardCopyStyle(TextStyle(lineHeight = 45.sp))
        val lhs = style.lineHeightStyle
        assertNotNull(lhs, "card text must carry an explicit LineHeightStyle")
        assertEquals(LineHeightStyle.Trim.None, lhs.trim, "trim must be None so no line box is clipped")
        assertEquals(LineHeightStyle.Alignment.Center, lhs.alignment, "centered distribution keeps line boxes uniform")
    }

    @Test
    fun disablesFontPaddingSoDiacriticsDoNotInflateLineBoxes() {
        // includeFontPadding=false drops the extra font-metric padding that a
        // combining accent otherwise pushes into, so the accented line's advance
        // matches the plain lines (AnkiDroid line-height:1.5 parity).
        val style = cardCopyStyle(TextStyle(lineHeight = 45.sp))
        assertEquals(false, style.platformStyle?.paragraphStyle?.includeFontPadding)
    }

    @Test
    fun preservesExplicitLineHeight() {
        val style = cardCopyStyle(TextStyle(lineHeight = 45.sp))
        assertTrue(style.lineHeight.isSp, "explicit lineHeight is kept so line boxes are uniform")
        assertEquals(45f, style.lineHeight.value)
    }
}
