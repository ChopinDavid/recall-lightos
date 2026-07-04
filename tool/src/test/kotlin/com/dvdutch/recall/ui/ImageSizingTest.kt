package com.dvdutch.recall.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for the pure sizing decision behind [MediaImage]: how an `<img>` node
 * is fit to the display given the bitmap's natural pixels and any explicit HTML
 * `width`/`height` attributes. This mirrors Anki/AnkiDroid, where a dimensionless
 * image scales to fill the content width (a tiny GIF scales UP, a huge photo scales
 * DOWN), while an image carrying explicit dims keeps that intended aspect. The Compose
 * modifier wiring is emulator-verified; the aspect math is asserted here.
 */
class ImageSizingTest {

    private val eps = 1e-4f

    // --- no explicit dims: always fit to width, natural aspect --------------

    @Test
    fun smallDimensionlessImage_fitsWidthAtNaturalAspect() {
        // A 32x32 element GIF with no <img width/height> must scale UP to the column
        // width, not render as a 32px thumbnail. FitToWidth carries the natural aspect.
        val mode = imageDisplayMode(naturalW = 32, naturalH = 32, explicitW = null, explicitH = null)
        assertEquals(ImageDisplayMode.FitToWidth(aspectRatio = 1f), mode.aspectRounded())
    }

    @Test
    fun largeDimensionlessImage_fitsWidthAtNaturalAspect() {
        // A 2000x1000 photo scales DOWN to the width, preserving its 2:1 aspect.
        val mode = imageDisplayMode(naturalW = 2000, naturalH = 1000, explicitW = null, explicitH = null)
        assertEquals(ImageDisplayMode.FitToWidth(aspectRatio = 2f), mode.aspectRounded())
    }

    @Test
    fun nonSquareDimensionlessImage_usesNaturalAspect() {
        val mode = imageDisplayMode(naturalW = 100, naturalH = 400, explicitW = null, explicitH = null)
        val fit = mode as ImageDisplayMode.FitToWidth
        assertEquals(0.25f, fit.aspectRatio, eps)
    }

    // --- explicit dims: respect the intended aspect, clamp to width ---------

    @Test
    fun explicitDims_useExplicitAspect() {
        // <img width=200 height=100> → 2:1 intended aspect, honoured (clamped into width).
        val mode = imageDisplayMode(naturalW = 32, naturalH = 32, explicitW = 200, explicitH = 100)
        assertEquals(ImageDisplayMode.FitToWidth(aspectRatio = 2f), mode.aspectRounded())
    }

    @Test
    fun explicitDims_ignoreNaturalAspect() {
        // Explicit dims override the bitmap's own aspect entirely.
        val mode = imageDisplayMode(naturalW = 1000, naturalH = 250, explicitW = 100, explicitH = 100)
        assertEquals(ImageDisplayMode.FitToWidth(aspectRatio = 1f), mode.aspectRounded())
    }

    // --- degenerate inputs: fall back safely --------------------------------

    @Test
    fun zeroNaturalHeight_fallsBackToWrap() {
        // An unmeasurable bitmap (0 height) can't yield an aspect; wrap rather than /0.
        val mode = imageDisplayMode(naturalW = 32, naturalH = 0, explicitW = null, explicitH = null)
        assertEquals(ImageDisplayMode.Wrap, mode)
    }

    @Test
    fun explicitZeroHeight_ignoredInFavourOfNatural() {
        // A bogus explicit height (0) is ignored; the natural aspect is used instead.
        val mode = imageDisplayMode(naturalW = 100, naturalH = 200, explicitW = 50, explicitH = 0)
        val fit = mode as ImageDisplayMode.FitToWidth
        assertEquals(0.5f, fit.aspectRatio, eps)
    }

    // --- helper: round aspect so exact float equality is stable in asserts ---

    private fun ImageDisplayMode.aspectRounded(): ImageDisplayMode = when (this) {
        is ImageDisplayMode.FitToWidth ->
            ImageDisplayMode.FitToWidth(aspectRatio = Math.round(aspectRatio * 1000f) / 1000f)
        ImageDisplayMode.Wrap -> this
    }
}
