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

    /**
     * Only one dimension known is not enough to scale: the x and y axes are scaled by
     * DIFFERENT factors, so a half-known pair would distort every shape. Both must be > 0.
     */
    @Test
    fun `resolveShapes declines to scale when only one natural dimension is known`() {
        val wOnly = resolveShapes(fractionalNote(), testedOrdinal = 1, isBack = false, naturalW = 1550, naturalH = 0)
            .filterIsInstance<OcclusionShapeState.Rect>().single()
        assertEquals(0.1363, wOnly.left, 1e-9)
        val hOnly = resolveShapes(fractionalNote(), testedOrdinal = 1, isBack = false, naturalW = 0, naturalH = 1240)
            .filterIsInstance<OcclusionShapeState.Rect>().single()
        assertEquals(0.1363, hOnly.left, 1e-9)
    }

    /**
     * A note whose every shape is unparseable has NO coordinates, so it must not be
     * classified fractional (there is nothing to scale, and `all {}` on an empty list is
     * vacuously true — the guard that `coords.isNotEmpty()` exists to defeat).
     */
    @Test
    fun `resolveShapes treats a coordinate-free note as non-fractional`() {
        val n = ParsedOcclusion(
            occludeInactive = false,
            occlusions = listOf(RawOcclusion(1, listOf(RawShape("text", mapOf("text" to "label"))))),
        )
        assertEquals(emptyList(), resolveShapes(n, testedOrdinal = 1, isBack = false, naturalW = 800, naturalH = 600))
    }

    // ---- parseShape rejection paths ------------------------------------------

    @Test
    fun `parseShape skips an ellipse missing its radii`() {
        // Every ellipse prop is required: without rx/ry there is no curve to draw.
        val base = mapOf("left" to "1", "top" to "2", "width" to "3", "height" to "4")
        assertNull(parseShape("ellipse", base, ShapeState.MASKED))
        assertNull(parseShape("ellipse", base + ("rx" to "5"), ShapeState.MASKED))
        assertNull(parseShape("ellipse", base + ("ry" to "5"), ShapeState.MASKED))
    }

    @Test
    fun `parseShape skips a shape whose prop is non-numeric`() {
        // A non-numeric value is dropped rather than guessed at.
        assertNull(
            parseShape(
                "rect",
                mapOf("left" to "auto", "top" to "2", "width" to "3", "height" to "4"),
                ShapeState.MASKED,
            ),
        )
    }

    @Test
    fun `parseShape skips a polygon with fewer than two points`() {
        // A single point (or none) cannot form an outline.
        assertNull(parseShape("polygon", mapOf("points" to "10,10"), ShapeState.MASKED))
        assertNull(parseShape("polygon", mapOf("points" to ""), ShapeState.MASKED))
        assertNull(parseShape("polygon", emptyMap(), ShapeState.MASKED))
    }

    @Test
    fun `parseShape drops malformed polygon pairs but keeps the well-formed ones`() {
        // "5" has no comma; "7,8,9" has too many; "a,b" is non-numeric — each pair is
        // judged on its own so one typo doesn't discard the whole shape.
        val s = parseShape(
            "polygon",
            mapOf("points" to "1,2 5 7,8,9 a,b 3,4"),
            ShapeState.CONTEXT,
        ) as OcclusionShapeState.Polygon
        assertEquals(listOf(Pt(1.0, 2.0), Pt(3.0, 4.0)), s.points)
    }

    @Test
    fun `parseShape drops a polygon left with one good point after malformed pairs`() {
        assertNull(parseShape("polygon", mapOf("points" to "1,2 oops nope"), ShapeState.MASKED))
    }

    // ---- imageDims: header sniffing ------------------------------------------
    // Natural dims decide whether fractional coords can be scaled (above), and Android's
    // BitmapFactory is unavailable in unit tests — so the sniffer is pure and must read
    // each format's header exactly. Fixtures are minimal real headers, byte for byte.

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun be16(n: Int) = listOf((n shr 8) and 0xFF, n and 0xFF)
    private fun le16(n: Int) = listOf(n and 0xFF, (n shr 8) and 0xFF)

    /** PNG: 8-byte signature, IHDR length+type, then big-endian width/height. */
    private fun png(w: Int, h: Int): ByteArray = bytes(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        *(be16(0) + be16(w) + be16(0) + be16(h)).toIntArray(),
    )

    private fun List<Int>.toIntArray(): IntArray = IntArray(size) { this[it] }

    @Test
    fun `imageDims reads PNG dimensions`() {
        assertEquals(ImageDims(1550, 1240), imageDims(png(1550, 1240)))
    }

    @Test
    fun `imageDims rejects a truncated PNG header`() {
        // IHDR dims live at offsets 16..23; anything shorter cannot be read.
        assertNull(imageDims(png(100, 100).copyOf(23)))
    }

    @Test
    fun `imageDims rejects bytes with a corrupt PNG signature`() {
        val b = png(100, 100)
        b[3] = 0x00 // 'G' of "PNG" clobbered
        assertNull(imageDims(b))
    }

    @Test
    fun `imageDims reads GIF dimensions little-endian`() {
        // "GIF89a" then little-endian width/height at offsets 6/8.
        val gif = bytes(
            'G'.code, 'I'.code, 'F'.code, '8'.code, '9'.code, 'a'.code,
            *(le16(640) + le16(480)).toIntArray(),
        )
        assertEquals(ImageDims(640, 480), imageDims(gif))
    }

    @Test
    fun `imageDims reads a GIF87a header too`() {
        val gif = bytes(
            'G'.code, 'I'.code, 'F'.code, '8'.code, '7'.code, 'a'.code,
            *(le16(11) + le16(22)).toIntArray(),
        )
        assertEquals(ImageDims(11, 22), imageDims(gif))
    }

    @Test
    fun `imageDims rejects a truncated GIF header`() {
        assertNull(imageDims(bytes('G'.code, 'I'.code, 'F'.code, '8'.code, '9'.code, 'a'.code, 0x80, 0x02)))
    }

    /** Builds a 30-byte RIFF/WEBP header for the given sub-format tail. */
    private fun webp(fourthCc: List<Int>, tail: List<Int>): ByteArray {
        val head = listOf(
            'R'.code, 'I'.code, 'F'.code, 'F'.code, 0, 0, 0, 0,
            'W'.code, 'E'.code, 'B'.code, 'P'.code,
        ) + fourthCc
        val all = (head + tail).toMutableList()
        while (all.size < 30) all.add(0)
        return bytes(*all.toIntArray())
    }

    @Test
    fun `imageDims reads lossy VP8 webp dimensions masking the flag bits`() {
        // "VP8 ": 14-bit width/height at offsets 26/28; the top 2 bits are scale flags
        // and must be masked off, so 0xC000-set bits must NOT leak into the size.
        val tail = MutableList(14) { 0 } // offsets 16..29
        val w = 320 or 0xC000 // upper 2 bits set — pure scale flags
        val h = 240 or 0xC000
        tail[10] = w and 0xFF; tail[11] = (w shr 8) and 0xFF   // offsets 26,27
        tail[12] = h and 0xFF; tail[13] = (h shr 8) and 0xFF   // offsets 28,29
        val b = webp(listOf('V'.code, 'P'.code, '8'.code, ' '.code), tail)
        assertEquals(ImageDims(320, 240), imageDims(b))
    }

    @Test
    fun `imageDims reads lossless VP8L webp dimensions as width minus one packed`() {
        // "VP8L": 14-bit (width-1) then 14-bit (height-1) packed from offset 21.
        val bits = (800 - 1) or ((600 - 1) shl 14)
        val tail = MutableList(14) { 0 }
        for (k in 0..3) tail[5 + k] = (bits shr (8 * k)) and 0xFF // offsets 21..24
        val b = webp(listOf('V'.code, 'P'.code, '8'.code, 'L'.code), tail)
        assertEquals(ImageDims(800, 600), imageDims(b))
    }

    @Test
    fun `imageDims reads extended VP8X webp canvas dimensions`() {
        // "VP8X": 24-bit (canvas width-1) at offset 24, (height-1) at offset 27.
        val tail = MutableList(14) { 0 }
        val w = 4096 - 1
        val h = 2160 - 1
        for (k in 0..2) tail[8 + k] = (w shr (8 * k)) and 0xFF  // offsets 24..26
        for (k in 0..2) tail[11 + k] = (h shr (8 * k)) and 0xFF // offsets 27..29
        val b = webp(listOf('V'.code, 'P'.code, '8'.code, 'X'.code), tail)
        assertEquals(ImageDims(4096, 2160), imageDims(b))
    }

    @Test
    fun `imageDims rejects a RIFF container that is not WEBP`() {
        // A WAV file is also RIFF; it must not be sniffed as an image.
        val b = webp(listOf('f'.code, 'm'.code, 't'.code, ' '.code), List(14) { 0 })
        b[8] = 'W'.code.toByte(); b[9] = 'A'.code.toByte()
        b[10] = 'V'.code.toByte(); b[11] = 'E'.code.toByte()
        assertNull(imageDims(b))
    }

    @Test
    fun `imageDims rejects an unknown WEBP sub-format`() {
        assertNull(imageDims(webp(listOf('V'.code, 'P'.code, '9'.code, '?'.code), List(14) { 0 })))
    }

    @Test
    fun `imageDims rejects a truncated WEBP header`() {
        assertNull(imageDims(webp(listOf('V'.code, 'P'.code, '8'.code, ' '.code), List(14) { 0 }).copyOf(29)))
    }

    /**
     * JPEG: SOI, then markers walked until an SOFn frame header, whose payload carries
     * height then width (in that order — the one easy field to transpose).
     */
    private fun jpeg(segments: List<List<Int>>, sofMarker: Int, w: Int, h: Int): ByteArray {
        val out = mutableListOf(0xFF, 0xD8)
        for (seg in segments) out.addAll(seg)
        // SOFn: marker, length (8), precision, height, width, components
        out.addAll(listOf(0xFF, sofMarker) + be16(8) + listOf(8) + be16(h) + be16(w) + listOf(1))
        return bytes(*out.toIntArray())
    }

    @Test
    fun `imageDims reads baseline JPEG dimensions height before width`() {
        assertEquals(ImageDims(1024, 768), imageDims(jpeg(emptyList(), 0xC0, 1024, 768)))
    }

    @Test
    fun `imageDims reads progressive JPEG SOF2 dimensions`() {
        assertEquals(ImageDims(640, 400), imageDims(jpeg(emptyList(), 0xC2, 640, 400)))
    }

    @Test
    fun `imageDims skips JPEG APP and DHT segments to reach the frame header`() {
        // A real JPEG opens with APP0/JFIF and usually a DHT; both are length-skipped.
        // DHT (0xC4) sits inside the 0xC0..0xCF range and must NOT be read as a frame.
        val app0 = listOf(0xFF, 0xE0) + be16(16) + List(14) { 0 }
        val dht = listOf(0xFF, 0xC4) + be16(6) + List(4) { 0 }
        assertEquals(ImageDims(200, 100), imageDims(jpeg(listOf(app0, dht), 0xC0, 200, 100)))
    }

    @Test
    fun `imageDims ignores JPEG fill bytes before a marker`() {
        // 0xFF padding may precede a marker; the walker must skip the run, not mis-read it.
        val padded = listOf(0xFF, 0xFF, 0xFF, 0xE0) + be16(6) + List(4) { 0 }
        assertEquals(ImageDims(50, 25), imageDims(jpeg(listOf(padded), 0xC0, 50, 25)))
    }

    @Test
    fun `imageDims returns null for a JPEG with no frame header`() {
        // SOI plus only APP segments: nothing declares dimensions.
        val app0 = listOf(0xFF, 0xE0) + be16(20) + List(18) { 0 }
        val b = bytes(*(listOf(0xFF, 0xD8) + app0).toIntArray())
        assertNull(imageDims(b))
    }

    @Test
    fun `imageDims returns null for a JPEG segment with a nonsense length`() {
        // A declared length < 2 cannot advance the walker — bail rather than loop.
        val bad = listOf(0xFF, 0xE0, 0x00, 0x00) + List(12) { 0 }
        val b = bytes(*(listOf(0xFF, 0xD8) + bad).toIntArray())
        assertNull(imageDims(b))
    }

    @Test
    fun `imageDims returns null for empty and non-image bytes`() {
        assertNull(imageDims(ByteArray(0)))
        assertNull(imageDims("not an image at all, just prose".toByteArray()))
    }
}
