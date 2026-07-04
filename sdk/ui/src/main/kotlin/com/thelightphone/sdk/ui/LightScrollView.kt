package com.thelightphone.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SCROLLBAR_WIDTH_UNITS = 2f
private const val SCROLLBAR_INSIDE_VERTICAL_PADDING_UNITS = 1f
private const val MIN_HANDLE_FRACTION = 0.1f
private const val MAX_HANDLE_FRACTION = 0.85f

enum class LightScrollBarPosition {
    Outside,

    Inside,
}

/**
 * The end-gutter width (grid units) reserved for the scrollbar under a given
 * [LightScrollBarPosition]. Deliberately depends ONLY on the position, never on whether
 * the bar is currently shown: reserving the gutter conditionally would let the content's
 * available width change when the bar toggles, which for aspect-ratio content can flip
 * viewport overflow and drive an infinite show/hide layout loop. Pure, so this invariant
 * is unit-testable without a Compose runtime.
 */
fun scrollBarGutterUnits(position: LightScrollBarPosition): Float = when (position) {
    LightScrollBarPosition.Outside -> SCROLLBAR_WIDTH_UNITS
    LightScrollBarPosition.Inside -> 0f
}

/**
 * The width (grid units) available to [LightScrollView] content given the view's [totalWidthUnits]
 * and scrollbar [position]. It is `total − gutter` and — critically — takes NO bar-visibility
 * parameter: whether the scrollbar is currently shown must not change the content width.
 *
 * This is the invariant that kills the occlusion flicker. The scrollbar is drawn as a CenterEnd
 * overlay (consuming no layout space) rather than a Row sibling (which consumed the bar's width
 * only while visible). Were content width to depend on bar visibility, `fillMaxWidth().aspectRatio()`
 * content would change height with the bar, flipping viewport overflow and oscillating the bar
 * forever. Pure, so the invariant is unit-testable without a Compose runtime.
 */
fun scrollViewContentWidthUnits(totalWidthUnits: Float, position: LightScrollBarPosition): Float =
    totalWidthUnits - scrollBarGutterUnits(position)

@Composable
fun LightScrollView(
    modifier: Modifier = Modifier,
    scrollBarPosition: LightScrollBarPosition = LightScrollBarPosition.Outside,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val showScrollBar = scrollState.maxValue > 0
    // Reserve the scrollbar gutter UNCONDITIONALLY (see [scrollBarGutterUnits]) AND draw
    // the bar as a CenterEnd OVERLAY that consumes no layout space — for BOTH positions.
    //
    // Why an overlay and not a Row sibling: a sibling bar occupies horizontal space in the
    // Row only while it is visible, so the weighted content Column's width toggled with bar
    // visibility (measured: 1000px bar-hidden ⇄ 920px bar-shown). For fillMaxWidth()
    // .aspectRatio() content (the occlusion image) a narrower box means a proportionally
    // shorter image, which flips whether total content overflows the viewport, which toggles
    // the bar again — an infinite show/hide layout loop that flickered the screen and pinned
    // the CPU. Commit 4e78a2a made the gutter PADDING unconditional but left the bar as a Row
    // sibling, so the width still swung by the bar's own width; overlaying the bar is what
    // finally makes the content width invariant to bar visibility. The Outside gutter padding
    // keeps the bar clear of content; Inside reserves no gutter so the bar overlays content.
    val contentPaddingEnd = scrollBarGutterUnits(scrollBarPosition)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = contentPaddingEnd.gridUnitsAsDp())
                .verticalScroll(scrollState),
            content = content,
        )
        if (showScrollBar) {
            val verticalPadding = if (scrollBarPosition == LightScrollBarPosition.Inside) {
                SCROLLBAR_INSIDE_VERTICAL_PADDING_UNITS.gridUnitsAsDp()
            } else {
                0.dp
            }
            LightScrollBar(
                scrollValue = scrollState.value.toFloat(),
                maxScrollValue = scrollState.maxValue.toFloat(),
                onScrollTo = { target ->
                    scope.launch { scrollState.scrollTo(target.roundToInt()) }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = verticalPadding),
            )
        }
    }
}

@Composable
fun LightLazyScrollView(
    modifier: Modifier = Modifier,
    scrollBarPosition: LightScrollBarPosition = LightScrollBarPosition.Outside,
    listState: LazyListState = rememberLazyListState(),
    uniformItemHeightGridUnits: Float,
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { uniformItemHeightGridUnits.gridUnitsAsDp().toPx() }

    val scrollMetrics by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val itemCount = layoutInfo.totalItemsCount
            val viewportHeightPx = layoutInfo.viewportSize.height.toFloat()
            val totalContentPx = itemCount * itemHeightPx
            val maxScrollPx = (totalContentPx - viewportHeightPx).coerceAtLeast(0f)
            val scrollPx = (
                listState.firstVisibleItemIndex * itemHeightPx +
                    listState.firstVisibleItemScrollOffset
                ).coerceAtMost(maxScrollPx)
            scrollPx to maxScrollPx
        }
    }
    val scrollPx = scrollMetrics.first
    val maxScrollPx = scrollMetrics.second
    val showScrollBar = maxScrollPx > 0f
    val contentPaddingEnd = when {
        !showScrollBar -> 0f
        scrollBarPosition == LightScrollBarPosition.Outside -> SCROLLBAR_WIDTH_UNITS
        else -> 0f
    }

    fun scrollToOffsetPx(targetPx: Float) {
        if (itemHeightPx <= 0f) return
        val itemCount = listState.layoutInfo.totalItemsCount
        if (itemCount == 0) return
        val clamped = targetPx.coerceIn(0f, maxScrollPx)
        val index = (clamped / itemHeightPx).toInt().coerceIn(0, itemCount - 1)
        val offset = (clamped - index * itemHeightPx).roundToInt()
        scope.launch { listState.scrollToItem(index, offset) }
    }

    if (scrollBarPosition == LightScrollBarPosition.Inside) {
        Box(modifier = modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
            if (showScrollBar) {
                LightScrollBar(
                    scrollValue = scrollPx,
                    maxScrollValue = maxScrollPx,
                    onScrollTo = ::scrollToOffsetPx,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(
                            vertical = SCROLLBAR_INSIDE_VERTICAL_PADDING_UNITS.gridUnitsAsDp(),
                        ),
                )
            }
        }
    } else {
        Row(modifier = modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = contentPaddingEnd.gridUnitsAsDp()),
                content = content,
            )
            if (showScrollBar) {
                LightScrollBar(
                    scrollValue = scrollPx,
                    maxScrollValue = maxScrollPx,
                    onScrollTo = ::scrollToOffsetPx,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun LightScrollBar(
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
                    detectTapGestures { offset ->
                        scrollToTrackOffset(offset.y)
                    }
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
                            onDragStart = {
                                dragStartHandleOffsetPx = handleOffsetPx
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (availableScrollRoomPx <= 0f || maxScrollValue <= 0f) {
                                    return@detectVerticalDragGestures
                                }
                                val totalScrollable = contentHeightPx - viewportHeightPx
                                val newHandleTop = (dragStartHandleOffsetPx + dragAmount)
                                    .coerceIn(0f, availableScrollRoomPx)
                                val newScroll = (newHandleTop / availableScrollRoomPx) * totalScrollable
                                onScrollTo(newScroll)
                            },
                        )
                    },
            )
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightScrollViewDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightScrollView(modifier = Modifier.fillMaxSize()) {
            repeat(24) { index ->
                LightText(
                    text = "Scrollable row ${index + 1}",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}
