package com.dvdutch.recall.compiler

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The port's definition of done: NODE-IDENTICAL output against the Python
 * reference on every real card side (~22k) in the machine-generated corpus.
 *
 * The corpus is a personal deck and stays LOCAL (never committed), so this test
 * reads its absolute path from the `recall.parityCorpus` system property (wired
 * from `-Precall.parityCorpus=...` in build.gradle.kts) and SKIPS CLEANLY when
 * absent — CI has no corpus. When present, any mismatch fails the build with a
 * first-N diagnostic dump (html / css / expected / actual) for evidence-driven
 * fixing.
 */
class ParityTest {

    private val maxReported = 20

    @Test
    fun corpusIsNodeIdentical() {
        val path = System.getProperty("recall.parityCorpus")
        if (path.isNullOrBlank()) {
            println("[ParityTest] recall.parityCorpus not set; skipping (expected on CI).")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            println("[ParityTest] corpus not found at $path; skipping.")
            return
        }

        var total = 0
        var mismatches = 0
        val report = StringBuilder()

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                total++
                val obj = parityJson.parseToJsonElement(line) as JsonObject
                val html = obj.str("html")
                val css = obj.str("css")
                val side = obj.str("side")
                val expected: JsonElement = obj.getValue("expected")

                val actual = nodesToJson(compileHtml(html, side, css))
                if (!jsonEquals(actual, expected)) {
                    mismatches++
                    if (mismatches <= maxReported) {
                        report.append("--- mismatch #$mismatches (line $total, side=$side) ---\n")
                        report.append("html:     ").append(truncate(html)).append('\n')
                        val diff = firstDiff(expected, actual)
                        report.append("firstDiff:").append(diff).append('\n')
                        report.append("expected: ").append(truncate(expected.toString())).append('\n')
                        report.append("actual:   ").append(truncate(actual.toString())).append('\n')
                    }
                }
            }
        }

        println("[ParityTest] compared $total sides, $mismatches mismatches.")
        assertEquals(
            0,
            mismatches,
            "parity mismatches: $mismatches / $total\n$report",
        )
    }

    private fun truncate(s: String, limit: Int = 600): String =
        if (s.length <= limit) s else s.substring(0, limit) + "…(${s.length} chars)"

    /** Locate the first array index whose nodes differ, and describe the pair. */
    private fun firstDiff(expected: JsonElement, actual: JsonElement): String {
        val e = (expected as? kotlinx.serialization.json.JsonArray)
        val a = (actual as? kotlinx.serialization.json.JsonArray)
        if (e == null || a == null) return " arrays? e=$e a=$a"
        val max = maxOf(e.size, a.size)
        for (idx in 0 until max) {
            val ei = e.getOrNull(idx)
            val ai = a.getOrNull(idx)
            if (ei == null || ai == null || !jsonEquals(ei, ai)) {
                val es = ei.toString()
                val asr = ai.toString()
                val cp = firstCharDiff(es, asr)
                return " [$idx] (sizes e=${e.size} a=${a.size})\n" +
                    "    exp[$idx]=$es\n    act[$idx]=$asr\n    charDiff=$cp"
            }
        }
        return " (equal length ${e.size}, values differ deeper)"
    }

    private fun firstCharDiff(a: String, b: String): String {
        val m = minOf(a.length, b.length)
        for (k in 0 until m) {
            if (a[k] != b[k]) {
                val ctxE = a.substring(maxOf(0, k - 8), minOf(a.length, k + 8))
                val ctxA = b.substring(maxOf(0, k - 8), minOf(b.length, k + 8))
                return "@${k}: exp U+%04X '%s' vs act U+%04X '%s' | ctxE=[%s] ctxA=[%s]"
                    .format(a[k].code, a[k], b[k].code, b[k], ctxE, ctxA)
            }
        }
        return "prefix-equal, lengths a=${a.length} b=${b.length}; tail=[${(if (a.length > b.length) a else b).substring(m)}]"
    }
}
