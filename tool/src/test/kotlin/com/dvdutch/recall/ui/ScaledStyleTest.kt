package com.dvdutch.recall.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 1 — [scaledBy] multiplies a copy style's fontSize and lineHeight by the bounded
 * per-node scale (a no-op at 1). Pure, Compose-runtime-free.
 */
class ScaledStyleTest {

    @Test
    fun scaleOneIsIdentity() {
        val s = TextStyle(fontSize = 30.sp, lineHeight = 45.sp)
        assertEquals(s, s.scaledBy(1f))
    }

    @Test
    fun scalesFontAndLineHeight() {
        val s = TextStyle(fontSize = 30.sp, lineHeight = 45.sp).scaledBy(0.5f)
        assertEquals(15f, s.fontSize.value, 1e-4f)
        assertEquals(22.5f, s.lineHeight.value, 1e-4f)
    }

    @Test
    fun growsAboveOne() {
        val s = TextStyle(fontSize = 20.sp, lineHeight = 30.sp).scaledBy(1.5f)
        assertEquals(30f, s.fontSize.value, 1e-4f)
        assertEquals(45f, s.lineHeight.value, 1e-4f)
    }
}
