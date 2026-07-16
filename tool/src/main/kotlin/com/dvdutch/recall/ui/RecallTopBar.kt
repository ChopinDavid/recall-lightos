package com.dvdutch.recall.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * Recall's OWN top bar for the study screen — a deliberate, sanctioned replacement for the
 * SDK's [com.thelightphone.sdk.ui.LightTopBar]. Light closed our multi-action-topbar request
 * (issue #75) confirming the SDK bar's single-button-per-side slot is BY DESIGN, that multiple
 * actions traditionally belong in the bottom bar, and — explicitly — that we are "free to spin
 * [our] own TopBar." Study genuinely needs two right-slot actions (undo + MORE) at the top of
 * the screen, so this bar rebuilds LightTopBar's exact geometry (Box: full width, height 3 grid
 * units, 1-unit horizontal padding; a Row at zIndex 2 with left/right slots and a weighted
 * spacer; a centered title Box) using the same public SDK pieces — [LightIcons.BACK], [LightText]
 * at [LightTextVariant.Fine] — but with a real two-action right slot instead of a single
 * [com.thelightphone.sdk.ui.LightBarButton]. It is pixel-faithful to the SDK bar: same back-icon
 * position, same centered title, so swapping it in is visually indistinguishable except for the
 * added second right action.
 */
@Composable
fun RecallTopBar(
    title: String,
    onBack: () -> Unit,
    rightSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Mirrors LightTopBar's constants (its internals are private, but these are the SDK's own
    // published bar metrics): 3-unit height, 1-unit horizontal padding, title in the Fine variant,
    // title width capped at 18 units so a long title ellipsizes rather than colliding with a slot.
    val barHeight = 3f.gridUnitsAsDp()
    val horizontalPadding = 1f.gridUnitsAsDp()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .padding(horizontal = horizontalPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .zIndex(2f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left slot: the BACK icon, rendered exactly as LightBarButtonView renders a
            // LightBarButton.LightIcon — the SDK LightIcon composable at its 2-unit default size,
            // made tappable. Same glyph, same size, same position as the SDK bar's back button.
            Box(
                modifier = Modifier.height(barHeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                LightIcon(
                    icon = LightIcons.BACK,
                    contentDescription = "Back",
                    // lightClickable (no press indication) is exactly what LightBarButtonView
                    // uses for the SDK bar's icon buttons — a plain Modifier.clickable would add
                    // a ripple the SDK bar doesn't have.
                    modifier = Modifier.lightClickable(onClick = onBack),
                )
            }

            Box(modifier = Modifier.weight(1f))

            // Right slot: the caller's two-action content (undo + MORE), center-aligned to the
            // bar height just like the SDK's single right-button slot.
            Box(
                modifier = Modifier.height(barHeight),
                contentAlignment = Alignment.CenterEnd,
            ) {
                rightSlot()
            }
        }

        // Centered title, identical to LightTopBarCenter.Text: Fine variant, centered, single
        // line, ellipsized, width-capped — sits in its own full-width centered Box atop the Row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = title,
                variant = LightTextVariant.Fine,
                align = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 18f.gridUnitsAsDp()),
            )
        }
    }
}

/**
 * The study screen's two right-slot actions, moved verbatim from the old LightTopBar+overlay:
 * the ↶ undo (ghosted when nothing is undoable, full-white and tappable after a grade) and the
 * MORE card-actions trigger (only over a live card). The undo glyph keeps its measured −0.35
 * grid-unit ink offset so its visual center lands level with the title and MORE (arrow glyphs
 * hang low in their line box). This lives here, next to the bar, rather than inside StudyScreen,
 * so the bar owns its own right-slot rendering.
 */
@Composable
fun StudyTopBarActions(
    undoable: Boolean,
    onUndo: () -> Unit,
    showMore: Boolean,
    onMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(3f.gridUnitsAsDp())
            .padding(end = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // AnkiDroid-style undo affordance: ALWAYS visible during study, ghosted when there is
        // nothing to undo and full white (tappable) after a grade — so the control teaches that
        // undo exists before it's needed. Fires rslib's own undo; the card returns and the counts
        // tick back.
        // The ↶ glyph's ink hangs low in its line box (arrow glyphs sit near the baseline), so its
        // visual center lands ~0.35 grid units below the bar's other elements; the offset
        // re-centers the INK against the title / "MORE" (measured on-device).
        LightText(
            text = "↶",
            variant = LightTextVariant.Copy,
            lighten = !undoable,
            modifier = (
                if (undoable) Modifier.clickable(onClick = onUndo) else Modifier
            )
                .offset(y = (-0.35f).gridUnitsAsDp())
                .padding(horizontal = 0.5f.gridUnitsAsDp()),
        )
        // "MORE" opens the card-actions menu, only over a live card.
        if (showMore) {
            LightText(
                text = "MORE",
                variant = LightTextVariant.Fine,
                modifier = Modifier
                    .clickable(onClick = onMore)
                    .padding(start = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}
