package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * The ghosted alpha for the SYNC bottom-bar action while a manual sync is in flight — the same
 * lightened, "not-actionable" treatment the study screen's idle undo glyph uses (there via
 * LightText's `lighten`; the bar is dimmed with alpha, the composable-agnostic twin).
 */
private const val GHOSTED_ALPHA = 0.35f

/**
 * Recall's initial screen: the deck list. With no bridge token configured it
 * auto-navigates to [SettingsScreen]; otherwise it fetches `/v1/decks`, flattens
 * the `::`-separated names into an indented tree, and shows each deck's due
 * counts. Tapping a deck opens a [StudyScreen]; the gear row opens Settings.
 * Errors surface the [SettingsMessages] copy with a retry row.
 */
@InitialScreen
class RecallHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, RecallHomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<RecallHomeViewModel>
        get() = RecallHomeViewModel::class.java

    override fun createViewModel(): RecallHomeViewModel =
        RecallHomeViewModel(lightContext.filesDir, lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        // The very first show fires onScreenShow -> load(); this covers the
        // in-Compose initial paint too (idempotent with onScreenShow).
        LaunchedEffect(Unit) { viewModel.load() }

        // Route off the engine's verdict: no collection yet → first-run download;
        // a FULL_* divergence → the needs-attention resolution screen.
        LaunchedEffect(state.mode) {
            when (state.mode) {
                is HomeMode.NeedsFirstRun -> navigateTo(::FirstRunScreen)
                is HomeMode.NeedsAttention -> navigateTo(::AttentionScreen)
                else -> Unit
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // The gear is the bar's sole right button — the SDK's single-button-per-side
                // slot is BY DESIGN (Light issue #75). Manual sync no longer overlays a 🔄 icon
                // here; it moved to a dedicated bottom bar (see below), the traditional home for
                // extra actions per Light's own guidance.
                LightTopBar(
                    center = LightTopBarCenter.Text("Recall"),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = { navigateTo(::SettingsScreen) },
                        contentDescription = "Settings",
                    ),
                )

                // The natural 1-unit gap under the top bar before the body.
                Spacer(modifier = Modifier.height(1f.gridUnitsAsDp()))

                // A single lightened line above the deck list when the last manual sync failed;
                // cleared on the next clean sync or on navigation (see RecallHomeViewModel).
                (state.syncState as? SyncState.Failed)?.let { failed ->
                    LightText(
                        text = failed.message,
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
                    )
                }

                // The body takes the remaining height so the SYNC bar can pin to the screen
                // bottom with the deck list scrolling above it.
                Box(modifier = Modifier.weight(1f)) {
                    when (val mode = state.mode) {
                        is HomeMode.Loading,
                        is HomeMode.NeedsFirstRun,
                        is HomeMode.NeedsAttention -> CenteredMessage("…")

                        is HomeMode.Error -> ErrorBody(
                            message = mode.message,
                            onRetry = viewModel::load,
                        )

                        is HomeMode.Loaded -> DeckList(
                            rows = mode.rows,
                            onOpenDeck = { deckId ->
                                navigateTo(screenFactory = { StudyScreen(it, deckId) })
                            },
                            onToggle = viewModel::toggle,
                        )
                    }
                }

                // Manual sync lives in a bottom bar now (Light issue #75: extra actions belong
                // in the bottom bar, not a second top-bar button). Visible in Loaded AND Error
                // modes — sync is how you clear staleness or recover — but hidden during the
                // transient/routing modes (Loading, first-run, needs-attention), which either
                // show a spinner or navigate away. A single SYNC text action, rendered exactly
                // like StudyScreen's REVEAL bottom bar. While a sync is InFlight the label is
                // ghosted (the same lightened, not-actionable treatment) and drops taps.
                when (state.mode) {
                    is HomeMode.Loaded, is HomeMode.Error -> {
                        val syncing = state.syncState is SyncState.InFlight
                        LightBottomBar(
                            items = listOf(
                                LightBarButton.Text(
                                    text = "SYNC",
                                    onClick = if (syncing) {
                                        { /* ghosted: in-flight taps are no-ops */ }
                                    } else {
                                        viewModel::sync
                                    },
                                ),
                            ),
                            modifier = if (syncing) Modifier.alpha(GHOSTED_ALPHA) else Modifier,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun DeckList(
    rows: List<DeckRow>,
    onOpenDeck: (Long) -> Unit,
    onToggle: (Long) -> Unit,
) {
    if (rows.isEmpty()) {
        CenteredMessage("no decks")
        return
    }
    LightScrollView(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        rows.forEach { row ->
            DeckListRow(
                row = row,
                onOpen = { onOpenDeck(row.id) },
                onToggle = { onToggle(row.id) },
            )
        }
    }
}

@Composable
private fun DeckListRow(row: DeckRow, onOpen: () -> Unit, onToggle: () -> Unit) {
    // Indent children by their `::` depth; each level adds one grid unit.
    val indent = row.depth.toFloat()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp())
            .padding(start = indent.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Parent decks get a leading +/− glyph that is its own tap target (toggle);
        // tapping the name still starts study. Leaf decks have no glyph.
        if (row.hasChildren) {
            val glyphVariant = if (row.isTopLevel) LightTextVariant.Heading else LightTextVariant.Copy
            Box(
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(end = 0.5f.gridUnitsAsDp()),
            ) {
                // "+" and "−" have different advance widths in the Light font, so a bare
                // glyph makes the deck title shift horizontally on every toggle. Lay out
                // BOTH glyphs invisibly so the slot is always as wide as the wider one,
                // then draw the current glyph on top — the title never moves.
                LightText(text = "+", variant = glyphVariant, modifier = Modifier.alpha(0f))
                LightText(text = "−", variant = glyphVariant, modifier = Modifier.alpha(0f))
                LightText(
                    text = if (row.isExpanded) "−" else "+",
                    variant = glyphVariant,
                    lighten = !row.hasDue,
                )
            }
        }
        LightText(
            text = row.label,
            variant = if (row.isTopLevel) LightTextVariant.Heading else LightTextVariant.Copy,
            lighten = !row.hasDue,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen),
        )
        LightText(
            text = row.countsLabel(),
            variant = LightTextVariant.Fine,
            lighten = !row.hasDue,
            align = TextAlign.End,
            modifier = Modifier.padding(start = 1f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = message, variant = LightTextVariant.Copy)
        LightText(
            text = "retry",
            variant = LightTextVariant.Heading,
            underline = true,
            modifier = Modifier
                .clickable(onClick = onRetry)
                .padding(vertical = 1f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center)
    }
}
