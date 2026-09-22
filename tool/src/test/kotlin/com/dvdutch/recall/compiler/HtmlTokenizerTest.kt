package com.dvdutch.recall.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tokenizer-level tests for the tag-soup paths the render corpus reaches only
 * indirectly: comments and processing instructions, doctypes, unterminated markup,
 * raw-text elements, implicit close rules, stray end tags, attribute quirks, and
 * numeric entity decoding.
 *
 * These assert the PARSE TREE (text/tail model) rather than compiled render nodes,
 * because the text/tail split and the comment/PI node placement are exactly what the
 * compiler downstream depends on and what libxml2 parity is judged against.
 */
class HtmlTokenizerTest {

    private fun parse(html: String): Element = HtmlTokenizer.parse(html)

    private fun kids(html: String): List<Element> = parse(html).children

    /** The tags of a root's children, with `null` (comment/PI) spelled out. */
    private fun kidTags(html: String): List<String?> = kids(html).map { it.tag }

    // ---- the root wrapper -----------------------------------------------------

    @Test
    fun rootIsAnAnkiRootWrapper() {
        // Mirrors lxml's `create_parent="anki-root"`: the fragment's top-level nodes
        // are the wrapper's children, never the root itself.
        val root = parse("<b>hi</b>")
        assertEquals("anki-root", root.tag)
        assertEquals(listOf("b"), root.children.map { it.tag })
    }

    @Test
    fun bareTextBecomesTheRootsOwnText() {
        val root = parse("just text")
        assertEquals("just text", root.text)
        assertTrue(root.children.isEmpty())
    }

    // ---- text / tail model ----------------------------------------------------

    @Test
    fun textBeforeAChildIsTextAndAfterItIsTail() {
        // lxml's model: `before` is the parent's text, `after` is the child's tail.
        val root = parse("<p>before<b>bold</b>after</p>")
        val p = root.children.single()
        assertEquals("before", p.text)
        val b = p.children.single()
        assertEquals("bold", b.text)
        assertEquals("after", b.tail)
        assertNull(p.tail)
    }

    @Test
    fun textAfterTheSecondChildTailsTheSecondChild() {
        // Pending text always lands on the LAST child added, not the first.
        val p = parse("<p><b>x</b>mid<i>y</i>end</p>").children.single()
        assertEquals("mid", p.children[0].tail)
        assertEquals("end", p.children[1].tail)
    }

    @Test
    fun textContentConcatenatesDescendantsAndTails() {
        // `textContent` walks text, then each child's content followed by its tail.
        val p = parse("<p>a<b>b<i>c</i>d</b>e</p>").children.single()
        assertEquals("abcde", p.textContent())
    }

    @Test
    fun textContentOfAnEmptyElementIsEmpty() {
        assertEquals("", parse("<p></p>").children.single().textContent())
    }

    // ---- comments -------------------------------------------------------------

    @Test
    fun aCommentBecomesANullTagNodeCarryingItsBody() {
        val c = kids("<!-- note -->").single()
        assertNull(c.tag)
        assertEquals(" note ", c.text)
    }

    @Test
    fun textAfterACommentIsTheCommentsTail() {
        // The compiler drops a comment's body but KEEPS its tail, so the tail must
        // attach to the comment node rather than being lost.
        val out = kids("<!--c-->after")
        assertEquals(listOf<String?>(null), out.map { it.tag })
        assertEquals("after", out.single().tail)
    }

    @Test
    fun textBeforeACommentIsFlushedFirst() {
        val root = parse("before<!--c-->")
        assertEquals("before", root.text)
        assertNull(root.children.single().tag)
    }

    @Test
    fun anUnterminatedCommentRunsToEndOfInput() {
        // No `-->`: the rest of the document is the comment body (and is dropped
        // downstream) rather than being emitted as visible text.
        val c = kids("<!-- never closed").single()
        assertNull(c.tag)
        assertEquals(" never closed", c.text)
    }

    @Test
    fun markupInsideACommentIsNotParsed() {
        val out = kids("<!-- <b>not bold</b> -->")
        assertEquals(1, out.size)
        assertNull(out.single().tag)
    }

    // ---- doctype / declarations ----------------------------------------------

    @Test
    fun aDoctypeIsSkippedEntirely() {
        // A declaration is neither a node nor text: it vanishes, leaving the content.
        assertEquals(listOf("p"), kidTags("<!DOCTYPE html><p>x</p>"))
        assertNull(parse("<!DOCTYPE html><p>x</p>").text)
    }

    @Test
    fun anUnterminatedDeclarationConsumesTheRest() {
        val root = parse("<!DOCTYPE html")
        assertTrue(root.children.isEmpty())
        assertNull(root.text)
    }

    // ---- processing instructions ---------------------------------------------

    @Test
    fun aProcessingInstructionBecomesANullTagNode() {
        val pi = kids("<?xml version=\"1.0\"?>").single()
        assertNull(pi.tag)
        assertEquals("xml version=\"1.0\"?", pi.text)
    }

    @Test
    fun textAroundAProcessingInstructionIsPreserved() {
        val root = parse("a<?pi?>b")
        assertEquals("a", root.text)
        assertEquals("b", root.children.single().tail)
    }

    @Test
    fun anUnterminatedProcessingInstructionConsumesTheRest() {
        val pi = kids("<?pi never closed").single()
        assertNull(pi.tag)
        assertEquals("pi never closed", pi.text)
    }

    // ---- a `<` that does not start a tag -------------------------------------

    @Test
    fun aLoneLessThanIsLiteralText() {
        // `1 < 2` in card prose must survive as text, not swallow the rest.
        assertEquals("1 < 2 is true", parse("1 < 2 is true").text)
    }

    @Test
    fun aLessThanFollowedByANonLetterIsLiteral() {
        assertEquals("a <3 b", parse("a <3 b").text)
    }

    @Test
    fun aTrailingLessThanAtEndOfInputIsLiteral() {
        assertEquals("end <", parse("end <").text)
    }

    @Test
    fun anUnclosedTagWithNoGreaterThanIsLiteralText() {
        // `<b` with no '>' anywhere: emitted as text rather than opening an element.
        val root = parse("x <b class=\"y\"")
        assertTrue(root.children.isEmpty())
        assertEquals("x <b class=\"y\"", root.text)
    }

    // ---- raw-text elements ----------------------------------------------------

    @Test
    fun scriptBodyIsRawTextNotMarkup() {
        val s = kids("<script>if (a<b) x=\"</i>\";</script>").single()
        assertEquals("script", s.tag)
        assertEquals("if (a<b) x=\"</i>\";", s.text)
        assertTrue(s.children.isEmpty(), "raw text must not build children")
    }

    @Test
    fun styleBodyIsRawText() {
        val s = kids("<style>.a > .b { color: red }</style>").single()
        assertEquals("style", s.tag)
        assertEquals(".a > .b { color: red }", s.text)
    }

    @Test
    fun rawTextCloseTagIsMatchedCaseInsensitively() {
        val s = kids("<script>body</SCRIPT>tail").single()
        assertEquals("body", s.text)
        assertEquals("tail", s.tail)
    }

    @Test
    fun anUnclosedRawTextElementConsumesTheRest() {
        val s = kids("<style>.a { color: red }").single()
        assertEquals(".a { color: red }", s.text)
    }

    @Test
    fun rawTextWithACloseTagMissingItsGreaterThanConsumesTheRest() {
        // `</script` with no '>' — the raw body ends there but input is exhausted.
        val s = kids("<script>body</script").single()
        assertEquals("body", s.text)
    }

    @Test
    fun contentAfterARawTextElementParsesNormally() {
        assertEquals(listOf("script", "b"), kidTags("<script>x</script><b>y</b>"))
    }

    // ---- void and self-closing tags ------------------------------------------

    @Test
    fun aVoidElementTakesNoChildren() {
        val root = parse("<br>after")
        val br = root.children.single()
        assertEquals("br", br.tag)
        assertTrue(br.children.isEmpty())
        assertEquals("after", br.tail)
    }

    @Test
    fun aSelfClosedNonVoidElementTakesNoChildren() {
        assertEquals(listOf("span", "b"), kidTags("<span/><b>x</b>"))
        assertTrue(kids("<span/><b>x</b>")[0].children.isEmpty())
    }

    @Test
    fun anImgIsVoidAndKeepsItsAttributes() {
        val img = kids("""<img src="a.png" alt="x">""").single()
        assertEquals("img", img.tag)
        assertEquals("a.png", img.attr("src"))
        assertEquals("x", img.attr("alt"))
    }

    // ---- implicit close -------------------------------------------------------

    @Test
    fun anOpenListItemIsClosedByTheNextOne() {
        // `<li>a<li>b` — the second `li` closes the first, so they are siblings.
        val lis = parse("<ul><li>a<li>b</ul>").children.single().children
        assertEquals(listOf("li", "li"), lis.map { it.tag })
        assertEquals("a", lis[0].text)
        assertEquals("b", lis[1].text)
    }

    @Test
    fun anOpenParagraphIsClosedByTheNextOne() {
        val ps = kidTags("<p>one<p>two")
        assertEquals(listOf("p", "p"), ps)
    }

    @Test
    fun aTableCellIsClosedByTheNextCellAndRowByRow() {
        // Opening `tr` closes an open td/th; opening `td` closes an open td/th.
        val rows = parse("<table><tr><td>a<td>b<tr><td>c</table>")
            .children.single().children
        assertEquals(listOf("tr", "tr"), rows.map { it.tag })
        assertEquals(listOf("td", "td"), rows[0].children.map { it.tag })
        assertEquals("a", rows[0].children[0].text)
        assertEquals("c", rows[1].children.single().text)
    }

    @Test
    fun aHeaderCellIsClosedByADataCell() {
        val cells = parse("<tr><th>h<td>d").children.single().children
        assertEquals(listOf("th", "td"), cells.map { it.tag })
    }

    @Test
    fun anOpenOptionIsClosedByTheNextOne() {
        val opts = parse("<select><option>a<option>b</select>")
            .children.single().children
        assertEquals(listOf("option", "option"), opts.map { it.tag })
    }

    @Test
    fun implicitCloseStopsAtTheFirstNonClosablyOpenElement() {
        // The rule pops only while the TOP of the stack is closable by the new tag.
        // With an unclosed `<b>` on top, `td` is not in CLOSES["td"]'s reach, so the
        // open cell is NOT closed and the new `td` nests inside the `b`.
        val cells = parse("<tr><td><b>x<td>y").children.single().children
        assertEquals(listOf("td"), cells.map { it.tag })
        val b = cells.single().children.single()
        assertEquals("b", b.tag)
        assertEquals(listOf("td"), b.children.map { it.tag })
        assertEquals("y", b.children.single().text)
    }

    @Test
    fun implicitCloseWalksUpSeveralClosableOpenPeers() {
        // When the stack top IS closable the loop keeps popping: `th` then `td` are
        // both in CLOSES["td"], so a new `td` unwinds both.
        val cells = parse("<tr><th>h<td>a<td>b").children.single().children
        assertEquals(listOf("th", "td", "td"), cells.map { it.tag })
    }

    // ---- end tags -------------------------------------------------------------

    @Test
    fun aStrayEndTagIsIgnored() {
        // libxml2 discards an end tag with no matching open element; the text around
        // it must survive.
        val root = parse("a</b>c")
        assertTrue(root.children.isEmpty())
        assertEquals("ac", root.text)
    }

    @Test
    fun aStrayEndTagDoesNotPopAnUnrelatedOpenElement() {
        // `</i>` matches nothing open, so `b` stays open and keeps the later text.
        val b = parse("<b>x</i>y</b>").children.single()
        assertEquals("b", b.tag)
        assertEquals("xy", b.text)
    }

    @Test
    fun anEndTagClosesTheNearestMatchingAncestorPoppingElementsAbove() {
        // `</div>` with an unclosed `b` inside: the b is implicitly closed too, and
        // the following text is the div's tail, not the b's.
        val root = parse("<div><b>x</div>after")
        val div = root.children.single()
        assertEquals("div", div.tag)
        assertEquals("b", div.children.single().tag)
        assertEquals("after", div.tail)
    }

    @Test
    fun anEndTagIsMatchedCaseInsensitively() {
        val b = parse("<B>x</b>y").children.single()
        assertEquals("b", b.tag)
        assertEquals("y", b.tail)
    }

    @Test
    fun unclosedElementsAreImplicitlyClosedAtEndOfInput() {
        val div = parse("<div><b>x").children.single()
        assertEquals("div", div.tag)
        assertEquals("x", div.children.single().text)
    }

    // ---- attributes -----------------------------------------------------------

    @Test
    fun attributeNamesAreLowercasedAndValuesKept() {
        val el = kids("""<div CLASS="Big" DATA-X="1">x</div>""").single()
        assertEquals("Big", el.attr("class"))
        assertEquals("1", el.attr("data-x"))
    }

    @Test
    fun singleQuotedAndUnquotedValuesAreBothRead() {
        val el = kids("<div a='one' b=two c=\"three\">x</div>").single()
        assertEquals("one", el.attr("a"))
        assertEquals("two", el.attr("b"))
        assertEquals("three", el.attr("c"))
    }

    @Test
    fun aValuelessAttributeIsEmptyString() {
        val el = kids("<input disabled>").single()
        assertEquals("", el.attr("disabled"))
    }

    @Test
    fun aGreaterThanInsideAQuotedValueDoesNotEndTheTag() {
        // findTagEnd respects quotes, so the `>` in the selector stays in the value.
        val el = kids("""<div title="a > b">x</div>""").single()
        assertEquals("a > b", el.attr("title"))
        assertEquals("x", el.text)
    }

    @Test
    fun theFirstOccurrenceOfARepeatedAttributeWins() {
        // libxml2 keeps the first attribute of a given name.
        assertEquals("1", kids("""<div a="1" a="2">x</div>""").single().attr("a"))
    }

    @Test
    fun entitiesInAttributeValuesAreDecoded() {
        assertEquals("a&b", kids("""<div title="a&amp;b">x</div>""").single().attr("title"))
    }

    @Test
    fun extraWhitespaceAndSlashesAroundAttributesAreTolerated() {
        val el = kids("""<img   src = "a.png"  /  >""").single()
        assertEquals("a.png", el.attr("src"))
        assertEquals("img", el.tag)
    }

    @Test
    fun anUnterminatedQuotedValueMakesTheWholeTagLiteralText() {
        // findTagEnd ignores a '>' inside quotes, so an unclosed quote means the tag
        // never ends: there is no element, and the markup survives as visible text
        // rather than silently eating the rest of the card.
        val root = parse("""<div title="unclosed>x""")
        assertTrue(root.children.isEmpty())
        assertEquals("""<div title="unclosed>x""", root.text)
    }

    @Test
    fun aStrayEqualsDoesNotDerailAttributeParsing() {
        // A leading `=` yields an empty name, which is skipped; `b` still parses.
        val el = kids("""<div ="x" b="2">y</div>""").single()
        assertEquals("2", el.attr("b"))
    }

    @Test
    fun anUnknownAttributeLookupIsNull() {
        assertNull(kids("<div a=\"1\">x</div>").single().attr("nope"))
    }

    @Test
    fun aNamespacedOrHyphenatedTagNameIsRead() {
        assertEquals(listOf("my-tag"), kidTags("<my-tag>x</my-tag>"))
        assertEquals(listOf("o:p"), kidTags("<o:p>x</o:p>"))
    }

    // ---- entity decoding ------------------------------------------------------

    @Test
    fun namedEntitiesDecode() {
        assertEquals("&<>\"'", parse("&amp;&lt;&gt;&quot;&apos;").text)
        assertEquals("—–…", parse("&mdash;&ndash;&hellip;").text)
    }

    @Test
    fun nbspDecodesToNoBreakSpace() {
        assertEquals("a b", parse("a&nbsp;b").text)
    }

    @Test
    fun decimalNumericEntitiesDecode() {
        assertEquals("A", parse("&#65;").text)
    }

    @Test
    fun hexNumericEntitiesDecodeInEitherCase() {
        assertEquals("A", parse("&#x41;").text)
        assertEquals("A", parse("&#X41;").text)
    }

    @Test
    fun anAstralNumericEntityDecodesToASurrogatePair() {
        // U+1F600; Character.toChars yields two chars, so the text is 2 code units.
        val out = parse("&#x1F600;").text!!
        assertEquals("😀", out)
        assertEquals(2, out.length)
    }

    @Test
    fun anUnknownNamedEntityPassesThroughVerbatim() {
        // libxml2 leaves undefined entity names alone.
        assertEquals("&notanentity;", parse("&notanentity;").text)
    }

    @Test
    fun anEntityWithNoSemicolonPassesThrough() {
        assertEquals("a & b", parse("a & b").text)
    }

    @Test
    fun anAmpersandAtEndOfInputPassesThrough() {
        assertEquals("tail &", parse("tail &").text)
    }

    @Test
    fun anEmptyEntityBodyPassesThrough() {
        assertEquals("&;", parse("&;").text)
    }

    @Test
    fun aVeryLongEntityCandidateIsNotTreatedAsAnEntity() {
        // Bodies longer than 32 chars are not entities — an `&` followed by a distant
        // `;` in prose must not swallow the text between them.
        val prose = "&" + "x".repeat(40) + ";"
        assertEquals(prose, parse(prose).text)
    }

    @Test
    fun aNonNumericNumericEntityPassesThrough() {
        assertEquals("&#zz;", parse("&#zz;").text)
        assertEquals("&#xzz;", parse("&#xzz;").text)
    }

    @Test
    fun anOutOfRangeCodePointPassesThrough() {
        // Above U+10FFFF there is no character to make.
        assertEquals("&#x110000;", parse("&#x110000;").text)
        assertEquals("&#99999999999;", parse("&#99999999999;").text)
    }

    @Test
    fun anUnpairedSurrogateEntityDecodesWithoutThrowing() {
        // U+D800 is an unpaired surrogate. `Character.toChars` accepts it (a single
        // code unit) rather than raising, so it decodes to that lone char. The point
        // of the test is the NEVER-RAISES contract: no input reaches the caller as an
        // exception. (This is why the decoder's IllegalArgumentException catch is
        // effectively unreachable: the range check above it already rejects the only
        // values toChars would reject.)
        val out = parse("&#xD800;").text!!
        assertEquals(1, out.length)
        assertEquals('\uD800', out[0])
    }

    @Test
    fun aDecodedEntityInTailTextIsAlsoDecoded() {
        assertEquals("&", parse("<b>x</b>&amp;").children.single().tail)
    }
}
