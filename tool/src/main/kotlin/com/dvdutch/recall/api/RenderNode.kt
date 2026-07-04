package com.dvdutch.recall.api

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single renderable node in a card's front/back payload.
 *
 * The wire format tags each node with a `"t"` discriminator. Unknown tags MUST
 * deserialize to [UnsupportedNode] rather than throwing, so a newer bridge that
 * introduces a node type can never crash an older client.
 */
@Serializable(with = RenderNodeSerializer::class)
sealed interface RenderNode

@Serializable
data class TextNode(val runs: List<TextRun>) : RenderNode

@Serializable
data class TextRun(
    val s: String,
    val b: Boolean = false,
    val i: Boolean = false,
    val small: Boolean = false,
    val mono: Boolean = false,
    val strike: Boolean = false,
    // Underline. Additive, kotlin-side only: the parity HTML→node compiler never
    // emits it (no HTML tag maps here) — it exists purely so the type-answer diff
    // parser can flag a "missed" run monochrome-safely. Default false keeps the wire
    // contract and the parity corpus unaffected.
    val underline: Boolean = false,
)

@Serializable
data class ClozeNode(
    val state: String,
    val hint: String? = null,
    val text: String? = null,
) : RenderNode

@Serializable
data class ImageNode(val src: String, val w: Int? = null, val h: Int? = null) : RenderNode

@Serializable
@SerialName("rule")
data object RuleNode : RenderNode

@Serializable
data class UnsupportedNode(val kind: String) : RenderNode

/**
 * A natively-rendered Image Occlusion card side. Emitted directly by the engine
 * from rslib's structured `getImageOcclusionNote` (NOT via the HTML→node compiler),
 * so the canvas/JS template is bypassed entirely.
 *
 * The engine has already resolved every shape's [OcclusionShapeState.state] for this
 * side (front vs back × tested-vs-inactive × hide-one/hide-all), so the composable is
 * dumb: it draws each shape at its natural-pixel coordinates, scaled by
 * displayedSize / (naturalW, naturalH), in the fill/outline/context style its state
 * dictates.
 *
 * @property image the base image filename (bare name, served like any [ImageNode] src).
 * @property naturalW natural image width in pixels — the reference frame for coords.
 * @property naturalH natural image height in pixels.
 * @property shapes the resolved shapes for this side.
 * @property side `"front"` or `"back"`.
 */
@Serializable
@SerialName("occlusion")
data class OcclusionNode(
    val image: String,
    val naturalW: Int,
    val naturalH: Int,
    val shapes: List<OcclusionShapeState>,
    val side: String,
) : RenderNode

/** How a single occlusion shape must be drawn on the resolved side. */
enum class ShapeState {
    /**
     * Solid opaque mask for an INACTIVE (not-being-asked) shape — the hide-all case
     * where a non-tested region is also hidden as context.
     */
    MASKED,

    /**
     * Solid opaque mask for the TESTED shape on the FRONT — the region being asked. Drawn
     * with a distinguishing high-contrast border so that, among many hide-all masks, the
     * studier can tell exactly WHICH hidden region they must recall. Still fully opaque:
     * the answer stays covered. (AnkiDroid renders this pink vs. tan; we do it in
     * monochrome — see [maskStyle].)
     */
    MASKED_TESTED,

    /** Outline only — the tested shape revealed on the answer side. */
    REVEALED_OUTLINE,

    /** Shown as plain context (no mask, no outline) — hide-one inactive shapes. */
    CONTEXT,
}

/** A simple natural-pixel point for polygon geometry. */
@Serializable
data class Pt(val x: Double, val y: Double)

/**
 * A typed occlusion shape carrying its geometry (natural pixels) and its already
 * resolved [state]. `text` shapes are intentionally NOT modelled in v1 (skipped by
 * the parser); the med-student majority is rect/ellipse/polygon.
 */
@Serializable
sealed interface OcclusionShapeState {
    val state: ShapeState

    @Serializable
    @SerialName("rect")
    data class Rect(
        val left: Double,
        val top: Double,
        val width: Double,
        val height: Double,
        override val state: ShapeState,
    ) : OcclusionShapeState

    @Serializable
    @SerialName("ellipse")
    data class Ellipse(
        val left: Double,
        val top: Double,
        val width: Double,
        val height: Double,
        val rx: Double,
        val ry: Double,
        override val state: ShapeState,
    ) : OcclusionShapeState

    @Serializable
    @SerialName("polygon")
    data class Polygon(
        val points: List<Pt>,
        override val state: ShapeState,
    ) : OcclusionShapeState
}

/**
 * Selects a [RenderNode] subtype from the `"t"` discriminator. Any tag we don't
 * recognise is mapped to [UnsupportedNode] via [UnsupportedNodeSerializer].
 */
object RenderNodeSerializer : JsonContentPolymorphicSerializer<RenderNode>(RenderNode::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<RenderNode> {
        val tag = element.jsonObject["t"]?.jsonPrimitive?.content
        return when (tag) {
            "text" -> TextNode.serializer()
            "cloze" -> ClozeNode.serializer()
            "image" -> ImageNode.serializer()
            "rule" -> RuleNode.serializer()
            "occlusion" -> OcclusionNode.serializer()
            else -> UnsupportedNodeSerializer(tag ?: "")
        }
    }
}

/**
 * Produces an [UnsupportedNode] carrying the unrecognised `"t"` tag. Encoding is
 * unsupported: these nodes only ever originate from inbound bridge payloads.
 */
private class UnsupportedNodeSerializer(private val kind: String) : KSerializer<RenderNode> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.dvdutch.recall.api.UnsupportedNode")

    override fun deserialize(decoder: Decoder): RenderNode = UnsupportedNode(kind)

    override fun serialize(encoder: Encoder, value: RenderNode) {
        throw SerializationException("UnsupportedNode is not serializable")
    }
}
