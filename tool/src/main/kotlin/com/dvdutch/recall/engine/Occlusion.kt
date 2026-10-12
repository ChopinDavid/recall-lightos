package com.dvdutch.recall.engine

import com.dvdutch.recall.api.OcclusionNode
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
 * Anki's built-in "Image Occlusion" notetype stores every coordinate as a fraction of the
 * image as shown (e.g. `left=.1363 width=.1882`) — in every stable release since 23.10
 * (ankitects/anki#2513) — while notes from its 2023 pre-releases carry absolute pixels
 * (e.g. `left=150 width=200`).
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
internal fun isFractional(note: ParsedOcclusion): Boolean {
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
 * If the note's coordinates are FRACTIONAL ratios (see [isFractional] — what Anki writes)
 * and the natural image dimensions ([naturalW]/[naturalH], as shown: see [imageDims]) are
 * known (`> 0`), each coordinate is multiplied up to pixels here so the UI layer can stay
 * pixel-only. Pixel-format notes (Anki's 2023 pre-releases) pass through unchanged.
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

/**
 * This node with its image's natural size, for a card built before the image was on the
 * phone (its size unknown then, see [OcclusionNode.fractional]): fractional shapes are
 * scaled up to natural pixels like [resolveShapes] does when the size is known. A node that
 * already has a size, or a non-positive [width]/[height], is returned unchanged.
 */
fun OcclusionNode.withNaturalSize(width: Int, height: Int): OcclusionNode {
    if ((naturalW > 0 && naturalH > 0) || width <= 0 || height <= 0) return this
    return copy(
        naturalW = width,
        naturalH = height,
        shapes = if (fractional) shapes.map { scaleToPixels(it, width, height) } else shapes,
        fractional = false,
    )
}

/**
 * The media filename an Image Occlusion note's image field names (`<img src="cell.png">`),
 * or null. rslib's `getImageOcclusionNote` reports the filename only when the file is
 * already in the media folder; while media is still downloading the field is the only
 * place left to read it from.
 */
fun occlusionImageSrc(field: String): String? =
    IMG_SRC.find(field)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

private val IMG_SRC = Regex("""<img\b[^>]*?\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

/** Natural pixel dimensions of a raster image. */
data class ImageDims(val width: Int, val height: Int)

/**
 * Sniffs natural width/height straight from encoded image header bytes — pure, so it
 * works in JVM unit tests where Android's `BitmapFactory` is unavailable. Supports the
 * formats Anki media realistically carries: PNG, JPEG, GIF, and (RIFF) WebP. Returns
 * `null` when the format is unrecognised or the header is truncated.
 *
 * The size is the image's size as SHOWN: a photo whose EXIF Orientation turns it a quarter
 * (a phone held upright) has its width and height swapped, as every browser — and so
 * Anki's occlusion editor, which measures masks on the image it shows — displays it (see
 * [imageOrientation]).
 */
fun imageDims(bytes: ByteArray): ImageDims? {
    val stored = pngDims(bytes) ?: gifDims(bytes) ?: webpDims(bytes) ?: jpegDims(bytes) ?: return null
    return if (exifTurn(imageOrientation(bytes)).swapsAxes) ImageDims(stored.height, stored.width) else stored
}

/**
 * How to show a stored image upright: rotate it [degrees] clockwise, then, if [mirror],
 * flip it left to right.
 */
data class ExifTurn(val degrees: Int, val mirror: Boolean) {
    val isIdentity: Boolean get() = degrees == 0 && !mirror

    /** A quarter turn: the shown image's width is the stored height, and vice versa. */
    val swapsAxes: Boolean get() = degrees == 90 || degrees == 270
}

/**
 * The turn an EXIF Orientation value asks for (the same as Android's `ExifInterface`): 1 is
 * upright, 3/6/8 rotate, 2/4/5/7 also mirror. Anything else counts as upright.
 */
fun exifTurn(orientation: Int): ExifTurn = when (orientation) {
    2 -> ExifTurn(0, mirror = true)
    3 -> ExifTurn(180, mirror = false)
    4 -> ExifTurn(180, mirror = true)
    5 -> ExifTurn(90, mirror = true)
    6 -> ExifTurn(90, mirror = false)
    7 -> ExifTurn(270, mirror = true)
    8 -> ExifTurn(270, mirror = false)
    else -> ExifTurn(0, mirror = false)
}

/**
 * The EXIF Orientation (1–8) an image carries, read the way Chromium (so Anki, which shows
 * cards in it) reads it: a JPEG's first "Exif" APP1 segment, or a PNG's eXIf chunk when it
 * comes before the image data. Anything else — no tag, a tag that can't be read, WebP
 * (whose EXIF Chromium ignores), other formats — is upright (1).
 */
fun imageOrientation(b: ByteArray): Int = when {
    isJpeg(b) -> jpegOrientation(b)
    isPng(b) -> pngOrientation(b)
    else -> 1
}

private fun isJpeg(b: ByteArray): Boolean = b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8

private fun isPng(b: ByteArray): Boolean = b.size >= 8 && (0 until 8).all { u8(b, it) == PNG_SIGNATURE[it] }

private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * A JPEG's EXIF Orientation — how a camera records that the photo was taken sideways or
 * upside down: from the first APP1 segment that is "Exif", as Chromium takes it, else 1.
 */
fun jpegOrientation(b: ByteArray): Int {
    if (!isJpeg(b)) return 1
    var i = 2
    while (i + 3 < b.size) {
        if (u8(b, i) != 0xFF) { i++; continue }
        var marker = u8(b, i + 1)
        while (marker == 0xFF && i + 2 < b.size) { i++; marker = u8(b, i + 1) }
        if (i + 3 >= b.size) return 1
        // The headers end where the image data starts.
        if (marker == 0xDA || marker == 0xD9) return 1
        val len = (u8(b, i + 2) shl 8) or u8(b, i + 3)
        if (len < 2) return 1
        val start = i + 4
        val end = minOf(b.size, i + 2 + len)
        // "Exif\0" and a pad byte, then the TIFF structure.
        if (marker == 0xE1 && end - start > 6 && (0 until 5).all { b[start + it] == EXIF_HEADER[it] }) {
            return tiffOrientation(b, start + 6, end) ?: 1
        }
        i += 2 + len
    }
    return 1
}

private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0)

/**
 * A PNG's EXIF Orientation from its eXIf chunk (bare TIFF data), honoured only before the
 * first IDAT — metadata after the image data is ignored, as in Chromium — else 1.
 */
private fun pngOrientation(b: ByteArray): Int {
    var i = 8
    while (i + 8 <= b.size) {
        val len = be32(b, i).toLong() and 0xFFFFFFFFL
        val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
        if (type == "IDAT" || type == "IEND") return 1
        val data = i + 8
        if (data + len > b.size) return 1
        if (type == "eXIf") return tiffOrientation(b, data, data + len.toInt()) ?: 1
        i = data + len.toInt() + 4 // past the data and its CRC
    }
    return 1
}

/**
 * The Orientation tag of the TIFF structure at [tiff] (ending at [end]): byte order ("II"
 * little / "MM" big endian), 42, then the first directory (IFD0); like Chromium, when IFD0
 * has no Orientation the Exif sub-directory it points to is read too. Null when absent,
 * malformed, or not a single SHORT in 1..8.
 */
private fun tiffOrientation(b: ByteArray, tiff: Int, end: Int): Int? {
    if (end - tiff < 8) return null
    val little = when {
        b[tiff] == 'I'.code.toByte() && b[tiff + 1] == 'I'.code.toByte() -> true
        b[tiff] == 'M'.code.toByte() && b[tiff + 1] == 'M'.code.toByte() -> false
        else -> return null
    }
    fun u16(at: Int): Int = if (little) u8(b, at) or (u8(b, at + 1) shl 8) else (u8(b, at) shl 8) or u8(b, at + 1)
    fun u32(at: Int): Long = if (little) {
        u16(at).toLong() or (u16(at + 2).toLong() shl 16)
    } else {
        (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    }
    if (u16(tiff + 2) != 42) return null

    /** The Orientation in the directory at [offset]; from IFD0, also its Exif sub-directory. */
    fun directory(offset: Long, root: Boolean): Int? {
        val at = tiff + offset // Long: no overflow before the bounds check
        if (at + 2 > end) return null
        val ifd = at.toInt()
        var exifIfd: Long? = null
        for (k in 0 until u16(ifd)) {
            val entry = ifd + 2 + 12 * k
            if (entry + 12 > end) break
            when (u16(entry)) {
                // Orientation: one SHORT (3), its value in the first two bytes of the value field.
                0x0112 -> if (u16(entry + 2) == 3 && u32(entry + 4) == 1L) {
                    u16(entry + 8).takeIf { it in 1..8 }?.let { return it }
                }
                // The Exif sub-directory's offset (a LONG).
                0x8769 -> if (root && u16(entry + 2) == 4) exifIfd = u32(entry + 8)
            }
        }
        return exifIfd?.let { directory(it, root = false) }
    }
    return directory(u32(tiff + 4), root = true)
}

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
        while (marker == 0xFF && i + 2 < b.size) { i++; marker = u8(b, i + 1) }
        // A truncated file can end inside the padding or the marker's length field.
        if (i + 3 >= b.size) return null
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
