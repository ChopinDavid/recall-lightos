package com.dvdutch.recall.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Large card images are decoded downsampled ([decodeSampleSize]). */
class DecodeSampleSizeTest {

    @Test
    fun `small and unknown images decode at full size`() {
        assertEquals(1, decodeSampleSize(1080, 1240))
        assertEquals(1, decodeSampleSize(2048, 2048))
        assertEquals(1, decodeSampleSize(0, 0))
    }

    @Test
    fun `a 12 megapixel photo is halved to fit`() {
        assertEquals(2, decodeSampleSize(4032, 3024)) // -> 2016 x 1512
    }

    @Test
    fun `very large or very tall images keep halving`() {
        assertEquals(4, decodeSampleSize(8000, 6000)) // -> 2000 x 1500
        assertEquals(8, decodeSampleSize(1000, 12000)) // -> 125 x 1500
    }
}
