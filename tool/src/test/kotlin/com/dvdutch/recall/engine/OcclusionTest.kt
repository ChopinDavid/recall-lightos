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

    // Front, tested shape → always MASKED (both hide modes).
    @Test
    fun `front tested is masked hide-one`() =
        assertEquals(ShapeState.MASKED, resolveState(tested = 1, shape = 1, isBack = false, occludeInactive = false))

    @Test
    fun `front tested is masked hide-all`() =
        assertEquals(ShapeState.MASKED, resolveState(tested = 1, shape = 1, isBack = false, occludeInactive = true))

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
    fun `resolveShapes front hide-all masks tested and inactive`() {
        val out = resolveShapes(note(), testedOrdinal = 2, isBack = false)
        assertEquals(3, out.size)
        // ordinal 2 is the ellipse (tested) → masked; others masked (hide-all).
        assertTrue(out.all { it.state == ShapeState.MASKED }, "hide-all front: all masked, got $out")
        assertTrue(out.any { it is OcclusionShapeState.Ellipse })
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
        assertEquals(ShapeState.MASKED, out.single().state)
    }
}
