package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.ClozeNode
import com.dvdutch.recall.api.ImageNode
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.RuleNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.UnsupportedNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Serializes compiled [RenderNode]s to the SAME on-the-wire JSON shape the
 * Python reference emits, so parity can be asserted structurally. This mirrors
 * the Python dict layout exactly: text runs carry only the style keys that are
 * `true` (bfalse/absent are indistinguishable), image nodes omit absent w/h,
 * cloze nodes omit absent hint/text.
 */
fun nodesToJson(nodes: List<RenderNode>): JsonArray = buildJsonArray {
    for (node in nodes) {
        add(
            when (node) {
                is TextNode -> buildJsonObject {
                    put("t", JsonPrimitive("text"))
                    put("runs", buildJsonArray {
                        for (run in node.runs) {
                            add(buildJsonObject {
                                put("s", JsonPrimitive(run.s))
                                if (run.b) put("b", JsonPrimitive(true))
                                if (run.i) put("i", JsonPrimitive(true))
                                if (run.small) put("small", JsonPrimitive(true))
                                if (run.mono) put("mono", JsonPrimitive(true))
                                if (run.strike) put("strike", JsonPrimitive(true))
                            })
                        }
                    })
                }
                is ClozeNode -> buildJsonObject {
                    put("t", JsonPrimitive("cloze"))
                    put("state", JsonPrimitive(node.state))
                    node.hint?.let { put("hint", JsonPrimitive(it)) }
                    node.text?.let { put("text", JsonPrimitive(it)) }
                }
                is ImageNode -> buildJsonObject {
                    put("t", JsonPrimitive("image"))
                    put("src", JsonPrimitive(node.src))
                    node.w?.let { put("w", JsonPrimitive(it)) }
                    node.h?.let { put("h", JsonPrimitive(it)) }
                }
                RuleNode -> buildJsonObject { put("t", JsonPrimitive("rule")) }
                is UnsupportedNode -> buildJsonObject {
                    put("t", JsonPrimitive("unsupported"))
                    put("kind", JsonPrimitive(node.kind))
                }
            }
        )
    }
}

/**
 * Order-sensitive structural JSON equality: arrays compare element-wise in
 * order; objects compare by key set and per-key value (key order irrelevant);
 * numbers compare by canonical numeric value so `800` == `800.0`.
 */
fun jsonEquals(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonArray && b is JsonArray ->
        a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
    a is JsonObject && b is JsonObject ->
        a.keys == b.keys && a.keys.all { jsonEquals(a.getValue(it), b.getValue(it)) }
    a is JsonPrimitive && b is JsonPrimitive -> primEquals(a, b)
    else -> false
}

private fun primEquals(a: JsonPrimitive, b: JsonPrimitive): Boolean {
    if (a.isString != b.isString) return false
    if (a.isString) return a.content == b.content
    // Non-string primitives: compare numerically when both look numeric,
    // else fall back to literal content (true/false/null).
    val an = a.content.toDoubleOrNull()
    val bn = b.content.toDoubleOrNull()
    return if (an != null && bn != null) an == bn else a.content == b.content
}

/** Lenient JSON parser shared by parity tests (allows the machine-dumped JSONL). */
val parityJson: Json = Json { ignoreUnknownKeys = true }

fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
