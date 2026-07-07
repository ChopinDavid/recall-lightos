package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dvdutch.recall.api.BlockAlign
import com.dvdutch.recall.api.ClozeNode
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
)

/**
 * The [UnsupportedNode.kind] the engine tags a side's audio marker with. Its inline
 * `▢ [audio]` placeholder is retired: audio is real playback with a replay affordance
 * in StudyScreen, so this kind renders nothing.
 */
private const val AUDIO_NODE_KIND = "audio"

/** Wire value of [ClozeNode.state] for a cloze that is still hidden (question side). */
private const val CLOZE_STATE_HIDDEN = "hidden"

/** The inlineContent id for the audio-replay glyph at track [track] within a text node. */
internal fun audioInlineId(track: Int): String = "audio:$track"

/**
 * Pure mapping from a [TextNode]'s runs to a styled [AnnotatedString].
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
fun textNodeToAnnotatedString(node: TextNode): AnnotatedString = buildAnnotatedString {
    for (run in node.runs) {
        if (run.audioTrack != null) {
            appendInlineContent(audioInlineId(run.audioTrack), "🔊")
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
    node: TextNode,
    scale: Float,
    onPlayTrack: (Int) -> Unit,
): Map<String, InlineTextContent> {
    val tracks = node.runs.mapNotNull { it.audioTrack }
    if (tracks.isEmpty()) return emptyMap()
    // Glyph box ~ the line text size; tracks the node scale so it matches shrunken/grown text.
    val glyphEm = 1.1f
    return tracks.associate { track ->
        audioInlineId(track) to InlineTextContent(
            Placeholder(
                width = glyphEm.em,
                height = glyphEm.em,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
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
                Text(
                    text = "🔊",
                    color = LightThemeTokens.colors.content,
                    style = cardCopyStyle(LightThemeTokens.typography.copy).scaledBy(scale),
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
        is TextNode -> LightAnnotatedCopy(
            textNodeToAnnotatedString(node),
            // A non-start text-align only bites when the Text spans the full width.
            modifier = if (node.align == BlockAlign.START || !expandWidth) {
                modifier
            } else {
                modifier.fillMaxWidth()
            },
            align = node.align,
            scale = node.scale,
            inlineContent = audioInlineContent(node, node.scale, onPlayTrack),
        )

        is ClozeNode -> LightAnnotatedCopy(clozeToAnnotatedString(node), modifier = modifier)

        is RowNode -> RenderRow(node = node, mediaLoader = mediaLoader, modifier = modifier, onPlayTrack = onPlayTrack)

        is ImageNode ->
            if (mediaLoader != null) MediaImage(node = node, loader = mediaLoader)
            else ImageNodePlaceholder(node)

        is RuleNode -> Box(
            modifier = (if (expandWidth) modifier.fillMaxWidth() else modifier)
                .padding(vertical = 0.75f.gridUnitsAsDp())
                .height(1.dp)
                .background(LightThemeTokens.colors.contentSecondary),
        )

        is OcclusionNode ->
            OcclusionImage(node = node, mediaLoader = mediaLoader, masksHidden = masksHidden)

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
                )
            }
    }
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
    val baseSp = LightThemeTokens.typography.copy.fontSize
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapDp = with(density) { (gapEm * baseSp.value).sp.toDp() }
    androidx.compose.foundation.layout.Spacer(Modifier.height(gapDp))
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
