package com.dvdutch.recall.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for [occlusionImageState], the pure resolver that maps the two
 * observable load signals (resolved bitmap + producer-completed flag) onto the three
 * [OcclusionImageState]s.
 *
 * This is the load-bearing logic behind the mask-confusion fix: a still-loading null
 * ([OcclusionImageState.Loading]) must be told apart from a resolved null
 * ([OcclusionImageState.Failed]), because both drive a *bordered text* placeholder that
 * must never be a solid grey fill mistakable for an occlusion mask. The Compose drawing
 * of that placeholder is emulator-verified.
 */
class OcclusionImageStateTest {

    /** Minimal [ImageBitmap] stub — occlusionImageState only checks non-null identity. */
    private val fakeBitmap = object : ImageBitmap {
        override val width: Int = 1
        override val height: Int = 1
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha: Boolean = false
        override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888
        override fun prepareToDraw() = Unit
        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = Unit
    }

    @Test
    fun nullBitmapNotYetCompleted_isLoading() {
        assertEquals(
            OcclusionImageState.Loading,
            occlusionImageState(bitmap = null, loadCompleted = false),
        )
    }

    @Test
    fun nullBitmapAfterCompletion_isFailed() {
        assertEquals(
            OcclusionImageState.Failed,
            occlusionImageState(bitmap = null, loadCompleted = true),
        )
    }

    @Test
    fun resolvedBitmap_isLoaded_regardlessOfCompletionFlag() {
        assertEquals(
            OcclusionImageState.Loaded,
            occlusionImageState(bitmap = fakeBitmap, loadCompleted = true),
        )
        // A bitmap present before the flag flips (shouldn't happen, but be robust) is
        // still Loaded — a decoded image is never a placeholder.
        assertEquals(
            OcclusionImageState.Loaded,
            occlusionImageState(bitmap = fakeBitmap, loadCompleted = false),
        )
    }
}
