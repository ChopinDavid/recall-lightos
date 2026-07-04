package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.scrollBarGutterUnits
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Grid-unit metrics mirrored from the SDK's LightScrollView so our card scroller looks
// identical to every other scroll surface in the app. The SDK keeps its LightScrollBar
// private and its LightScrollView owns its scrollState internally with no scroll-to API
// (Light case-file item #4: LightScrollView exposes no scroll-position control), so to
// auto-scroll to the answer on reveal we must own the scrollState ourselves — which means
// carrying the SDK's oscillation fixes here rather than reusing LightScrollView.
private const val SCROLLBAR_WIDTH_UNITS = 2f
private const val MIN_HANDLE_FRACTION = 0.1f
private const val MAX_HANDLE_FRACTION = 0.85f

/**
 * The scroll offset that brings a tall card's answer into view when REVEAL is tapped, or
 * null when no scroll is needed. Pure so it is unit-testable without a Compose runtime.
 *
 * On a card whose content overflows the viewport, tapping REVEAL appends the answer below
 * the already-tall front, so the answer's start — the divider between question and answer —
 * lands off-screen. AnkiDroid keeps that boundary visible; we replicate it by scrolling the
 * divider to a small margin below the viewport top so the answer has room to read.
 *
 * @param dividerY the divider's y-position in content-space (px from the top of the
 *   scrollable content).
 * @param viewportH the visible viewport height in px.
 * @param currentScroll the current vertical scroll offset in px.
 * @param maxScroll the maximum scroll offset in px (content height − viewport height).
 * @param topMargin px to leave above the divider after scrolling, so the divider sits just
 *   below the viewport top rather than flush against it.
 * @return null when the divider is already visible at [currentScroll] (do NOT scroll — no
 *   motion for the common, fits-on-screen case); otherwise the target scroll offset placing
 *   the divider [topMargin] below the viewport top, clamped to `[0, maxScroll]`.
 */
fun revealScrollTarget(
    dividerY: Int,
    viewportH: Int,
    currentScroll: Int,
    maxScroll: Int,
    topMargin: Int,
): Int? {
    // Visible range is [currentScroll, currentScroll + viewportH). If the divider already
    // falls inside it, there is nothing to do — leave the view untouched.
    val visibleTop = currentScroll
    val visibleBottom = currentScroll + viewportH
    if (dividerY in visibleTop until visibleBottom) return null

    // Otherwise scroll so the divider sits topMargin below the viewport top, clamped so we
    // never scroll above 0 or past the content's end.
    return (dividerY - topMargin).coerceIn(0, maxScroll.coerceAtLeast(0))
}

/**
 * A drop-in replacement for the SDK's `LightScrollView` (Outside scrollbar) whose vertical
 * scroll offset we own via [scrollState], so a caller can drive scroll position — which the
 * SDK's LightScrollView does not expose (it `rememberScrollState()`s internally).
 *
 * The visuals and the three oscillation fixes are carried verbatim from LightScrollView so
 * the subtle occlusion-reveal flicker cannot reappear here:
 *   1. the scrollbar gutter is reserved UNCONDITIONALLY (via the SDK's pure
 *      [scrollBarGutterUnits]) so content width never changes with bar visibility;
 *   2. the bar is a zero-layout CenterEnd overlay, not a Row sibling, so it consumes no
 *      horizontal space when shown;
 *   3. the bar's SHOW is debounced by one frame (HIDE stays immediate) so a content swap's
 *      transient one-frame overflow does not flash the bar.
 */
@Composable
fun CardScrollView(
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()

    // (3) Debounce the scrollbar SHOW by one frame; HIDE stays immediate. See LightScrollView.
    val overflowing = scrollState.maxValue > 0
    val showScrollBar by produceState(initialValue = false, overflowing) {
        if (!overflowing) {
            value = false
        } else {
            withFrameNanos { }
            if (scrollState.maxValue > 0) value = true
        }
    }
    // (1) Reserve the gutter unconditionally, for the Outside position StudyScreen uses.
    val contentPaddingEnd = scrollBarGutterUnits(LightScrollBarPosition.Outside)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = contentPaddingEnd.gridUnitsAsDp())
                .verticalScroll(scrollState),
            content = content,
        )
        // (2) Bar as a zero-layout CenterEnd overlay.
        if (showScrollBar) {
            CardScrollBar(
                scrollValue = scrollState.value.toFloat(),
                maxScrollValue = scrollState.maxValue.toFloat(),
                onScrollTo = { target -> scope.launch { scrollState.scrollTo(target.roundToInt()) } },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * Visual + interaction twin of the SDK's private `LightScrollBar` (Outside position): a thin
 * rail with a draggable/tappable handle, monochrome in the content color. Replicated because
 * the SDK does not export it; kept pixel-identical so the card scroller matches every other
 * scroll surface.
 */
@Composable
private fun CardScrollBar(
    scrollValue: Float,
    maxScrollValue: Float,
    onScrollTo: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barColor = LightThemeTokens.colors.content
    val density = LocalDensity.current
    val trackWidth = SCROLLBAR_WIDTH_UNITS.gridUnitsAsDp()
    val railWidth = 1.dp
    val handleWidth = 5.dp

    BoxWithConstraints(
        modifier = modifier.width(trackWidth),
        contentAlignment = Alignment.TopCenter,
    ) {
        val trackHeightPx = with(density) { maxHeight.toPx() }
        if (trackHeightPx <= 0f) return@BoxWithConstraints

        val viewportHeightPx = trackHeightPx
        val contentHeightPx = viewportHeightPx + maxScrollValue
        val handleHeightFraction = (viewportHeightPx / contentHeightPx)
            .coerceIn(MIN_HANDLE_FRACTION, MAX_HANDLE_FRACTION)
        val handleHeightPx = trackHeightPx * handleHeightFraction
        val availableScrollRoomPx = trackHeightPx - handleHeightPx
        val scrollFraction = if (maxScrollValue > 0f) {
            (scrollValue / maxScrollValue).coerceIn(0f, 1f)
        } else {
            0f
        }
        val handleOffsetPx = scrollFraction * availableScrollRoomPx
        val handleOffsetDp = with(density) { handleOffsetPx.toDp() }
        val handleHeightDp = with(density) { handleHeightPx.toDp() }

        fun scrollToTrackOffset(yPx: Float) {
            val totalScrollable = contentHeightPx - viewportHeightPx
            if (totalScrollable <= 0f) return
            val fraction = (yPx / trackHeightPx).coerceIn(0f, 1f)
            onScrollTo(fraction * totalScrollable)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(maxScrollValue, scrollValue) {
                    detectTapGestures { offset -> scrollToTrackOffset(offset.y) }
                },
        ) {
            Box(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .background(barColor),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = handleOffsetDp)
                    .width(handleWidth)
                    .height(handleHeightDp)
                    .background(barColor)
                    .pointerInput(maxScrollValue, scrollValue) {
                        var dragStartHandleOffsetPx = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragStartHandleOffsetPx = handleOffsetPx },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (availableScrollRoomPx <= 0f || maxScrollValue <= 0f) {
                                    return@detectVerticalDragGestures
                                }
                                val totalScrollable = contentHeightPx - viewportHeightPx
                                val newHandleTop = (dragStartHandleOffsetPx + dragAmount)
                                    .coerceIn(0f, availableScrollRoomPx)
                                val newScroll =
                                    (newHandleTop / availableScrollRoomPx) * totalScrollable
                                onScrollTo(newScroll)
                            },
                        )
                    },
            )
        }
    }
}
