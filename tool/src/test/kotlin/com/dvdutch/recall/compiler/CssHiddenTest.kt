package com.dvdutch.recall.compiler

import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The 8 adversarial CSS over-hide cases, ported verbatim from the bridge's
 * `test_compiler.py`. The compiler must NEVER over-hide (drop content that a
 * real browser would paint) and must still hide genuine `display: none`.
 */
class CssHiddenTest {

    private fun text(nodes: List<RenderNode>): String = buildString {
        for (n in nodes) if (n is TextNode) n.runs.forEach { append(it.s) }
    }

    private fun visible(html: String, css: String, needle: String = "SECRET"): Boolean =
        text(compileHtml(html, side = "front", css = css)).contains(needle)

    // --- never-over-hide: at-rules must not hide unconditionally --------------

    @Test
    fun mediaAtRuleDoesNotHide() {
        val html = """<div class="foo">SECRET</div>"""
        val css = "@media screen { .foo { display: none } }"
        assertTrue(visible(html, css))
    }

    @Test
    fun supportsAtRuleDoesNotHide() {
        val html = """<div class="foo">SECRET</div>"""
        val css = "@supports (display: grid) { .foo { display: none } }"
        assertTrue(visible(html, css))
    }

    // --- never-over-hide: exact display:none property/value matching ----------

    @Test
    fun backgroundDisplayNoneDoesNotHide() {
        val html = """<div class="foo">SECRET</div>"""
        val css = ".foo { background-display: none }"
        assertTrue(visible(html, css))
    }

    @Test
    fun displayNoneIshDoesNotHide() {
        val html = """<div class="foo">SECRET</div>"""
        val css = ".foo { display: none-ish }"
        assertTrue(visible(html, css))
    }

    // --- still hides where it genuinely should --------------------------------

    @Test
    fun displayNoneCaseAndImportantStillHides() {
        val html = """<div class="foo">SECRET</div>"""
        val css = ".foo { display: NONE !important }"
        assertFalse(visible(html, css))
    }

    @Test
    fun displayNoneMultiDeclarationStillHides() {
        val html = """<div class="foo">SECRET</div>"""
        val css = ".foo { color: red; display: none }"
        assertFalse(visible(html, css))
    }

    @Test
    fun atRuleStrippingKeepsFollowingRules() {
        val html = """<div class="hidden">SECRET</div><div class="show">SHOWN</div>"""
        val css = "@media print { .show { display: none } } .hidden { display: none }"
        val t = text(compileHtml(html, side = "front", css = css))
        assertFalse(t.contains("SECRET"), "plain .hidden rule still applied")
        assertTrue(t.contains("SHOWN"), ".show only hidden under @media print")
    }

    @Test
    fun tagHashIdIsSkippedAsComplex() {
        val html = """<span id="x">SECRET</span>"""
        val css = "div#x { display: none }"
        assertTrue(visible(html, css)) // tag#id skipped -> span not hidden
        // sanity: the conservative bare-id form still hides.
        assertFalse(visible("""<span id="x">SECRET</span>""", "#x { display: none }"))
    }

    // --- visibility: hidden hides content, same as display: none --------------
    // A real browser paints neither `display:none` nor `visibility:hidden`, so
    // the compiler must drop both (Russian Core 5000 scaffolding leak). Mirrors
    // the display:none adversarial suite exactly.

    @Test
    fun visibilityHiddenHides() {
        val html = """<div class="foo">SECRET</div>"""
        assertFalse(visible(html, ".foo { visibility: hidden }"))
    }

    @Test
    fun visibilityHiddenCaseAndImportantStillHides() {
        val html = """<div class="foo">SECRET</div>"""
        assertFalse(visible(html, ".foo { visibility: HIDDEN !important }"))
    }

    @Test
    fun visibilityHiddenMultiDeclarationStillHides() {
        val html = """<div class="foo">SECRET</div>"""
        assertFalse(visible(html, ".foo { color: red; visibility: hidden }"))
    }

    @Test
    fun visibilityHiddenInlineStyleHides() {
        val html = """<div style="visibility: hidden">SECRET</div>"""
        assertFalse(visible(html, ""))
    }

    @Test
    fun visibilityVisibleDoesNotHide() {
        val html = """<div class="foo">SECRET</div>"""
        assertTrue(visible(html, ".foo { visibility: visible }"))
    }

    @Test
    fun visibilityCollapseDoesNotHide() {
        // `collapse` only hides table rows/columns; only exact `hidden` hides.
        val html = """<div class="foo">SECRET</div>"""
        assertTrue(visible(html, ".foo { visibility: collapse }"))
    }

    @Test
    fun xVisibilityHiddenDoesNotHide() {
        // property must be exactly `visibility`, not a substring.
        val html = """<div class="foo">SECRET</div>"""
        assertTrue(visible(html, ".foo { x-visibility: hidden }"))
    }

    @Test
    fun visibilityHiddenIshDoesNotHide() {
        // value must be exactly `hidden`, not a prefix of `hidden-ish`.
        val html = """<div class="foo">SECRET</div>"""
        assertTrue(visible(html, ".foo { visibility: hidden-ish }"))
    }

    @Test
    fun visibilityHiddenMediaAtRuleDoesNotHide() {
        val html = """<div class="foo">SECRET</div>"""
        assertTrue(visible(html, "@media screen { .foo { visibility: hidden } }"))
    }

    @Test
    fun visibilityHiddenOnlyCssStillParses() {
        // a stylesheet with visibility but no `display` token must still parse
        // (the early-exit guard now checks for `visibility` too).
        val html = """<div class="foo">SECRET</div>"""
        assertFalse(visible(html, ".foo { visibility: hidden }"))
    }

    // --- degradation ladder: never raises on garbage --------------------------

    @Test
    fun neverRaisesOnGarbage() {
        for (garbage in listOf("<div><", "<script>alert(1)</script>", "\u0000\u0001", "<table><tr><td>x")) {
            // must return without throwing; content preserved as text at worst.
            val nodes = compileHtml(garbage, side = "front")
            assertTrue(nodes.size >= 0)
        }
    }
}
