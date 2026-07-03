package com.dvdutch.recall.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.dvdutch.recall.api.OcclusionNode
import com.dvdutch.recall.api.OcclusionShapeState
import com.dvdutch.recall.api.Pt
import com.dvdutch.recall.api.ShapeState

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
        ImageNodePlaceholder(occlusionPlaceholderNode(node))
        return
    }

    val bitmap by produceState<ImageBitmap?>(initialValue = null, node.image, mediaLoader) {
        value = mediaLoader.load(node.image)
    }

    val image = bitmap
    if (image == null) {
        ImageNodePlaceholder(occlusionPlaceholderNode(node))
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
 * A synthetic [com.dvdutch.recall.api.ImageNode] used only to reuse [ImageNodePlaceholder]'s
 * labelled fallback box when the occlusion base image can't be shown — same "content is
 * never silently dropped" contract as [MediaImage].
 */
private fun occlusionPlaceholderNode(node: OcclusionNode) =
    com.dvdutch.recall.api.ImageNode(src = node.image, w = null, h = null)
