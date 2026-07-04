package com.dvdutch.recall.engine

import com.dvdutch.recall.api.OcclusionShapeState
import com.dvdutch.recall.api.Pt
import com.dvdutch.recall.api.ShapeState

/**
 * Pure, backend-free image-occlusion logic: shape parsing, per-side state resolution,
 * and image-dimension sniffing. Kept isolated from rslib protos so it is exhaustively
 * unit-testable on the plain JVM (no Android runtime, no native backend).
 *
 * [LocalEngineApi] adapts rslib's `GetImageOcclusionNoteResponse` into the [ParsedOcclusion]
 * shape below, then delegates all correctness-bearing decisions here.
 *
 * ## Semantics (verified against Anki 25.09.x, see occlusion-investigation.md)
 * A note with K shapes yields K cards; `card.ord` (0-based) tests occlusion
 * `ordinal = card.ord + 1`. Per side:
 *   - Front: the tested shape is MASKED_TESTED (hidden but drawn distinct from inactive
 *     masks). Inactive shapes are CONTEXT in hide-one mode (`occludeInactive == false`)
 *     or MASKED in hide-all mode (`occludeInactive == true`).
 *   - Back: the tested shape is REVEALED_OUTLINE. Inactive shapes are CONTEXT (hide-one)
 *     or MASKED (hide-all).
 */

/** Backend-neutral view of one occlusion (a cloze ordinal + its raw shapes). */
data class RawShape(val kind: String, val props: Map<String, String>)

/** One occlusion group: its 1-based [ordinal] and the shapes drawn for it. */
data class RawOcclusion(val ordinal: Int, val shapes: List<RawShape>)

/** Backend-neutral view of the whole note's occlusion payload. */
data class ParsedOcclusion(
    val occludeInactive: Boolean,
    val occlusions: List<RawOcclusion>,
)

/**
 * Parses one raw shape into a typed [OcclusionShapeState] carrying the already-resolved
 * [state], or `null` when the shape cannot be drawn:
 *   - `text` shapes are intentionally skipped in v1 (documented; the rect/ellipse/polygon
 *     majority covers the med-student use case);
 *   - unknown kinds are skipped;
 *   - a shape missing a required numeric prop (or with a non-numeric value) is skipped
 *     rather than guessed.
 *
 * Coordinates are natural image pixels. rslib already unescapes property values
 * (`\:` → `:`), so no further unescaping is needed here.
 */
fun parseShape(kind: String, props: Map<String, String>, state: ShapeState): OcclusionShapeState? =
    when (kind) {
        "rect" -> {
            val l = props.num("left"); val t = props.num("top")
            val w = props.num("width"); val h = props.num("height")
            if (l == null || t == null || w == null || h == null) null
            else OcclusionShapeState.Rect(l, t, w, h, state)
        }
        "ellipse" -> {
            val l = props.num("left"); val t = props.num("top")
            val w = props.num("width"); val h = props.num("height")
            val rx = props.num("rx"); val ry = props.num("ry")
            if (l == null || t == null || w == null || h == null || rx == null || ry == null) null
            else OcclusionShapeState.Ellipse(l, t, w, h, rx, ry, state)
        }
        "polygon" -> {
            val pts = parsePoints(props["points"])
            if (pts.size < 2) null else OcclusionShapeState.Polygon(pts, state)
        }
        // "text" and any unknown kind → skipped in v1.
        else -> null
    }

/** Parses a `"x,y x,y ..."` points string into [Pt]s, dropping malformed pairs. */
private fun parsePoints(raw: String?): List<Pt> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.trim().split(Regex("\\s+")).mapNotNull { pair ->
        val xy = pair.split(",")
        if (xy.size != 2) return@mapNotNull null
        val x = xy[0].trim().toDoubleOrNull() ?: return@mapNotNull null
        val y = xy[1].trim().toDoubleOrNull() ?: return@mapNotNull null
        Pt(x, y)
    }
}

private fun Map<String, String>.num(key: String): Double? = this[key]?.trim()?.toDoubleOrNull()

/**
 * Resolves the draw-state for a single shape given which ordinal is tested this card,
 * which side we are rendering, and the note-level hide mode. This is the whole table.
 */
fun resolveState(tested: Int, shape: Int, isBack: Boolean, occludeInactive: Boolean): ShapeState {
    val isTested = shape == tested
    return when {
        // Front tested → MASKED_TESTED: hidden but visually distinct from inactive masks,
        // so the studier can tell WHICH region is being asked on a many-mask card.
        isTested && !isBack -> ShapeState.MASKED_TESTED
        isTested && isBack -> ShapeState.REVEALED_OUTLINE
        // Inactive shape: hide-all keeps it plainly masked on both sides; hide-one shows context.
        occludeInactive -> ShapeState.MASKED
        else -> ShapeState.CONTEXT
    }
}

/** The numeric-coordinate prop keys that define a shape's geometry (per shape kind). */
private val COORD_KEYS = listOf("left", "top", "width", "height", "rx", "ry")

/**
 * Collects every geometry coordinate in [note] (rect/ellipse props + polygon point
 * components), used to decide fractional-vs-pixel for the whole note at once.
 */
private fun allCoords(note: ParsedOcclusion): List<Double> =
    note.occlusions.flatMap { occ ->
        occ.shapes.flatMap { sh ->
            val fromProps = COORD_KEYS.mapNotNull { sh.props.num(it) }
            val fromPoints = parsePoints(sh.props["points"]).flatMap { listOf(it.x, it.y) }
            fromProps + fromPoints
        }
    }

/**
 * Decides whether a note's shape coordinates are FRACTIONAL ratios (0..1 of the image)
 * rather than absolute natural pixels.
 *
 * The "Image Occlusion Enhanced" / "Image Occlusion+" notetype (the format the med-student
 * majority actually uses) stores every coordinate as a fraction of the image dimension
 * (e.g. `left=.1363 width=.1882`), whereas Anki's modern built-in "Image Occlusion"
 * notetype stores absolute pixels (e.g. `left=150 width=200`).
 *
 * Detection is per-note (a note is uniformly one or the other) and evidence-based: across
 * real IO+ decks the maximum coordinate observed was 0.9439 — i.e. every fractional coord
 * is `<= 1.0`, while a pixel coord for any real image is far larger. So: **if every numeric
 * coordinate in the note is `<= 1.0`, the note is fractional.** A note with no coordinates
 * (all shapes malformed/text) is treated as non-fractional (nothing to scale anyway).
 *
 * Ambiguity note: the only case this misclassifies is a genuine pixel shape whose every
 * coordinate is `<= 1` — i.e. a shape confined to the top-left 1×1 pixel of the image.
 * That is absurd for any real occlusion image, and a fractional coordinate is never `> 1`,
 * so the rule is safe in practice. (Theoretical corner: a 1×1-pixel base image, which no
 * occlusion deck contains.)
 */
private fun isFractional(note: ParsedOcclusion): Boolean {
    val coords = allCoords(note)
    return coords.isNotEmpty() && coords.all { it <= 1.0 }
}

/**
 * Multiplies a fractional-ratio [shape] up to natural pixels: x-axis metrics
 * (`left/width/rx`, polygon `x`) by [nW]; y-axis metrics (`top/height/ry`, polygon `y`)
 * by [nH]. Only called for notes classified fractional with known natural dims.
 */
private fun scaleToPixels(shape: OcclusionShapeState, nW: Int, nH: Int): OcclusionShapeState =
    when (shape) {
        is OcclusionShapeState.Rect -> shape.copy(
            left = shape.left * nW, top = shape.top * nH,
            width = shape.width * nW, height = shape.height * nH,
        )
        is OcclusionShapeState.Ellipse -> shape.copy(
            left = shape.left * nW, top = shape.top * nH,
            width = shape.width * nW, height = shape.height * nH,
            rx = shape.rx * nW, ry = shape.ry * nH,
        )
        is OcclusionShapeState.Polygon -> shape.copy(
            points = shape.points.map { Pt(it.x * nW, it.y * nH) },
        )
    }

/**
 * Resolves every shape in [note] for one side into typed, state-carrying shapes, in
 * NATURAL PIXELS.
 *
 * Unparseable shapes (text/unknown/malformed) are dropped. The resulting order follows
 * the note's occlusion order (which is NOT guaranteed sorted by ordinal from rslib), but
 * ordering is irrelevant to correctness since each shape carries its own state.
 *
 * If the note's coordinates are FRACTIONAL ratios (see [isFractional] — the Image
 * Occlusion Enhanced format) and the natural image dimensions ([naturalW]/[naturalH]) are
 * known (`> 0`), each coordinate is multiplied up to pixels here so the UI layer can stay
 * pixel-only. Pixel-format notes (the modern built-in notetype) pass through unchanged.
 * When natural dims are unknown, fractional coords are left as-is (the UI already declines
 * to draw masks without known dims).
 */
fun resolveShapes(
    note: ParsedOcclusion,
    testedOrdinal: Int,
    isBack: Boolean,
    naturalW: Int = 0,
    naturalH: Int = 0,
): List<OcclusionShapeState> {
    val scale = naturalW > 0 && naturalH > 0 && isFractional(note)
    return note.occlusions.flatMap { occ ->
        val state = resolveState(testedOrdinal, occ.ordinal, isBack, note.occludeInactive)
        occ.shapes.mapNotNull { raw ->
            parseShape(raw.kind, raw.props, state)?.let {
                if (scale) scaleToPixels(it, naturalW, naturalH) else it
            }
        }
    }
}

/** Natural pixel dimensions of a raster image. */
data class ImageDims(val width: Int, val height: Int)

/**
 * Sniffs natural width/height straight from encoded image header bytes — pure, so it
 * works in JVM unit tests where Android's `BitmapFactory` is unavailable. Supports the
 * formats Anki media realistically carries: PNG, JPEG, GIF, and (RIFF) WebP. Returns
 * `null` when the format is unrecognised or the header is truncated.
 */
fun imageDims(bytes: ByteArray): ImageDims? =
    pngDims(bytes) ?: gifDims(bytes) ?: webpDims(bytes) ?: jpegDims(bytes)

private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

private fun be32(b: ByteArray, i: Int): Int =
    (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)

private fun pngDims(b: ByteArray): ImageDims? {
    // 8-byte signature, then IHDR at offset 16 (width) / 20 (height), big-endian.
    if (b.size < 24) return null
    val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    for (i in sig.indices) if (b[i] != sig[i]) return null
    return ImageDims(be32(b, 16), be32(b, 20))
}

private fun gifDims(b: ByteArray): ImageDims? {
    // "GIF87a"/"GIF89a", then little-endian width/height at offsets 6/8.
    if (b.size < 10) return null
    if (b[0] != 'G'.code.toByte() || b[1] != 'I'.code.toByte() || b[2] != 'F'.code.toByte()) return null
    val w = u8(b, 6) or (u8(b, 7) shl 8)
    val h = u8(b, 8) or (u8(b, 9) shl 8)
    return ImageDims(w, h)
}

private fun webpDims(b: ByteArray): ImageDims? {
    // RIFF....WEBP; three sub-formats: VP8 (lossy), VP8L (lossless), VP8X (extended).
    if (b.size < 30) return null
    if (b[0] != 'R'.code.toByte() || b[1] != 'I'.code.toByte() || b[2] != 'F'.code.toByte() || b[3] != 'F'.code.toByte()) return null
    if (b[8] != 'W'.code.toByte() || b[9] != 'E'.code.toByte() || b[10] != 'B'.code.toByte() || b[11] != 'P'.code.toByte()) return null
    return when {
        b[12] == 'V'.code.toByte() && b[15] == ' '.code.toByte() -> {
            // "VP8 ": 16-bit width/height (14-bit meaningful) at offset 26/28, little-endian.
            val w = (u8(b, 26) or (u8(b, 27) shl 8)) and 0x3FFF
            val h = (u8(b, 28) or (u8(b, 29) shl 8)) and 0x3FFF
            ImageDims(w, h)
        }
        b[15] == 'L'.code.toByte() -> {
            // "VP8L": 14-bit width-1 / height-1 packed from offset 21.
            val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
            val w = (bits and 0x3FFF) + 1
            val h = ((bits shr 14) and 0x3FFF) + 1
            ImageDims(w, h)
        }
        b[15] == 'X'.code.toByte() -> {
            // "VP8X": 24-bit canvas width-1 / height-1 at offsets 24 / 27, little-endian.
            val w = (u8(b, 24) or (u8(b, 25) shl 8) or (u8(b, 26) shl 16)) + 1
            val h = (u8(b, 27) or (u8(b, 28) shl 8) or (u8(b, 29) shl 16)) + 1
            ImageDims(w, h)
        }
        else -> null
    }
}

private fun jpegDims(b: ByteArray): ImageDims? {
    // SOI (FFD8), then walk markers to the first SOFn frame header.
    if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
    var i = 2
    while (i + 9 < b.size) {
        if (u8(b, i) != 0xFF) { i++; continue }
        var marker = u8(b, i + 1)
        // Skip fill bytes (0xFF padding).
        while (marker == 0xFF && i + 1 < b.size) { i++; marker = u8(b, i + 1) }
        // SOF markers carry frame dimensions (exclude DHT/DAC/SOS/RST/APP/COM).
        val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        val len = (u8(b, i + 2) shl 8) or u8(b, i + 3)
        if (isSof) {
            if (i + 9 >= b.size) return null
            val h = (u8(b, i + 5) shl 8) or u8(b, i + 6)
            val w = (u8(b, i + 7) shl 8) or u8(b, i + 8)
            return ImageDims(w, h)
        }
        if (len < 2) return null
        i += 2 + len
    }
    return null
}
