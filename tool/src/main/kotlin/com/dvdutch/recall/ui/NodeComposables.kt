package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dvdutch.recall.api.BlockAlign
import com.dvdutch.recall.api.ClozeNode
import com.dvdutch.recall.api.DetailsNode
import com.dvdutch.recall.api.ImageNode
import com.dvdutch.recall.api.OcclusionNode
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.RowCell
import com.dvdutch.recall.api.RowNode
import com.dvdutch.recall.api.RuleNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.api.UnsupportedNode
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Relative font size applied to `small` runs so it scales with the base style. */
private const val SMALL_TEXT_SCALE = 0.8f

/**
 * Fix C — layers a uniform [LineHeightStyle] onto a card text style so EVERY line
 * box is the same height regardless of combining marks. Without it, a combining
 * acute accent (U+0301, e.g. "надéялся") extends above the normal ascent and
 * Compose's DEFAULT line-height distribution lets that line's box grow to fit the
 * mark — the wrapped Russian sentence then shows a much larger inter-line gap than
 * the plain-ASCII English wrap. `trim = None` keeps the explicit lineHeight fully
 * applied (no first/last-line trimming) and `Center` distributes it symmetrically,
 * so the box height no longer tracks per-line glyph ascent. `includeFontPadding =
 * false` additionally drops the platform's extra font-metric padding, which a
 * combining accent would otherwise inflate, so the accented line's advance matches
 * the plain lines. Together they mirror AnkiDroid's CSS `line-height: 1.5`. Pure
 * (Compose-runtime-free) so it is unit-tested directly.
 */
fun cardCopyStyle(base: TextStyle): TextStyle = base.copy(
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    // A paragraph's direction follows its content: an Arabic or Hebrew card reads
    // right-to-left (right-aligned, continuation lines on the right) instead of being
    // laid out as a left-to-right paragraph of RTL words.
    textDirection = TextDirection.Content,
)

/**
 * The [UnsupportedNode.kind] the engine tags a side's audio marker with. Its inline
 * `▢ [audio]` placeholder is retired: audio is real playback with a replay affordance
 * in StudyScreen, so this kind renders nothing.
 */
private const val AUDIO_NODE_KIND = "audio"

/** Wire value of [ClozeNode.state] for a cloze that is still hidden (question side). */
private const val CLOZE_STATE_HIDDEN = "hidden"

/**
 * The inline replay glyph: a PLAY triangle with U+FE0E forcing text presentation, so it
 * renders as a font glyph (inheriting the content color, respecting font metrics — no
 * emoji-box overflow/clipping) rather than a color emoji.
 */
internal const val AUDIO_GLYPH = "▶︎"

/**
 * Downward ink re-centering for the ▶ glyph from its baseline-anchored box, as a fraction
 * of its font size. The placeholder anchors to the BASELINE (not the line center) because
 * combining accents (e.g. Кири́лл) grow a line's box and shift its center — the baseline is
 * the one reference they cannot move, so one measured constant lands the glyph on the
 * x-height optical center of BOTH plain and accented lines (pixel-verified ≤1px on each).
 */
private const val AUDIO_GLYPH_INK_OFFSET_EM = 0.20f

/** The inlineContent id for the audio-replay glyph at track [track] within a text node. */
internal fun audioInlineId(track: Int): String = "audio:$track"

/**
 * How far each list level steps its items in, in em of the card base text (a browser's
 * 40px `<ul>` padding, scaled to the LP3's narrow column).
 */
private const val LIST_INDENT_EM = 1.25f

/** The gap between a list marker and its item's text, in em of the card base text. */
private const val LIST_MARKER_GAP_EM = 0.3f

/** The chevron heading a closed / open [DetailsNode] summary line. */
private const val DETAILS_CLOSED_GLYPH = "▸ "
private const val DETAILS_OPEN_GLYPH = "▾ "

/** Pure mapping from a [TextNode]'s runs to a styled [AnnotatedString] (see [runsToAnnotatedString]). */
fun textNodeToAnnotatedString(node: TextNode): AnnotatedString = runsToAnnotatedString(node.runs)

/**
 * A list item's marker run (its first run, when it is one) and the runs of the item's
 * text after it. Pure and unit-tested.
 */
fun splitListMarker(node: TextNode): Pair<TextRun?, List<TextRun>> = splitListMarker(node.runs)

/** [splitListMarker] over bare runs (a [DetailsNode] summary line inside a list item). */
fun splitListMarker(runs: List<TextRun>): Pair<TextRun?, List<TextRun>> {
    val first = runs.firstOrNull()
    return if (first != null && first.marker) first to runs.drop(1) else null to runs
}

/** A [DetailsNode] summary line: the open/closed chevron, then the summary's text. Pure and unit-tested. */
fun detailsSummaryText(open: Boolean, runs: List<TextRun>): AnnotatedString = buildAnnotatedString {
    append(if (open) DETAILS_OPEN_GLYPH else DETAILS_CLOSED_GLYPH)
    append(runsToAnnotatedString(runs))
}

/**
 * Pure mapping from styled runs to an [AnnotatedString].
 *
 * Each run's flags collapse into a single [SpanStyle] over that run's range;
 * unstyled runs contribute plain text with no span. An inline AUDIO run
 * ([TextRun.audioTrack] != null) contributes an [appendInlineContent] placeholder
 * (keyed by [audioInlineId]) at its position — the tappable speaker glyph the caller
 * supplies via the matching inlineContent map — so the glyph flows INLINE with the
 * text it follows and wraps naturally. Bidi-isolate control characters embedded in a
 * run's string (U+2068/U+2069) are part of the text and pass through unmodified. This
 * function is Compose-runtime-free and unit-tested.
 */
fun runsToAnnotatedString(runs: List<TextRun>): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        if (run.audioTrack != null) {
            appendInlineContent(audioInlineId(run.audioTrack), AUDIO_GLYPH)
            continue
        }
        val style = run.toSpanStyle()
        if (style == null) {
            append(run.s)
        } else {
            withStyle(style) { append(run.s) }
        }
    }
}

/** Collapses a run's typographic flags into one [SpanStyle], or null if unstyled. */
private fun TextRun.toSpanStyle(): SpanStyle? {
    if (!b && !i && !small && !mono && !strike && !underline) return null
    return SpanStyle(
        fontWeight = if (b) FontWeight.Bold else null,
        fontStyle = if (i) FontStyle.Italic else null,
        fontFamily = if (mono) FontFamily.Monospace else null,
        textDecoration = when {
            strike -> TextDecoration.LineThrough
            underline -> TextDecoration.Underline
            else -> null
        },
        fontSize = if (small) SMALL_TEXT_SCALE.em else TextUnit.Unspecified,
    )
}

/**
 * Renders a cloze deletion's label. A hidden cloze shows its hint (or `...`) as a
 * bold placeholder; a revealed cloze shows the answer text in bold.
 */
private fun clozeToAnnotatedString(node: ClozeNode): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
        if (node.state == CLOZE_STATE_HIDDEN) {
            append("[")
            append(node.hint?.takeIf { it.isNotBlank() } ?: "...")
            append("]")
        } else {
            append(node.text.orEmpty())
        }
    }
}

/**
 * Renders an [AnnotatedString] with the design system's body ("copy") typography
 * and primary content color — the AnnotatedString analogue of [LightText], which
 * only accepts a plain [String].
 */
@Composable
private fun LightAnnotatedCopy(
    annotated: AnnotatedString,
    modifier: Modifier = Modifier,
    align: BlockAlign = BlockAlign.START,
    // Task 1: a bounded font multiplier on the base copy size (1 = no scaling). Both
    // fontSize AND lineHeight scale so the line box tracks the shrunken/grown text.
    scale: Float = 1f,
    // Inline replay: the map of audio-glyph inlineContent placeholders this string
    // references (empty for a plain text block), keyed by [audioInlineId].
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
) {
    Text(
        text = annotated,
        modifier = modifier,
        color = LightThemeTokens.colors.content,
        // Fix C: uniform line boxes so a combining accent can't inflate a line's gap.
        style = cardCopyStyle(LightThemeTokens.typography.copy).scaledBy(scale),
        textAlign = align.toTextAlign(),
        inlineContent = inlineContent,
    )
}

/**
 * The inlineContent map of tappable speaker glyphs for a [TextNode]'s inline audio runs.
 * Each audio run (track N) maps to an [InlineTextContent] whose placeholder is sized to the
 * line's text (so the glyph sits on the same line as the word/sentence it follows) with a
 * ≥44dp tap target via padding that does NOT inflate the line box (the placeholder box stays
 * ~1em; the extra hit-area is negative-offset padding on the clickable). A tap calls
 * [onPlayTrack] with N, which plays THAT track only. Empty when the node carries no audio.
 */
@Composable
private fun audioInlineContent(
    runs: List<TextRun>,
    scale: Float,
    onPlayTrack: (Int) -> Unit,
): Map<String, InlineTextContent> {
    val tracks = runs.mapNotNull { it.audioTrack }
    if (tracks.isEmpty()) return emptyMap()
    // Glyph box slightly wider than the line text size (headroom against clipping);
    // tracks the node scale so it matches shrunken/grown text.
    val glyphEm = 1.3f
    return tracks.associate { track ->
        audioInlineId(track) to InlineTextContent(
            Placeholder(
                width = glyphEm.em,
                height = glyphEm.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
            ),
        ) {
            // The content fills the placeholder box (sized in em, above); the whole box is the
            // tap target. Padding on the outer box would inflate the placeholder, so the ≥44dp
            // reach comes from the surrounding line height plus the box itself — comfortably
            // tappable on the LP3 while keeping the glyph on the text baseline.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onPlayTrack(track) },
                contentAlignment = Alignment.Center,
            ) {
                // Baseline-anchored box + measured downward nudge: re-centers the ▶ INK on
                // the leading text's x-height middle (see AUDIO_GLYPH_INK_OFFSET_EM).
                val glyphStyle = cardCopyStyle(LightThemeTokens.typography.copy).scaledBy(scale)
                val inkNudge = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (glyphStyle.fontSize.toDp()) * AUDIO_GLYPH_INK_OFFSET_EM
                }
                Text(
                    text = AUDIO_GLYPH,
                    color = LightThemeTokens.colors.content,
                    style = glyphStyle,
                    modifier = Modifier.offset(y = inkNudge),
                )
            }
        }
    }
}

/**
 * Task 1 — multiplies a copy style's fontSize and lineHeight by [scale] (a no-op at
 * 1). Pure and Compose-runtime-free so it is unit-tested. Only sp-valued sizes scale;
 * an unspecified size is left untouched.
 */
fun TextStyle.scaledBy(scale: Float): TextStyle {
    if (scale == 1f) return this
    val fs = if (fontSize == TextUnit.Unspecified) fontSize else (fontSize.value * scale).sp
    val lh = if (lineHeight == TextUnit.Unspecified) lineHeight else (lineHeight.value * scale).sp
    return copy(fontSize = fs, lineHeight = lh)
}

/** Maps the wire [BlockAlign] to a Compose [TextAlign]. */
private fun BlockAlign.toTextAlign(): TextAlign = when (this) {
    BlockAlign.START -> TextAlign.Start
    BlockAlign.CENTER -> TextAlign.Center
    BlockAlign.END -> TextAlign.End
}

/**
 * Renders a single [RenderNode]. This and [RenderNodeColumn] are the only entry
 * points consumers use to display bridge-supplied card content.
 *
 * @param mediaLoader supplies image bitmaps; when null, [ImageNode]s render as a
 *   labelled placeholder box (the loader arrives in a later task).
 */
@Composable
fun RenderNodeView(
    node: RenderNode,
    mediaLoader: MediaLoader?,
    modifier: Modifier = Modifier,
    masksHidden: Boolean = false,
    // Whether a block node may claim the full available width. True everywhere
    // except inside a [RowNode]'s WRAP-content (corner) cell, where a
    // `fillMaxWidth` would starve the weighted centre cell of width. In that case
    // the enclosing cell's `horizontalAlignment` already places the content.
    expandWidth: Boolean = true,
    // Inline replay seam: a tap on an inline audio glyph (track N) calls this with N, so
    // the caller plays THAT one track. Default no-op keeps non-study render sites (gallery,
    // previews) inert.
    onPlayTrack: (Int) -> Unit = {},
) {
    when (node) {
        is TextNode -> if (node.indent > 0) {
            ListItemBlock(node, modifier = modifier, expandWidth = expandWidth, onPlayTrack = onPlayTrack)
        } else {
            LightAnnotatedCopy(
                textNodeToAnnotatedString(node),
                // A block of text spans the full width: text-align only bites then, and so does
                // right-to-left direction (a lone Arabic word would otherwise hug the left edge
                // in a box just its own width). Inside a wrap-content row cell it stays tight.
                modifier = if (expandWidth) modifier.fillMaxWidth() else modifier,
                align = node.align,
                scale = node.scale,
                inlineContent = audioInlineContent(node.runs, node.scale, onPlayTrack),
            )
        }

        is ClozeNode -> LightAnnotatedCopy(clozeToAnnotatedString(node), modifier = modifier)

        is RowNode -> RenderRow(node = node, mediaLoader = mediaLoader, modifier = modifier, onPlayTrack = onPlayTrack)

        is DetailsNode -> DetailsBlock(
            node = node,
            mediaLoader = mediaLoader,
            modifier = modifier,
            expandWidth = expandWidth,
            onPlayTrack = onPlayTrack,
        )

        // Image, occlusion and unsupported nodes take [modifier] on a wrapping Box, so a
        // caller's onGloballyPositioned (the reveal scroll anchor) fires for them too.
        is ImageNode -> Box(modifier) {
            if (mediaLoader != null) MediaImage(node = node, loader = mediaLoader)
            else ImageNodePlaceholder(node)
        }

        is RuleNode -> Box(
            modifier = (if (expandWidth) modifier.fillMaxWidth() else modifier)
                .padding(vertical = 0.75f.gridUnitsAsDp())
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary),
        )

        is OcclusionNode -> Box(modifier) {
            OcclusionImage(node = node, mediaLoader = mediaLoader, masksHidden = masksHidden)
        }

        is UnsupportedNode ->
            // The `audio` placeholder is retired: audio is now real playback with a
            // dedicated replay affordance in StudyScreen, so an inline `▢ [audio]` box
            // would be a dead duplicate. Other genuinely-unsupported kinds still show
            // their labelled box so content is never silently dropped.
            if (node.kind != AUDIO_NODE_KIND) {
                LightText(
                    text = "▢ [${node.kind}]",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = modifier,
                )
            }
    }
}

/**
 * True when the text's first letter with a direction reads right-to-left (Arabic,
 * Hebrew…): the paragraph will lay out right-to-left ([cardCopyStyle]'s content
 * direction), so a list marker belongs on its right. Pure and unit-tested.
 */
fun startsRtl(runs: List<TextRun>): Boolean {
    for (run in runs) {
        if (run.audioTrack != null) continue
        for (c in run.s) {
            when (Character.getDirectionality(c)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                else -> {}
            }
        }
    }
    return false
}

/** The side of the column a list level's step-in is measured from, for [indent] (list depth). */
@Composable
private fun listIndent(indent: Int): Dp = emToDp(((indent - 1).coerceAtLeast(0)) * LIST_INDENT_EM)

/**
 * The marker column of a line in a list: the marker set right against the item's text in
 * a column one list step wide, as a browser hangs an outside marker. A line of the item
 * that has no marker (`list-style-type: none`, or text after a block inside the item)
 * leaves the column empty, so every line of an item, and every item, starts its text at
 * the same place. The marker's trailing space becomes padding on its text side, which
 * keeps the gap between marker and text in right-to-left lines too (the column follows
 * the layout direction, whatever the marker's own characters).
 */
@Composable
private fun ListMarkerColumn(marker: TextRun?, scale: Float) {
    val gutter = emToDp(LIST_INDENT_EM)
    if (marker == null) {
        Spacer(Modifier.width(gutter))
        return
    }
    // Exactly one gutter wide, so every item's text starts at the same place; a marker
    // wider than that ("10.", "viii.") spills out on the start side, as a browser's does.
    LightAnnotatedCopy(
        runsToAnnotatedString(listOf(marker.copy(s = marker.s.trimEnd()))),
        modifier = Modifier
            .width(gutter)
            .wrapContentWidth(align = Alignment.End, unbounded = true)
            .padding(end = emToDp(LIST_MARKER_GAP_EM)),
        scale = scale,
    )
}

/**
 * A list item, laid out as a browser lays out `<li>`: the marker in its own column
 * ([ListMarkerColumn]) with the item's text beside it, so a wrapped line continues under
 * the text, not the marker; and the whole item stepped in once per nesting level beyond
 * the first. Right-to-left text puts the marker on the right, where its paragraph starts.
 */
@Composable
private fun ListItemBlock(
    node: TextNode,
    modifier: Modifier,
    expandWidth: Boolean,
    onPlayTrack: (Int) -> Unit,
) {
    val (marker, body) = splitListMarker(node)
    val direction = if (startsRtl(body)) LayoutDirection.Rtl else LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(
            modifier = (if (expandWidth) modifier.fillMaxWidth() else modifier).padding(start = listIndent(node.indent)),
            verticalAlignment = Alignment.Top,
        ) {
            ListMarkerColumn(marker, node.scale)
            LightAnnotatedCopy(
                runsToAnnotatedString(body),
                modifier = if (expandWidth) Modifier.weight(1f) else Modifier,
                align = node.align,
                scale = node.scale,
                inlineContent = audioInlineContent(body, node.scale, onPlayTrack),
            )
        }
    }
}

/**
 * Keys state on a node's identity rather than its value: each card compiles its own
 * node objects, so the state follows the card even when two cards' sections are equal
 * (a fixed "How to answer" block on every front).
 */
private class NodeIdentity(val node: Any) {
    override fun equals(other: Any?): Boolean = other is NodeIdentity && other.node === node
    override fun hashCode(): Int = System.identityHashCode(node)
}

/**
 * Which `<details>` sections the studier has opened or closed on the card in view. It
 * lives above the card body (StudyScreen keys it on the card), so a trip to the MORE menu
 * and back leaves them as they were. Keyed by node object ([NodeIdentity]): the front and
 * the back are compiled separately, so revealing starts the back's sections fresh, as
 * AnkiDroid's new page does.
 */
class DisclosureState {
    private val toggled = mutableStateMapOf<NodeIdentity, Boolean>()

    fun isOpen(node: DetailsNode): Boolean = toggled[NodeIdentity(node)] ?: node.open

    fun toggle(node: DetailsNode) {
        toggled[NodeIdentity(node)] = !isOpen(node)
    }
}

/** The [DisclosureState] of the card in view; null outside study (each section then keeps its own). */
val LocalDisclosureState = compositionLocalOf<DisclosureState?> { null }

/**
 * A [DetailsNode]: its summary line, headed by a chevron, shows or hides the body on
 * tap — closed until tapped unless the element was `open`, as in AnkiWeb and
 * AnkiDroid. A node with no summary line (hidden by the template) can only be an open
 * one, so its body simply shows; an empty summary line is just its chevron. Inside a
 * list item the summary line is laid out like the item's own line, its marker first.
 */
@Composable
private fun DetailsBlock(
    node: DetailsNode,
    mediaLoader: MediaLoader?,
    modifier: Modifier,
    expandWidth: Boolean,
    onPlayTrack: (Int) -> Unit,
) {
    val shared = LocalDisclosureState.current
    var ownOpen by remember(NodeIdentity(node)) { mutableStateOf(node.open) }
    val open = shared?.isOpen(node) ?: ownOpen
    val toggle = { if (shared != null) shared.toggle(node) else ownOpen = !ownOpen }

    val summary = node.summary
    val (marker, summaryText) = splitListMarker(summary.orEmpty())
    // In a list item, the item's marker column (an empty one on later lines of the item).
    val inItem = node.indent > 0 || marker != null
    val width = if (expandWidth) Modifier.fillMaxWidth() else Modifier
    // A right-to-left summary lays the whole section out from the right — its list step,
    // marker column and body offset — while the body's own nodes keep the card's direction.
    val ambient = LocalLayoutDirection.current
    val direction = if (startsRtl(summaryText)) LayoutDirection.Rtl else ambient
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Column(modifier.then(width).padding(start = listIndent(node.indent))) {
            if (summary != null) {
                Row(
                    modifier = width
                        .clickable(onClick = toggle)
                        .padding(vertical = 0.25f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (inItem) ListMarkerColumn(marker, node.scale)
                    LightAnnotatedCopy(
                        detailsSummaryText(open, summaryText),
                        modifier = if (expandWidth) Modifier.weight(1f) else Modifier,
                        align = node.align,
                        scale = node.scale,
                        inlineContent = audioInlineContent(summaryText, node.scale, onPlayTrack),
                    )
                }
            }
            if (open) {
                // The body lines up under the summary's text, past the marker column and the
                // chevron; with no summary line it simply takes the section's place.
                val bodyStart = (if (inItem) LIST_INDENT_EM else 0f) + (if (summary != null) LIST_INDENT_EM else 0f)
                Column(width.padding(start = emToDp(bodyStart))) {
                    CompositionLocalProvider(LocalLayoutDirection provides ambient) {
                        RenderNodeColumn(
                            nodes = node.body,
                            mediaLoader = mediaLoader,
                            expandWidth = expandWidth,
                            onPlayTrack = onPlayTrack,
                        )
                    }
                }
            }
        }
    }
}

/** [em] multiples of the card base text size (the copy style's font size), as dp. */
@Composable
private fun emToDp(em: Float): Dp {
    val baseSp = LightThemeTokens.typography.copy.fontSize
    return with(LocalDensity.current) { (em * baseSp.value).sp.toDp() }
}

/**
 * A block node's own (top, bottom) vertical margins in em, or (0,0) for nodes that
 * carry none. Fix D: only [TextNode]/[RowNode]/[RuleNode] carry margins.
 */
private fun RenderNode.marginsEm(): Pair<Float, Float> = when (this) {
    is TextNode -> marginTop to marginBottom
    is RowNode -> marginTop to marginBottom
    is RuleNode -> marginTop to marginBottom
    else -> 0f to 0f
}

/**
 * The vertical gap (em) to insert BEFORE each stacked node. The first node keeps
 * its own top margin; between two nodes the previous bottom and next top COLLAPSE
 * to their max (CSS margin collapse), not their sum. Pure and unit-tested.
 */
fun collapsedTopGapsEm(nodes: List<RenderNode>): List<Float> = nodes.mapIndexed { i, node ->
    val top = node.marginsEm().first
    if (i == 0) top else maxOf(nodes[i - 1].marginsEm().second, top)
}

/**
 * Renders a list of nodes stacked vertically, one below the next.
 *
 * [dividerIndex], when in range, marks the node that begins the answer — the boundary
 * between question and answer. On the answer side StudyScreen passes the count of front
 * nodes: since a standard back is `{{FrontSide}}` + (optional `<hr>`) + `{{Back}}`, the node
 * at that index is the first content unique to the answer (the `<hr>` rule if the template
 * has one, else the first answer node). [onNodePositioned] fires with that node's layout
 * coordinates once placed, so StudyScreen can auto-scroll a tall card's answer into view on
 * reveal. This anchors on the front/back boundary directly rather than hunting for a rule,
 * so it works for templates (like image mnemonics) that omit the `<hr>` divider. A
 * [dividerIndex] outside `nodes.indices` (e.g. −1 on the front side) simply never fires.
 */
@Composable
fun RenderNodeColumn(
    nodes: List<RenderNode>,
    mediaLoader: MediaLoader?,
    dividerIndex: Int = -1,
    onNodePositioned: ((androidx.compose.ui.layout.LayoutCoordinates) -> Unit)? = null,
    masksHidden: Boolean = false,
    // Inline replay seam threaded to each node's audio glyphs (see [RenderNodeView]).
    onPlayTrack: (Int) -> Unit = {},
    // Whether each node may claim the full width (see [RenderNodeView]); false for a
    // column nested in a wrap-content row cell, like an open section's body there.
    expandWidth: Boolean = true,
) {
    // Fix D: the collapsed vertical gap (em) to insert before each block, converted
    // to dp against the card base text size (the copy style's font size) so it scales
    // with the deck's em rhythm.
    val gapsEm = collapsedTopGapsEm(nodes)
    nodes.forEachIndexed { index, node ->
        MarginGap(gapsEm[index])
        val dividerModifier =
            if (onNodePositioned != null && index == dividerIndex) {
                Modifier.onGloballyPositioned(onNodePositioned)
            } else {
                Modifier
            }
        RenderNodeView(
            node = node,
            mediaLoader = mediaLoader,
            modifier = dividerModifier,
            masksHidden = masksHidden,
            expandWidth = expandWidth,
            onPlayTrack = onPlayTrack,
        )
    }
}

/**
 * The collapsed em gap [gapEm] rendered as a vertical [androidx.compose.foundation.layout.Spacer],
 * converted to dp against the card base text size (the copy style's font size) so it
 * scales with the deck's em rhythm. A non-positive gap renders nothing.
 */
@Composable
private fun MarginGap(gapEm: Float) {
    if (gapEm <= 0f) return
    androidx.compose.foundation.layout.Spacer(Modifier.height(emToDp(gapEm)))
}

/**
 * Renders a [RowNode] as a horizontal [Row] of cells, all TOP-aligned vertically
 * (so a short corner label flanks the FIRST line of a tall centre column). A cell
 * with a `null` weight wraps its content (the flanking first/last corners); a
 * weighted cell takes its share of the leftover width (the centre column). Each
 * cell stacks its own nodes vertically, horizontally placed per [RowCell.align].
 */
@Composable
private fun RenderRow(
    node: RowNode,
    mediaLoader: MediaLoader?,
    modifier: Modifier = Modifier,
    onPlayTrack: (Int) -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        for (cell in node.cells) {
            RenderRowCell(cell = cell, mediaLoader = mediaLoader, onPlayTrack = onPlayTrack)
        }
    }
}

@Composable
private fun RowScope.RenderRowCell(
    cell: RowCell,
    mediaLoader: MediaLoader?,
    onPlayTrack: (Int) -> Unit = {},
) {
    // A weighted (centre) cell is width-bounded, so its blocks may fill it; a
    // wrap-content (corner) cell must NOT let a block fill max width or it would
    // starve the weighted sibling. The Column's horizontalAlignment still places
    // corner content per the cell's role.
    val weight = cell.weight
    val weighted = weight != null
    val cellModifier = if (weight != null) Modifier.weight(weight) else Modifier
    // Task 2: stack the cell's blocks with the SAME collapsed em gaps the top-level
    // column uses ([collapsedTopGapsEm]). Without this the cell dropped every in-cell
    // margin — so the FIRST divider (inside this centre cell) got no .7em gap while the
    // SECOND, top-level divider did, making the gap below "he" ~2× the gap above it.
    // Honoring the gaps here spaces both dividers identically (symmetric around "he").
    val gapsEm = collapsedTopGapsEm(cell.nodes)
    Column(
        modifier = cellModifier,
        horizontalAlignment = when (cell.align) {
            BlockAlign.START -> Alignment.Start
            BlockAlign.CENTER -> Alignment.CenterHorizontally
            BlockAlign.END -> Alignment.End
        },
    ) {
        cell.nodes.forEachIndexed { index, child ->
            // Skip the cell's LEADING gap (index 0): a cell's own top margin governs its
            // placement WITHIN the top-aligned row, not intra-cell rhythm, and honoring
            // it here would shove the whole header column down. Only the BETWEEN-node
            // collapsed gaps (index >= 1) are inserted — that's what spaces the in-cell
            // divider the same as the top-level one.
            if (index > 0) MarginGap(gapsEm[index])
            RenderNodeView(node = child, mediaLoader = mediaLoader, expandWidth = weighted, onPlayTrack = onPlayTrack)
        }
    }
}

/**
 * Placeholder shown for an [ImageNode] when no loader is supplied, while its image
 * is loading, or when loading/decoding failed. Shows the media `src` label so the
 * content is never silently dropped.
 */
@Composable
internal fun ImageNodePlaceholder(node: ImageNode) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp())
            .height(4f.gridUnitsAsDp())
            .background(LightThemeTokens.colors.contentSecondary),
    ) {
        LightText(
            text = node.src,
            variant = LightTextVariant.Fine,
            color = LightThemeTokens.colors.background,
            modifier = Modifier.padding(0.5f.gridUnitsAsDp()),
        )
    }
}
