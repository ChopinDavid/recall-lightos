package com.dvdutch.recall.compiler

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Node-identical parity against the 9 hand-authored golden fixtures, dumped by
 * `bridge/tools/dump_parity_corpus.py` into `goldens.jsonl`. Each line carries
 * `{case, html, css, side, expected}`; we compile `html` and assert the emitted
 * nodes serialize to exactly `expected`.
 */
class GoldenParityTest {

    private data class Golden(
        val case: String,
        val html: String,
        val css: String,
        val side: String,
        val expected: JsonElement,
    )

    private fun goldens(): List<Golden> {
        val text = requireNotNull(javaClass.getResourceAsStream("/parity/goldens.jsonl")) {
            "Missing /parity/goldens.jsonl test resource"
        }.bufferedReader().use { it.readText() }
        return text.lineSequence().filter { it.isNotBlank() }.map { line ->
            val obj = parityJson.parseToJsonElement(line) as JsonObject
            Golden(
                case = obj.str("case"),
                html = obj.str("html"),
                css = obj.str("css"),
                side = obj.str("side"),
                expected = obj.getValue("expected"),
            )
        }.toList()
    }

    @Test
    fun goldensAreNodeIdentical() {
        val failures = mutableListOf<String>()
        for (g in goldens()) {
            val actual = nodesToJson(compileHtml(g.html, g.side, g.css))
            if (!jsonEquals(actual, g.expected)) {
                failures += "case ${g.case}:\n  expected: ${g.expected}\n  actual:   $actual"
            }
        }
        assertEquals(emptyList(), failures, "golden mismatches:\n" + failures.joinToString("\n"))
    }
}
