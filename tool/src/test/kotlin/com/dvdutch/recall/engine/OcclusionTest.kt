package com.dvdutch.recall.engine

import com.dvdutch.recall.api.OcclusionShapeState
import com.dvdutch.recall.api.Pt
import com.dvdutch.recall.api.ShapeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the occlusion shape parser and per-side state resolver — the
 * correctness core. No backend, no Android runtime.
 */
class OcclusionTest {

    // ---- parseShape -----------------------------------------------------------

    @Test
    fun `parseShape reads a rect`() {
        val s = parseShape(
            "rect",
            mapOf("left" to "10", "top" to "20", "width" to "100", "height" to "80", "oi" to "1"),
            ShapeState.MASKED,
        )
        assertEquals(
            OcclusionShapeState.Rect(10.0, 20.0, 100.0, 80.0, ShapeState.MASKED),
            s,
        )
    }

    @Test
    fun `parseShape reads an ellipse with radii`() {
        val s = parseShape(
            "ellipse",
            mapOf(
                "left" to "200", "top" to "50", "width" to "60", "height" to "40",
                "rx" to "30", "ry" to "20",
            ),
            ShapeState.CONTEXT,
        )
        assertEquals(
            OcclusionShapeState.Ellipse(200.0, 50.0, 60.0, 40.0, 30.0, 20.0, ShapeState.CONTEXT),
            s,
        )
    }

    @Test
    fun `parseShape reads a polygon points list`() {
        val s = parseShape(
            "polygon",
            mapOf("points" to "10,10 60,10 35,50"),
            ShapeState.REVEALED_OUTLINE,
        )
        assertEquals(
            OcclusionShapeState.Polygon(
                listOf(Pt(10.0, 10.0), Pt(60.0, 10.0), Pt(35.0, 50.0)),
                ShapeState.REVEALED_OUTLINE,
            ),
            s,
        )
    }

    @Test
    fun `parseShape tolerates decimals and extra whitespace in polygon points`() {
        val s = parseShape("polygon", mapOf("points" to "  1.5,2.5   3,4  "), ShapeState.MASKED)
                as OcclusionShapeState.Polygon
        assertEquals(listOf(Pt(1.5, 2.5), Pt(3.0, 4.0)), s.points)
    }

    @Test
    fun `parseShape skips text shapes in v1`() {
        assertNull(parseShape("text", mapOf("text" to "label", "left" to "5"), ShapeState.MASKED))
    }

    @Test
    fun `parseShape skips an unknown shape kind`() {
        assertNull(parseShape("triangle", mapOf("left" to "1"), ShapeState.MASKED))
    }

    @Test
    fun `parseShape skips a shape with missing required props`() {
        // A rect missing height cannot be drawn — drop it rather than guess.
        assertNull(parseShape("rect", mapOf("left" to "1", "top" to "2", "width" to "3"), ShapeState.MASKED))
    }

    // ---- resolveState (the table) --------------------------------------------
    // tested ordinal = card.ord + 1. A shape's ordinal is the tested one when equal.

    // Front, tested shape → MASKED_TESTED (both hide modes): the tested mask must be
    // visually distinct from inactive masks so the studier knows WHICH region to recall.
    @Test
    fun `front tested is masked-tested hide-one`() =
        assertEquals(ShapeState.MASKED_TESTED, resolveState(tested = 1, shape = 1, isBack = false, occludeInactive = false))

    @Test
    fun `front tested is masked-tested hide-all`() =
        assertEquals(ShapeState.MASKED_TESTED, resolveState(tested = 1, shape = 1, isBack = false, occludeInactive = true))

    // Front, inactive shape → CONTEXT (hide-one) / MASKED (hide-all).
    @Test
    fun `front inactive is context hide-one`() =
        assertEquals(ShapeState.CONTEXT, resolveState(tested = 1, shape = 2, isBack = false, occludeInactive = false))

    @Test
    fun `front inactive is masked hide-all`() =
        assertEquals(ShapeState.MASKED, resolveState(tested = 1, shape = 2, isBack = false, occludeInactive = true))

    // Back, tested shape → REVEALED_OUTLINE (both hide modes).
    @Test
    fun `back tested is revealed outline hide-one`() =
        assertEquals(ShapeState.REVEALED_OUTLINE, resolveState(tested = 1, shape = 1, isBack = true, occludeInactive = false))

    @Test
    fun `back tested is revealed outline hide-all`() =
        assertEquals(ShapeState.REVEALED_OUTLINE, resolveState(tested = 1, shape = 1, isBack = true, occludeInactive = true))

    // Back, inactive shape → CONTEXT (hide-one) / MASKED (hide-all).
    @Test
    fun `back inactive is context hide-one`() =
        assertEquals(ShapeState.CONTEXT, resolveState(tested = 2, shape = 1, isBack = true, occludeInactive = false))

    @Test
    fun `back inactive is masked hide-all`() =
        assertEquals(ShapeState.MASKED, resolveState(tested = 2, shape = 1, isBack = true, occludeInactive = true))

    // ---- resolveShapes over a whole note -------------------------------------

    private fun note() = ParsedOcclusion(
        occludeInactive = true,
        occlusions = listOf(
            RawOcclusion(ordinal = 1, shapes = listOf(RawShape("rect", mapOf("left" to "10", "top" to "20", "width" to "100", "height" to "80")))),
            RawOcclusion(ordinal = 2, shapes = listOf(RawShape("ellipse", mapOf("left" to "200", "top" to "50", "width" to "60", "height" to "40", "rx" to "30", "ry" to "20")))),
            RawOcclusion(ordinal = 3, shapes = listOf(RawShape("polygon", mapOf("points" to "10,10 60,10 35,50")))),
        ),
    )

    @Test
    fun `resolveShapes front hide-all marks tested distinct from inactive masks`() {
        val out = resolveShapes(note(), testedOrdinal = 2, isBack = false)
        assertEquals(3, out.size)
        // ordinal 2 is the ellipse (tested) → MASKED_TESTED (distinct); others MASKED (hide-all).
        val ellipse = out.filterIsInstance<OcclusionShapeState.Ellipse>().single()
        assertEquals(ShapeState.MASKED_TESTED, ellipse.state)
        assertTrue(
            out.filter { it !is OcclusionShapeState.Ellipse }.all { it.state == ShapeState.MASKED },
            "hide-all front: inactive masks stay plain MASKED, got $out",
        )
    }

    @Test
    fun `resolveShapes back hide-all reveals tested keeps others masked`() {
        val out = resolveShapes(note(), testedOrdinal = 2, isBack = true)
        val ellipse = out.filterIsInstance<OcclusionShapeState.Ellipse>().single()
        assertEquals(ShapeState.REVEALED_OUTLINE, ellipse.state)
        assertTrue(
            out.filter { it !is OcclusionShapeState.Ellipse }.all { it.state == ShapeState.MASKED },
            "hide-all back: inactive stay masked, got $out",
        )
    }

    @Test
    fun `resolveShapes drops unparseable shapes but keeps ordinals aligned`() {
        val n = ParsedOcclusion(
            occludeInactive = false,
            occlusions = listOf(
                RawOcclusion(1, listOf(RawShape("text", mapOf("text" to "x")))),
                RawOcclusion(2, listOf(RawShape("rect", mapOf("left" to "0", "top" to "0", "width" to "5", "height" to "5")))),
            ),
        )
        val out = resolveShapes(n, testedOrdinal = 2, isBack = false)
        // The text shape is skipped, so only the rect survives, and it is the tested one.
        assertEquals(1, out.size)
        assertEquals(ShapeState.MASKED_TESTED, out.single().state)
    }

    // ---- fractional-vs-pixel coordinate normalisation -------------------------
    // The "Image Occlusion Enhanced" / "Image Occlusion+" notetype (the dominant format
    // med students use) stores coords as FRACTIONAL RATIOS 0..1 of the image, not pixels.
    // Detection rule (evidence-based across real IO+ decks): if EVERY numeric coordinate
    // in the note is <= 1.0, the note is fractional → multiply by natural dims to pixels.

    private fun fractionalNote() = ParsedOcclusion(
        occludeInactive = false,
        occlusions = listOf(
            RawOcclusion(1, listOf(RawShape("rect", mapOf("left" to ".1363", "top" to ".0512", "width" to ".1882", "height" to ".033")))),
            RawOcclusion(2, listOf(RawShape("ellipse", mapOf("left" to ".2", "top" to ".3", "width" to ".1", "height" to ".05", "rx" to ".05", "ry" to ".025")))),
            RawOcclusion(3, listOf(RawShape("polygon", mapOf("points" to ".1,.1 .5,.1 .3,.5")))),
        ),
    )

    /** All-fractional note with natural 1550x1240 → resolved shapes must be in PIXELS. */
    @Test
    fun `resolveShapes scales fractional coords to pixels`() {
        val out = resolveShapes(fractionalNote(), testedOrdinal = 1, isBack = false, naturalW = 1550, naturalH = 1240)
        val rect = out.filterIsInstance<OcclusionShapeState.Rect>().single()
        // left = .1363 * 1550 ≈ 211.3, top = .0512 * 1240 ≈ 63.5, width = .1882 * 1550 ≈ 291.7, height = .033 * 1240 ≈ 40.9
        assertEquals(211.265, rect.left, 0.01)
        assertEquals(63.488, rect.top, 0.01)
        assertEquals(291.71, rect.width, 0.01)
        assertEquals(40.92, rect.height, 0.01)

        val ell = out.filterIsInstance<OcclusionShapeState.Ellipse>().single()
        assertEquals(0.2 * 1550, ell.left, 0.01)
        assertEquals(0.3 * 1240, ell.top, 0.01)
        assertEquals(0.1 * 1550, ell.width, 0.01)
        assertEquals(0.05 * 1240, ell.height, 0.01)
        assertEquals(0.05 * 1550, ell.rx, 0.01)
        assertEquals(0.025 * 1240, ell.ry, 0.01)

        val poly = out.filterIsInstance<OcclusionShapeState.Polygon>().single()
        assertEquals(
            listOf(
                Pt(0.1 * 1550, 0.1 * 1240),
                Pt(0.5 * 1550, 0.1 * 1240),
                Pt(0.3 * 1550, 0.5 * 1240),
            ),
            poly.points,
        )
    }

    /** A pixel note (coords > 1) is left UNCHANGED regardless of natural dims. */
    @Test
    fun `resolveShapes leaves pixel coords unchanged`() {
        val n = ParsedOcclusion(
            occludeInactive = false,
            occlusions = listOf(
                RawOcclusion(1, listOf(RawShape("rect", mapOf("left" to "150", "top" to "50", "width" to "200", "height" to "80")))),
            ),
        )
        val out = resolveShapes(n, testedOrdinal = 1, isBack = false, naturalW = 640, naturalH = 480)
        val rect = out.filterIsInstance<OcclusionShapeState.Rect>().single()
        assertEquals(150.0, rect.left, 0.0)
        assertEquals(50.0, rect.top, 0.0)
        assertEquals(200.0, rect.width, 0.0)
        assertEquals(80.0, rect.height, 0.0)
    }

    /** Classification boundary: max coord exactly 1.0 → fractional; 1.5 → pixels. */
    @Test
    fun `resolveShapes classification boundary at one`() {
        val atOne = ParsedOcclusion(
            occludeInactive = false,
            occlusions = listOf(RawOcclusion(1, listOf(RawShape("rect", mapOf("left" to "0.5", "top" to "0.5", "width" to "0.5", "height" to "1.0"))))),
        )
        // max coord == 1.0 → fractional → scaled.
        val scaled = resolveShapes(atOne, testedOrdinal = 1, isBack = false, naturalW = 1000, naturalH = 1000)
            .filterIsInstance<OcclusionShapeState.Rect>().single()
        assertEquals(500.0, scaled.left, 0.0)
        assertEquals(1000.0, scaled.height, 0.0)

        val overOne = ParsedOcclusion(
            occludeInactive = false,
            occlusions = listOf(RawOcclusion(1, listOf(RawShape("rect", mapOf("left" to "0.5", "top" to "0.5", "width" to "0.5", "height" to "1.5"))))),
        )
        // any coord > 1.0 → pixels → unchanged.
        val unchanged = resolveShapes(overOne, testedOrdinal = 1, isBack = false, naturalW = 1000, naturalH = 1000)
            .filterIsInstance<OcclusionShapeState.Rect>().single()
        assertEquals(0.5, unchanged.left, 0.0)
        assertEquals(1.5, unchanged.height, 0.0)
    }

    /** Mixed-magnitude fractional note: a .2 shape and a .9 shape both scale correctly. */
    @Test
    fun `resolveShapes scales mixed-magnitude fractional shapes uniformly`() {
        val n = ParsedOcclusion(
            occludeInactive = false,
            occlusions = listOf(
                RawOcclusion(1, listOf(RawShape("rect", mapOf("left" to "0.2", "top" to "0.2", "width" to "0.1", "height" to "0.1")))),
                RawOcclusion(2, listOf(RawShape("rect", mapOf("left" to "0.9", "top" to "0.9", "width" to "0.05", "height" to "0.05")))),
            ),
        )
        val out = resolveShapes(n, testedOrdinal = 1, isBack = false, naturalW = 1000, naturalH = 500)
        val rects = out.filterIsInstance<OcclusionShapeState.Rect>().sortedBy { it.left }
        assertEquals(200.0, rects[0].left, 0.0)
        assertEquals(100.0, rects[0].top, 0.0)
        assertEquals(900.0, rects[1].left, 0.0)
        assertEquals(450.0, rects[1].top, 0.0)
    }

    /** With unknown natural dims (0), fractional coords cannot be scaled → left as-is. */
    @Test
    fun `resolveShapes leaves fractional coords untouched when natural dims unknown`() {
        val out = resolveShapes(fractionalNote(), testedOrdinal = 1, isBack = false, naturalW = 0, naturalH = 0)
        val rect = out.filterIsInstance<OcclusionShapeState.Rect>().single()
        assertEquals(0.1363, rect.left, 1e-9)
    }
}
