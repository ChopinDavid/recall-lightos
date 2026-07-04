package com.dvdutch.recall.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dvdutch.recall.api.OcclusionNode
import com.dvdutch.recall.api.OcclusionShapeState
import com.dvdutch.recall.api.Pt
import com.dvdutch.recall.api.ShapeState
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Log tag for occlusion rendering diagnostics (degenerate natural dimensions).
 */
private const val OCCLUSION_TAG = "OcclusionImage"

/**
 * Solid mask fill for [ShapeState.MASKED] shapes. On the black theme a mid/light grey
 * block reads clearly as "something is hidden here" while staying monochrome. Fully
 * opaque so the answer region underneath is genuinely covered.
 */
private val MASK_FILL = Color(0xFFBBBBBB)

/**
 * Outline colour for [ShapeState.REVEALED_OUTLINE] shapes (the tested answer on the
 * back). White reads as a highlight ring around the now-visible region on the black
 * theme.
 */
private val OUTLINE_COLOR = Color.White

/** Outline stroke width, in natural-image pixels (scaled with the image). */
private const val OUTLINE_STROKE_PX = 2f

// --- Base-image load state (why this exists) ----------------------------------

/**
 * The three states an occlusion base image can be in.
 *
 * This exists to kill a real UX bug: the generic [ImageNodePlaceholder] is a *solid
 * grey filled box*, and a masked occlusion shape ([MASK_FILL]) is *also* a solid grey
 * box. So a mid-load occlusion card rendered through the shared placeholder was visually
 * indistinguishable from an occlusion whose mask never lifts — it read as broken. The
 * loading and failed states must therefore be *text in a bordered (unfilled) box*, never
 * a solid fill, so they can never be mistaken for a mask.
 */
enum class OcclusionImageState {
    /** Decode in flight — bitmap not yet resolved. */
    Loading,

    /** Load completed but yielded no bitmap (missing / undecodable media). */
    Failed,

    /** Bitmap decoded and ready to draw. */
    Loaded,
}

/**
 * Resolves the [OcclusionImageState] from the two observable signals of the
 * [produceState] load: whether the producer has finished ([loadCompleted]) and the
 * resolved [bitmap] (null until/unless a bitmap decodes).
 *
 * `produceState` seeds `null` (Loading), then the producer resolves to a bitmap
 * (Loaded) or to `null` (Failed). The only way to tell a still-loading null from a
 * failed null is the completion flag, so we thread it through. Pure and unit-tested.
 */
fun occlusionImageState(bitmap: ImageBitmap?, loadCompleted: Boolean): OcclusionImageState = when {
    bitmap != null -> OcclusionImageState.Loaded
    loadCompleted -> OcclusionImageState.Failed
    else -> OcclusionImageState.Loading
}

/** Centred text shown while the occlusion base image is still decoding. */
private const val LOADING_LABEL = "Loading image…"

/** Centred text shown when the occlusion base image is missing / failed to decode. */
private const val FAILED_LABEL = "⚠ image unavailable"

// --- Pure scaling / coordinate math (Android-runtime-free, unit-tested) -------

/**
 * The uniform scale + letterbox offset that maps natural-image pixel coordinates onto
 * the on-screen image under [ContentScale.Fit].
 *
 * @property scale natural→displayed multiplier (identical for x and y under Fit).
 * @property offsetX left inset of the image inside the available box (letterboxing).
 * @property offsetY top inset of the image inside the available box.
 */
data class FitTransform(val scale: Float, val offsetX: Float, val offsetY: Float)

/**
 * Computes the [FitTransform] for fitting a `naturalW × naturalH` image into a
 * `displayedW × displayedH` box under [ContentScale.Fit]: the image is scaled by the
 * smaller of the two axis ratios (so it fully fits, aspect preserved) and centred, so
 * the unused axis is letterboxed.
 *
 * Degenerate inputs (any dimension `<= 0`) yield a zero scale and zero offset — the
 * caller treats a zero scale as "draw the image, skip the masks" rather than dividing
 * by zero. T1's `imageDims` returns `(0, 0)` for image formats it can't measure, so
 * this guard is load-bearing.
 */
fun fitTransform(
    naturalW: Int,
    naturalH: Int,
    displayedW: Float,
    displayedH: Float,
): FitTransform {
    if (naturalW <= 0 || naturalH <= 0 || displayedW <= 0f || displayedH <= 0f) {
        return FitTransform(scale = 0f, offsetX = 0f, offsetY = 0f)
    }
    val scale = minOf(displayedW / naturalW, displayedH / naturalH)
    val drawnW = naturalW * scale
    val drawnH = naturalH * scale
    val offsetX = (displayedW - drawnW) / 2f
    val offsetY = (displayedH - drawnH) / 2f
    return FitTransform(scale = scale, offsetX = offsetX, offsetY = offsetY)
}

/**
 * A shape's geometry after [fitTransform] has been applied: coordinates are in the
 * on-screen pixel frame (already scaled and letterbox-offset), ready to hand straight
 * to a [DrawScope]. Carries the resolved [state] through unchanged.
 */
sealed interface ScaledShape {
    val state: ShapeState

    data class Rect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        override val state: ShapeState,
    ) : ScaledShape

    /** An oval defined by its bounding box (Fit-scaled). rx/ry are redundant with the box. */
    data class Ellipse(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        override val state: ShapeState,
    ) : ScaledShape

    /** Polygon vertices as `(x, y)` pairs in on-screen pixels. */
    data class Polygon(
        val points: List<Pair<Float, Float>>,
        override val state: ShapeState,
    ) : ScaledShape
}

/**
 * Maps one natural-pixel [OcclusionShapeState] into on-screen pixels under [transform].
 *
 * Each coordinate is multiplied by [FitTransform.scale] and then offset by the
 * letterbox insets. The ellipse's bounding box (`left, top, width, height`) is the
 * authoritative oval definition; `rx`/`ry` from the wire are radii that merely restate
 * `width/2`, `height/2`, so they're dropped here.
 */
fun scaleShape(shape: OcclusionShapeState, transform: FitTransform): ScaledShape {
    val s = transform.scale
    val ox = transform.offsetX
    val oy = transform.offsetY
    return when (shape) {
        is OcclusionShapeState.Rect -> ScaledShape.Rect(
            left = (shape.left * s).toFloat() + ox,
            top = (shape.top * s).toFloat() + oy,
            width = (shape.width * s).toFloat(),
            height = (shape.height * s).toFloat(),
            state = shape.state,
        )

        is OcclusionShapeState.Ellipse -> ScaledShape.Ellipse(
            left = (shape.left * s).toFloat() + ox,
            top = (shape.top * s).toFloat() + oy,
            width = (shape.width * s).toFloat(),
            height = (shape.height * s).toFloat(),
            state = shape.state,
        )

        is OcclusionShapeState.Polygon -> ScaledShape.Polygon(
            points = shape.points.map { p: Pt ->
                Pair((p.x * s).toFloat() + ox, (p.y * s).toFloat() + oy)
            },
            state = shape.state,
        )
    }
}

// --- Composable ---------------------------------------------------------------

/**
 * Renders a natively-drawn Image Occlusion card side: the base grayscale image with
 * mask/outline overlays drawn per the engine-resolved [ShapeState] of each shape.
 *
 * The base bitmap is loaded through the shared [MediaLoader] (same off-main,
 * LRU-cached, filename→bitmap path as [MediaImage]); a missing/undecodable file falls
 * back to the same labelled placeholder rather than crashing. The image draws under
 * [ContentScale.Fit] filling the available width at the node's natural aspect ratio,
 * and a Compose [Canvas] overlays the shapes at Fit-scaled coordinates.
 *
 * When the natural dimensions are degenerate (T1's `imageDims` returns `(0, 0)` for
 * unmeasurable formats) the image is shown with no masks and a warning is logged —
 * drawing masks at an unknown scale would misplace them.
 *
 * @param mediaLoader supplies the base bitmap; when null, the placeholder is shown.
 */
@Composable
fun OcclusionImage(node: OcclusionNode, mediaLoader: MediaLoader?) {
    if (mediaLoader == null) {
        // No loader supplied (config-time): treat as unavailable, but with the
        // bordered text state — never a solid grey fill that reads as a stuck mask.
        OcclusionImagePlaceholder(OcclusionImageState.Failed, node.image)
        return
    }

    // Two signals distinguish Loading from Failed: `completed` flips true only after the
    // producer returns, so a still-loading null (Loading) is never confused with a
    // resolved null (Failed). See [occlusionImageState].
    //
    // Seed the initial value from the session cache: on a REVEAL the FRONT and BACK sides
    // are distinct composable instances over the SAME image, so swapping them restarts
    // produceState from its initial value. Seeding a cache hit (already-decoded bitmap,
    // marked completed) makes the back side draw the image on frame 1 — no ~2-frame drop
    // to the Loading placeholder (and the transient scrollbar it caused). A cache miss
    // (genuinely-not-yet-loaded image) seeds null/false → unchanged Loading behaviour.
    val loaded by produceState<Pair<ImageBitmap?, Boolean>>(
        initialValue = mediaLoader.peek(node.image)?.let { it to true } ?: (null to false),
        node.image,
        mediaLoader,
    ) {
        value = mediaLoader.load(node.image) to true
    }

    val (bitmap, completed) = loaded
    val image = bitmap
    if (image == null) {
        OcclusionImagePlaceholder(occlusionImageState(bitmap, completed), node.image)
        return
    }

    val naturalW = node.naturalW
    val naturalH = node.naturalH
    val hasDims = naturalW > 0 && naturalH > 0
    if (!hasDims) {
        Log.w(
            OCCLUSION_TAG,
            "occlusion '${node.image}' has degenerate natural size " +
                "(${naturalW}x$naturalH); drawing image without masks",
        )
    }

    // Fill the available width at the image's natural aspect ratio; the Canvas fills
    // the same box so its size is exactly the displayed image size (no extra letterbox
    // under Fit, but fitTransform stays robust if that ever changes).
    val aspect = if (hasDims) naturalW.toFloat() / naturalH.toFloat() else 1f
    val modifier = Modifier.fillMaxWidth().let {
        if (hasDims) it.aspectRatio(aspect) else it
    }

    Canvas(modifier = modifier) {
        drawBaseImage(image)
        if (!hasDims) return@Canvas
        val transform = fitTransform(naturalW, naturalH, size.width, size.height)
        if (transform.scale <= 0f) return@Canvas
        for (shape in node.shapes) {
            drawScaledShape(scaleShape(shape, transform))
        }
    }
}

/**
 * Draws the base [image] scaled to fill this [DrawScope] under Fit semantics: uniform
 * scale, centred, letterboxed on the unused axis. Kept separate so the overlay loop
 * shares the exact same transform math as [fitTransform].
 */
private fun DrawScope.drawBaseImage(image: ImageBitmap) {
    val t = fitTransform(image.width, image.height, size.width, size.height)
    if (t.scale <= 0f) return
    translate(left = t.offsetX, top = t.offsetY) {
        drawImage(
            image = image,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(
                (image.width * t.scale).toInt(),
                (image.height * t.scale).toInt(),
            ),
        )
    }
}

/**
 * Draws one already-scaled shape per its [ScaledShape.state]:
 * MASKED → opaque fill; REVEALED_OUTLINE → stroke only; CONTEXT → nothing.
 */
private fun DrawScope.drawScaledShape(shape: ScaledShape) {
    when (shape.state) {
        ShapeState.CONTEXT -> return // shape shows through — draw nothing
        ShapeState.MASKED -> drawShapeGeometry(shape, filled = true)
        ShapeState.REVEALED_OUTLINE -> drawShapeGeometry(shape, filled = false)
    }
}

/** Emits the geometry of [shape] as a fill (mask) or stroke-only outline. */
private fun DrawScope.drawShapeGeometry(shape: ScaledShape, filled: Boolean) {
    val color = if (filled) MASK_FILL else OUTLINE_COLOR
    val stroke = Stroke(width = OUTLINE_STROKE_PX)
    when (shape) {
        is ScaledShape.Rect ->
            if (filled) {
                drawRect(
                    color = color,
                    topLeft = Offset(shape.left, shape.top),
                    size = Size(shape.width, shape.height),
                )
            } else {
                drawRect(
                    color = color,
                    topLeft = Offset(shape.left, shape.top),
                    size = Size(shape.width, shape.height),
                    style = stroke,
                )
            }

        is ScaledShape.Ellipse ->
            if (filled) {
                drawOval(
                    color = color,
                    topLeft = Offset(shape.left, shape.top),
                    size = Size(shape.width, shape.height),
                )
            } else {
                drawOval(
                    color = color,
                    topLeft = Offset(shape.left, shape.top),
                    size = Size(shape.width, shape.height),
                    style = stroke,
                )
            }

        is ScaledShape.Polygon -> {
            val path = polygonPath(shape.points)
            if (filled) drawPath(path, color) else drawPath(path, color, style = stroke)
        }
    }
}

/** Builds a closed [Path] from polygon vertices; empty vertices yield an empty path. */
private fun polygonPath(points: List<Pair<Float, Float>>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    val (x0, y0) = points.first()
    moveTo(x0, y0)
    for (i in 1 until points.size) {
        val (x, y) = points[i]
        lineTo(x, y)
    }
    close()
}

/**
 * The non-drawn states of an occlusion base image: a *bordered, text-labelled, unfilled*
 * box — deliberately NOT the solid grey [ImageNodePlaceholder]. A solid grey fill is
 * exactly what a [MASK_FILL] mask looks like, so reusing it made a mid-load (or missing)
 * occlusion card indistinguishable from an occlusion whose mask never lifts. Text plus an
 * outline (no fill) can never be mistaken for a mask.
 *
 * [state] must be [OcclusionImageState.Loading] or [OcclusionImageState.Failed]; the
 * [OcclusionImageState.Loaded] state is drawn by [OcclusionImage] itself and never
 * reaches here. [imageName] is the media filename, appended so content is never silently
 * dropped (same contract as [MediaImage]'s placeholder).
 */
@Composable
private fun OcclusionImagePlaceholder(state: OcclusionImageState, imageName: String) {
    val label = when (state) {
        OcclusionImageState.Loading -> LOADING_LABEL
        OcclusionImageState.Failed -> "$FAILED_LABEL — $imageName"
        // Loaded is drawn by OcclusionImage; never routed here. Fall back defensively.
        OcclusionImageState.Loaded -> "$FAILED_LABEL — $imageName"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp())
            .border(1.dp, LightThemeTokens.colors.contentSecondary)
            .padding(2f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            align = TextAlign.Center,
            lighten = true,
        )
    }
}
