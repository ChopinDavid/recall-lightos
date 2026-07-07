package com.dvdutch.recall.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelsTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Missing fixture: $name"
        }.bufferedReader().use { it.readText() }

    private fun List<RenderNode>.flatten(): String = buildString {
        for (node in this@flatten) {
            when (node) {
                is TextNode -> node.runs.forEach { append(it.s) }
                is ClozeNode -> {
                    node.text?.let { append(it) }
                    node.hint?.let { append(it) }
                }
                is ImageNode -> append(node.src)
                is OcclusionNode -> append(node.image)
                is RowNode -> node.cells.forEach { append(it.nodes.flatten()) }
                is RuleNode -> Unit
                is UnsupportedNode -> Unit
            }
        }
    }

    @Test
    fun decksFixtureParses() {
        val response = BridgeJson.decodeFromString<DecksResponse>(fixture("decks.json"))
        val russian = response.decks.first { it.name == "Russian" }
        // NOTE: the brief states new == 20 for "Russian", but the real fixture's
        // top-level "Russian" deck has new == 19 (the 20s belong to sub-decks such
        // as "Russian::Pimsleur Russian"). Tests assert real fixture content.
        assertEquals(19, russian.new)
    }

    @Test
    fun queueFixtureParses() {
        val response = BridgeJson.decodeFromString<QueueResponse>(fixture("queue.json"))
        assertEquals(3, response.cards.size)

        val first = response.cards.first()
        val frontText = first.front.flatten()
        assertTrue(frontText.contains("Genitive"), "front should contain 'Genitive': $frontText")
        assertTrue(frontText.contains("роди́тельный"), "front should contain Cyrillic: $frontText")

        assertFalse(first.states.isEmpty(), "states should be non-empty")
        // base64-shaped: only base64 alphabet chars
        assertTrue(
            first.states.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' },
            "states should be base64-shaped: ${first.states}",
        )

        assertEquals(setOf("again", "hard", "good", "easy"), first.nextDueLabels.keys)
    }

    @Test
    fun unknownNodeParsesAsUnsupported() {
        val node = BridgeJson.decodeFromString<RenderNode>("""{"t":"video3d","foo":1}""")
        assertEquals(UnsupportedNode("video3d"), node)
    }

    @Test
    fun statusFixtureParses() {
        val response = BridgeJson.decodeFromString<StatusResponse>(fixture("status.json"))
        assertFalse(response.needsAttention)
    }
}
