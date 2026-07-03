package com.dvdutch.recall.compiler

/**
 * A parsed element tree modelling the SUBSET of lxml's `fragment_fromstring`
 * output the render compiler consumes.
 *
 * lxml (libxml2's HTML parser) exposes a text/tail model: [text] is the
 * character data between an element's start tag and its first child; [tail] is
 * the character data after the element's end tag, before its next sibling.
 * [Element]s with a `null` [tag] are comments / processing instructions — the
 * compiler drops their body but keeps their [tail].
 *
 * We do NOT aim for a general HTML5 parser; we replicate libxml2's observable
 * behavior on the card-template tag soup the corpus exercises: void elements,
 * auto-closing block/list tags, implicit close at EOF, entity decoding, and
 * dropped `<script>/<style>` raw-text bodies. Where genuinely-undefined tag soup
 * diverges, the 22k-side parity corpus is the arbiter and the tokenizer is bent
 * to match libxml2.
 */
class Element(
    val tag: String?,               // null => comment / processing instruction
    val attrs: Map<String, String> = emptyMap(),
) {
    var text: String? = null
    var tail: String? = null
    val children: MutableList<Element> = mutableListOf()

    fun attr(name: String): String? = attrs[name]

    /** Concatenated text of this element and all descendants (lxml `text_content`). */
    fun textContent(): String = buildString { appendTextContent(this@Element, this) }

    private fun appendTextContent(el: Element, sb: StringBuilder) {
        el.text?.let { sb.append(it) }
        for (child in el.children) {
            appendTextContent(child, sb)
            child.tail?.let { sb.append(it) }
        }
    }
}

/**
 * Tolerant HTML tokenizer + tree builder. Parses [html] into a single root
 * [Element] (mirroring lxml's `create_parent="anki-root"` wrapper) whose
 * children are the fragment's top-level nodes.
 */
object HtmlTokenizer {

    // Elements that never have children/close tags (HTML void elements).
    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    // Raw-text elements: content up to the matching close tag is CDATA, never
    // parsed as markup. libxml2 keeps their text; the compiler drops script/style.
    private val RAWTEXT = setOf("script", "style", "title", "textarea")

    // Implicit-close rules: opening a key tag auto-closes any currently-open
    // element in the value set (walking up the stack until one is found). This
    // reproduces libxml2's block/list auto-closing for the corpus's tag soup.
    private val CLOSES = mapOf(
        "li" to setOf("li"),
        "p" to setOf("p"),
        "tr" to setOf("tr", "td", "th"),
        "td" to setOf("td", "th"),
        "th" to setOf("td", "th"),
        "option" to setOf("option"),
    )

    fun parse(html: String): Element {
        val root = Element("anki-root")
        val stack = ArrayDeque<Element>()
        stack.addLast(root)

        // Pending text destined for the current open element: it becomes that
        // element's `text` if no child has been added yet, else the last child's
        // `tail`.
        val pendingText = StringBuilder()

        fun flushText() {
            if (pendingText.isEmpty()) return
            val parent = stack.last()
            val s = decodeEntities(pendingText.toString())
            if (parent.children.isEmpty()) {
                parent.text = (parent.text ?: "") + s
            } else {
                val last = parent.children.last()
                last.tail = (last.tail ?: "") + s
            }
            pendingText.setLength(0)
        }

        val n = html.length
        var i = 0
        while (i < n) {
            val c = html[i]
            if (c != '<') {
                pendingText.append(c)
                i++
                continue
            }
            // c == '<'
            if (i + 1 < n && html[i + 1] == '!') {
                // comment, CDATA, or doctype
                if (html.startsWith("<!--", i)) {
                    val end = html.indexOf("-->", i + 4)
                    val bodyEnd = if (end < 0) n else end
                    flushText()
                    val comment = Element(null)
                    comment.text = html.substring(i + 4, bodyEnd)
                    stack.last().children.add(comment)
                    i = if (end < 0) n else end + 3
                    continue
                }
                // doctype/declaration: skip to '>'
                val gt = html.indexOf('>', i)
                i = if (gt < 0) n else gt + 1
                continue
            }
            if (i + 1 < n && html[i + 1] == '?') {
                // processing instruction: drop body, keep as comment-like node
                val end = html.indexOf('>', i)
                flushText()
                val pi = Element(null)
                pi.text = html.substring(i + 2, if (end < 0) n else end)
                stack.last().children.add(pi)
                i = if (end < 0) n else end + 1
                continue
            }
            val isEnd = i + 1 < n && html[i + 1] == '/'
            // find end of tag
            val tagStart = if (isEnd) i + 2 else i + 1
            // A '<' not starting a valid tag name is literal text.
            if (tagStart >= n || !isTagNameStart(html[tagStart])) {
                pendingText.append('<')
                i++
                continue
            }
            val gt = findTagEnd(html, i)
            if (gt < 0) {
                // no closing '>': treat rest as text
                pendingText.append('<')
                i++
                continue
            }
            val inner = html.substring(tagStart, gt) // between name-start and '>'
            if (isEnd) {
                val name = readTagName(inner).lowercase()
                flushText()
                closeTag(stack, name, root)
                i = gt + 1
                continue
            }

            // start tag
            val selfClose = inner.trimEnd().endsWith("/")
            val name = readTagName(inner).lowercase()
            val attrs = parseAttrs(inner.substring(readTagNameLength(inner)))

            flushText()
            // implicit-close: opening `name` may close currently-open peers.
            CLOSES[name]?.let { closable ->
                while (stack.size > 1 && stack.last().tag in closable) {
                    stack.removeLast()
                }
            }

            val el = Element(name, attrs)
            stack.last().children.add(el)

            if (name in RAWTEXT) {
                // consume raw text up to matching close tag (case-insensitive).
                val closeTag = "</$name"
                val idx = indexOfIgnoreCase(html, closeTag, gt + 1)
                val rawEnd = if (idx < 0) n else idx
                el.text = html.substring(gt + 1, rawEnd)
                // advance past the close tag's '>'
                i = if (idx < 0) n else {
                    val closeGt = html.indexOf('>', idx)
                    if (closeGt < 0) n else closeGt + 1
                }
                continue
            }

            if (name in VOID || selfClose) {
                // void / self-closed: no children, no push.
                i = gt + 1
                continue
            }

            stack.addLast(el)
            i = gt + 1
        }
        flushText()
        return root
    }

    /**
     * Close the nearest open ancestor named [name]. If none is open, the end tag
     * is stray and ignored (libxml2 discards unmatched end tags). Elements popped
     * above the match are implicitly closed.
     */
    private fun closeTag(stack: ArrayDeque<Element>, name: String, root: Element) {
        // Is there a matching open element above root?
        var found = false
        for (k in stack.indices.reversed()) {
            if (k == 0) break
            if (stack[k].tag == name) {
                found = true
                break
            }
        }
        if (!found) return
        while (stack.size > 1) {
            val popped = stack.removeLast()
            if (popped.tag == name) break
        }
    }

    private fun isTagNameStart(c: Char): Boolean = c.isLetter()

    private fun readTagName(inner: String): String {
        val len = readTagNameLength(inner)
        return inner.substring(0, len)
    }

    private fun readTagNameLength(inner: String): Int {
        var k = 0
        while (k < inner.length && (inner[k].isLetterOrDigit() || inner[k] == '-' || inner[k] == ':')) k++
        return k
    }

    /**
     * Find the index of the '>' that ends the tag starting at [ltIndex],
     * respecting quoted attribute values (a '>' inside quotes doesn't end it).
     */
    private fun findTagEnd(html: String, ltIndex: Int): Int {
        var k = ltIndex + 1
        var quote = 0.toChar()
        val n = html.length
        while (k < n) {
            val c = html[k]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else {
                if (c == '"' || c == '\'') quote = c
                else if (c == '>') return k
            }
            k++
        }
        return -1
    }

    /** Parse attributes from the tag's remainder (after the tag name). */
    private fun parseAttrs(s: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        var k = 0
        val n = s.length
        while (k < n) {
            // skip whitespace and stray '/'
            while (k < n && (s[k].isWhitespace() || s[k] == '/')) k++
            if (k >= n) break
            val nameStart = k
            while (k < n && !s[k].isWhitespace() && s[k] != '=' && s[k] != '/' && s[k] != '>') k++
            val name = s.substring(nameStart, k).lowercase()
            if (name.isEmpty()) {
                k++
                continue
            }
            // skip whitespace
            while (k < n && s[k].isWhitespace()) k++
            var value = ""
            if (k < n && s[k] == '=') {
                k++
                while (k < n && s[k].isWhitespace()) k++
                if (k < n && (s[k] == '"' || s[k] == '\'')) {
                    val q = s[k]
                    k++
                    val vStart = k
                    while (k < n && s[k] != q) k++
                    value = s.substring(vStart, k)
                    if (k < n) k++ // closing quote
                } else {
                    val vStart = k
                    while (k < n && !s[k].isWhitespace() && s[k] != '>') k++
                    value = s.substring(vStart, k)
                }
            }
            // first occurrence wins (libxml2 keeps the first attribute of a name)
            if (!attrs.containsKey(name)) attrs[name] = decodeEntities(value)
        }
        return attrs
    }

    private fun indexOfIgnoreCase(haystack: String, needle: String, from: Int): Int {
        val end = haystack.length - needle.length
        var k = from
        while (k <= end) {
            if (haystack.regionMatches(k, needle, 0, needle.length, ignoreCase = true)) return k
            k++
        }
        return -1
    }

    // Minimal HTML entity decoding covering what card templates emit. Numeric
    // (&#nnn; / &#xhh;) and the common named entities; unknown entities pass
    // through verbatim (libxml2 does the same for undefined names).
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "copy" to "©", "reg" to "®", "trade" to "™",
        "deg" to "°", "middot" to "·", "bull" to "•",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“",
        "rdquo" to "”", "times" to "×", "divide" to "÷",
    )

    private fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var k = 0
        val n = s.length
        while (k < n) {
            val c = s[k]
            if (c != '&') {
                out.append(c)
                k++
                continue
            }
            val semi = s.indexOf(';', k + 1)
            if (semi < 0 || semi - k > 32) {
                out.append(c)
                k++
                continue
            }
            val body = s.substring(k + 1, semi)
            val decoded = decodeEntityBody(body)
            if (decoded != null) {
                out.append(decoded)
                k = semi + 1
            } else {
                out.append(c)
                k++
            }
        }
        return out.toString()
    }

    private fun decodeEntityBody(body: String): String? {
        if (body.isEmpty()) return null
        if (body[0] == '#') {
            val cp = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            } ?: return null
            if (cp < 0 || cp > 0x10FFFF) return null
            return try {
                String(Character.toChars(cp))
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return NAMED[body]
    }
}
