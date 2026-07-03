package com.dvdutch.recall.ui

import com.dvdutch.recall.api.OcclusionShapeState
import com.dvdutch.recall.api.Pt
import com.dvdutch.recall.api.ShapeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit coverage for the pure, Android-runtime-free scaling / coordinate math behind
 * [OcclusionImage]: the Fit transform (uniform scale + letterbox offset) and the
 * per-shape natural→screen mapping for rect/ellipse/polygon, plus the degenerate
 * natural-size guard. The Compose drawing itself is emulator-verified in Task 3.
 */
class OcclusionScaleTest {

    private val eps = 1e-4f

    // --- fitTransform: scale + letterbox ------------------------------------

    @Test
    fun squareImageIntoSquareBox_scalesNoLetterbox() {
        val t = fitTransform(naturalW = 100, naturalH = 100, displayedW = 400f, displayedH = 400f)
        assertEquals(4f, t.scale, eps)
        assertEquals(0f, t.offsetX, eps)
        assertEquals(0f, t.offsetY, eps)
    }

    @Test
    fun wideImageIntoSquareBox_fitsWidth_letterboxesVertically() {
        // 200x100 into 400x400 → width ratio 2, height ratio 4 → scale = min = 2.
        val t = fitTransform(naturalW = 200, naturalH = 100, displayedW = 400f, displayedH = 400f)
        assertEquals(2f, t.scale, eps)
        assertEquals(0f, t.offsetX, eps) // width fills the box
        // drawn height = 100*2 = 200; box 400 → (400-200)/2 = 100 top inset.
        assertEquals(100f, t.offsetY, eps)
    }

    @Test
    fun tallImageIntoSquareBox_fitsHeight_letterboxesHorizontally() {
        // 100x200 into 400x400 → width ratio 4, height ratio 2 → scale = 2.
        val t = fitTransform(naturalW = 100, naturalH = 200, displayedW = 400f, displayedH = 400f)
        assertEquals(2f, t.scale, eps)
        // drawn width = 100*2 = 200; box 400 → (400-200)/2 = 100 left inset.
        assertEquals(100f, t.offsetX, eps)
        assertEquals(0f, t.offsetY, eps)
    }

    @Test
    fun fillWidthBoxAtNaturalAspect_scalesUniformlyNoLetterbox() {
        // The production box: width fixed, height = width/aspect → exact fit, no inset.
        // 640x480 (aspect 4:3) into 320 wide → box height = 320*480/640 = 240.
        val t = fitTransform(naturalW = 640, naturalH = 480, displayedW = 320f, displayedH = 240f)
        assertEquals(0.5f, t.scale, eps)
        assertEquals(0f, t.offsetX, eps)
        assertEquals(0f, t.offsetY, eps)
    }

    // --- fitTransform: degenerate guards ------------------------------------

    @Test
    fun zeroNaturalWidth_yieldsZeroScale() {
        val t = fitTransform(naturalW = 0, naturalH = 480, displayedW = 320f, displayedH = 240f)
        assertEquals(0f, t.scale, eps)
        assertEquals(0f, t.offsetX, eps)
        assertEquals(0f, t.offsetY, eps)
    }

    @Test
    fun zeroNaturalHeight_yieldsZeroScale() {
        val t = fitTransform(naturalW = 640, naturalH = 0, displayedW = 320f, displayedH = 240f)
        assertEquals(0f, t.scale, eps)
    }

    @Test
    fun zeroDisplayedSize_yieldsZeroScale() {
        val t = fitTransform(naturalW = 640, naturalH = 480, displayedW = 0f, displayedH = 0f)
        assertEquals(0f, t.scale, eps)
    }

    // --- scaleShape: rect ---------------------------------------------------

    @Test
    fun scalesRect_multipliesCoordsAndAddsOffset() {
        val t = FitTransform(scale = 2f, offsetX = 10f, offsetY = 5f)
        val shape = OcclusionShapeState.Rect(
            left = 10.0, top = 20.0, width = 100.0, height = 80.0,
            state = ShapeState.MASKED,
        )
        val scaled = scaleShape(shape, t)
        assertIs<ScaledShape.Rect>(scaled)
        assertEquals(10.0 * 2 + 10, scaled.left.toDouble(), 1e-3)
        assertEquals(20.0 * 2 + 5, scaled.top.toDouble(), 1e-3)
        assertEquals(200f, scaled.width, eps)
        assertEquals(160f, scaled.height, eps)
        assertEquals(ShapeState.MASKED, scaled.state)
    }

    // --- scaleShape: ellipse ------------------------------------------------

    @Test
    fun scalesEllipse_usesBoundingBox_dropsRedundantRadii() {
        val t = FitTransform(scale = 0.5f, offsetX = 0f, offsetY = 0f)
        val shape = OcclusionShapeState.Ellipse(
            left = 200.0, top = 50.0, width = 60.0, height = 40.0, rx = 30.0, ry = 20.0,
            state = ShapeState.REVEALED_OUTLINE,
        )
        val scaled = scaleShape(shape, t)
        assertIs<ScaledShape.Ellipse>(scaled)
        assertEquals(100f, scaled.left, eps)
        assertEquals(25f, scaled.top, eps)
        assertEquals(30f, scaled.width, eps)
        assertEquals(20f, scaled.height, eps)
        assertEquals(ShapeState.REVEALED_OUTLINE, scaled.state)
    }

    // --- scaleShape: polygon ------------------------------------------------

    @Test
    fun scalesPolygon_mapsEveryVertex() {
        val t = FitTransform(scale = 3f, offsetX = 1f, offsetY = 2f)
        val shape = OcclusionShapeState.Polygon(
            points = listOf(Pt(10.0, 10.0), Pt(60.0, 10.0), Pt(35.0, 50.0)),
            state = ShapeState.CONTEXT,
        )
        val scaled = scaleShape(shape, t)
        assertIs<ScaledShape.Polygon>(scaled)
        assertEquals(3, scaled.points.size)
        assertEquals(31f, scaled.points[0].first, eps) // 10*3+1
        assertEquals(32f, scaled.points[0].second, eps) // 10*3+2
        assertEquals(181f, scaled.points[1].first, eps) // 60*3+1
        assertEquals(106f, scaled.points[2].first, eps) // 35*3+1
        assertEquals(152f, scaled.points[2].second, eps) // 50*3+2
        assertEquals(ShapeState.CONTEXT, scaled.state)
    }

    @Test
    fun scalesEmptyPolygon_yieldsNoVertices() {
        val t = FitTransform(scale = 2f, offsetX = 0f, offsetY = 0f)
        val shape = OcclusionShapeState.Polygon(points = emptyList(), state = ShapeState.MASKED)
        val scaled = scaleShape(shape, t)
        assertIs<ScaledShape.Polygon>(scaled)
        assertTrue(scaled.points.isEmpty())
    }
}
