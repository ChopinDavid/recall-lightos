package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
 * The [UnsupportedNode.kind] the engine tags a side's audio marker with. Its inline
 * `▢ [audio]` placeholder is retired: audio is real playback with a replay affordance
 * in StudyScreen, so this kind renders nothing.
 */
private const val AUDIO_NODE_KIND = "audio"

/** Wire value of [ClozeNode.state] for a cloze that is still hidden (question side). */
private const val CLOZE_STATE_HIDDEN = "hidden"

/**
 * Pure mapping from a [TextNode]'s runs to a styled [AnnotatedString].
 *
 * Each run's flags collapse into a single [SpanStyle] over that run's range;
 * unstyled runs contribute plain text with no span. Bidi-isolate control
 * characters embedded in a run's string (U+2068/U+2069) are part of the text and
 * pass through unmodified. This function is Compose-runtime-free and unit-tested.
 */
fun textNodeToAnnotatedString(node: TextNode): AnnotatedString = buildAnnotatedString {
    for (run in node.runs) {
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
) {
    Text(
        text = annotated,
        modifier = modifier,
        color = LightThemeTokens.colors.content,
        style = LightThemeTokens.typography.copy,
        textAlign = align.toTextAlign(),
    )
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
        )

        is ClozeNode -> LightAnnotatedCopy(clozeToAnnotatedString(node), modifier = modifier)

        is RowNode -> RenderRow(node = node, mediaLoader = mediaLoader, modifier = modifier)

        is ImageNode ->
            if (mediaLoader != null) MediaImage(node = node, loader = mediaLoader)
            else ImageNodePlaceholder(node)

        RuleNode -> Box(
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
) {
    nodes.forEachIndexed { index, node ->
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
        )
    }
}

/**
 * Renders a [RowNode] as a horizontal [Row] of cells, all TOP-aligned vertically
 * (so a short corner label flanks the FIRST line of a tall centre column). A cell
 * with a `null` weight wraps its content (the flanking first/last corners); a
 * weighted cell takes its share of the leftover width (the centre column). Each
 * cell stacks its own nodes vertically, horizontally placed per [RowCell.align].
 */
@Composable
private fun RenderRow(node: RowNode, mediaLoader: MediaLoader?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        for (cell in node.cells) {
            RenderRowCell(cell = cell, mediaLoader = mediaLoader)
        }
    }
}

@Composable
private fun RowScope.RenderRowCell(cell: RowCell, mediaLoader: MediaLoader?) {
    // A weighted (centre) cell is width-bounded, so its blocks may fill it; a
    // wrap-content (corner) cell must NOT let a block fill max width or it would
    // starve the weighted sibling. The Column's horizontalAlignment still places
    // corner content per the cell's role.
    val weighted = cell.weight != null
    val cellModifier = if (weighted) Modifier.weight(cell.weight!!) else Modifier
    Column(
        modifier = cellModifier,
        horizontalAlignment = when (cell.align) {
            BlockAlign.START -> Alignment.Start
            BlockAlign.CENTER -> Alignment.CenterHorizontally
            BlockAlign.END -> Alignment.End
        },
    ) {
        for (child in cell.nodes) {
            RenderNodeView(node = child, mediaLoader = mediaLoader, expandWidth = weighted)
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
