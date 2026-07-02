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
