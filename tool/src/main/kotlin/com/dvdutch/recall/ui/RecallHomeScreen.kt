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
import com.thelightphone.sdk.ui.LightIcon
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
 * The ghosted alpha for the sync icon while a manual sync is in flight — the same
 * lightened, "not-actionable" treatment the study screen's idle undo glyph uses (there via
 * LightText's `lighten`; an Icon has no `lighten`, so alpha is the composable-agnostic twin).
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
                // LightTopBar exposes a single right-button slot, so the sync (🔄) control is
                // overlaid immediately LEFT of the real gear at CenterEnd, using the bar's own
                // metrics (height 3 units, horizontal padding 1 unit — mirrored from
                // LightTopBar.kt, whose internals are private; same pattern as StudyScreen). The
                // gear stays the real rightButton; the sync icon is offset left of it by the
                // gear's own 2-unit icon width plus a small gap so the two sit evenly spaced.
                // No bottom padding on this Box: the overlay Row is centered within it, so any
                // padding here would push the 🔄 below the gear's optical center. The 1-unit
                // gap under the bar is applied to the body content instead (below).
                Box {
                    LightTopBar(
                        center = LightTopBarCenter.Text("Recall"),
                        rightButton = LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { navigateTo(::SettingsScreen) },
                            contentDescription = "Settings",
                        ),
                    )
                    val syncing = state.syncState is SyncState.InFlight
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .height(3f.gridUnitsAsDp())
                            // Clear the gear (2-unit icon) plus a 1-unit gap, on top of the
                            // bar's own 1-unit end padding, so 🔄 sits just left of the gear.
                            .padding(end = 4f.gridUnitsAsDp()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The sync icon renders exactly like the gear (same SDK LightIcon
                        // composable → same 2-unit size, same theme-aware drawable + content
                        // tint). While a sync is in flight it renders GHOSTED (the established
                        // idle-control alpha treatment) and ignores taps.
                        LightIcon(
                            icon = LightIcons.REFRESH,
                            contentDescription = "Sync now",
                            modifier = (
                                if (syncing) Modifier else Modifier.clickable(onClick = viewModel::sync)
                            ).alpha(if (syncing) GHOSTED_ALPHA else 1f),
                        )
                    }
                }

                // The 1-unit gap under the top bar (previously the bar's own bottom padding,
                // moved here so it doesn't shift the overlaid 🔄 off the gear's optical center).
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
