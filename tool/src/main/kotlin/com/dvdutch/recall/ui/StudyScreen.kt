package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dvdutch.recall.api.BridgeError
import com.dvdutch.recall.api.CardPayload
import com.dvdutch.recall.api.Counts
import com.dvdutch.recall.api.SyncInfo
import com.dvdutch.recall.study.FailCause
import com.dvdutch.recall.study.StudyState
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
 * The study session screen for one deck. It renders whichever [StudyState] the
 * [StudyViewModel]'s [com.dvdutch.recall.study.StudyMachine] currently exposes:
 * Loading → a centered "…"; ShowingFront → the card front + a "Reveal" bar;
 * ShowingBack → the card back + four grade buttons (again/hard/good/easy);
 * Finished → a summary; Failed → mapped copy with a retry when retriable.
 * A slim session-counts header sits under the top bar throughout. Leaving the
 * screen (back or hide) best-effort finishes the session.
 */
class StudyScreen(
    sealedActivity: SealedLightActivity,
    private val deckId: Long,
) : LightScreen<Unit, StudyViewModel>(sealedActivity) {

    override val viewModelClass: Class<StudyViewModel>
        get() = StudyViewModel::class.java

    override fun createViewModel(): StudyViewModel =
        StudyViewModel(
            deckId = deckId,
            filesDir = lightContext.filesDir,
            dataStore = lightContext.dataStore,
        )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val mediaLoader by viewModel.mediaLoader.collectAsState()

        // Whether the card-actions menu (bury / suspend / mark) is open. Local UI state:
        // it only ever opens over a live card and closes the moment an action fires.
        var actionsOpen by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { viewModel.begin() }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Study"),
                    // "MORE" opens the card-actions menu, but only over a live card.
                    rightButton = if (state.hasCard()) {
                        LightBarButton.Text(text = "MORE", onClick = { actionsOpen = true })
                    } else {
                        null
                    },
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )

                CountsHeader(state.currentCounts(), state.marked())

                // Unobtrusive UNDO control just below the counts header, shown only
                // when the machine reports the last grade is undoable (an answer was
                // given this session AND the engine holds an undoable op). One tap
                // reverts the last grade via rslib's own undo; the card returns and
                // the counts tick back.
                if (state.undoAvailable()) {
                    UndoRow(onUndo = viewModel::undo)
                }

                if (actionsOpen && state.hasCard()) {
                    // The card-actions menu takes over the body while open. Each action
                    // dispatches the backend op via the ViewModel and closes the menu;
                    // bury/suspend advance to the next card, mark just toggles the star.
                    ActionsMenu(
                        marked = state.marked(),
                        onBury = { viewModel.buryCard(); actionsOpen = false },
                        onSuspend = { viewModel.suspendCard(); actionsOpen = false },
                        onToggleMark = { viewModel.toggleMark(); actionsOpen = false },
                        onCancel = { actionsOpen = false },
                    )
                    return@Column
                }

                when (val s = state) {
                    is StudyState.Loading -> CenteredMessage("…")

                    is StudyState.ShowingFront -> CardBody(
                        card = s.card,
                        showBack = false,
                        mediaLoader = mediaLoader,
                        onAutoPlay = viewModel::playAudio,
                        onReplay = viewModel::playAudio,
                        bottom = {
                            LightBottomBar(
                                items = listOf(
                                    LightBarButton.Text(
                                        text = "REVEAL",
                                        onClick = viewModel::reveal,
                                    ),
                                ),
                            )
                        },
                    )

                    is StudyState.ShowingBack -> CardBody(
                        card = s.card,
                        showBack = true,
                        mediaLoader = mediaLoader,
                        onAutoPlay = viewModel::playAudio,
                        onReplay = viewModel::playAudio,
                        bottom = {
                            GradeBar(
                                buttons = gradeButtons(s.card.nextDueLabels),
                                onGrade = viewModel::grade,
                            )
                        },
                    )

                    is StudyState.Finished -> FinishedBody(
                        reviewed = s.reviewed,
                        sync = s.sync,
                        counts = s.counts,
                        onBack = { goBack() },
                    )

                    is StudyState.Failed -> FailedBody(
                        cause = s.cause,
                        retriable = s.retriable,
                        onRetry = viewModel::retry,
                        onBack = { goBack() },
                    )
                }
            }
        }
    }
}

/** The most recent server counts for the header, whichever state we're in. */
private fun StudyState.currentCounts(): Counts? = when (this) {
    is StudyState.ShowingFront -> counts
    is StudyState.ShowingBack -> counts
    is StudyState.Finished -> counts
    else -> null
}

/** Whether the UNDO control should be shown for the current review state. */
private fun StudyState.undoAvailable(): Boolean = when (this) {
    is StudyState.ShowingFront -> undoAvailable
    is StudyState.ShowingBack -> undoAvailable
    else -> false
}

/** Whether a live card is currently showing (front or back) — gates the MORE control. */
private fun StudyState.hasCard(): Boolean =
    this is StudyState.ShowingFront || this is StudyState.ShowingBack

/** Whether the current note is marked — drives the ★ indicator in the counts header. */
private fun StudyState.marked(): Boolean = when (this) {
    is StudyState.ShowingFront -> marked
    is StudyState.ShowingBack -> marked
    else -> false
}

/**
 * A tappable "↶ UNDO" row shown only when the last grade is undoable; a tap reverts
 * it via rslib's own undo. Monochrome LightText matching [ReplayAudioRow] — sits
 * under the counts header, discoverable but unobtrusive.
 */
@Composable
private fun UndoRow(onUndo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUndo)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = "↶ UNDO",
            variant = LightTextVariant.Fine,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}

/**
 * Slim `new · learning · review` header sitting under the top bar. A leading ★ appears
 * when the current note is [marked] (AnkiDroid's Mark Note) — a subtle, monochrome cue.
 */
@Composable
private fun CountsHeader(counts: Counts?, marked: Boolean) {
    val body = counts?.let { "${it.new} new · ${it.learning} learning · ${it.review} review" }
        ?: " "
    val text = if (marked) "★ $body" else body
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    )
}

/**
 * The card-actions menu: BURY CARD / SUSPEND CARD / MARK (or UNMARK) NOTE / CANCEL, each a
 * tappable LightText row matching the SettingsScreen aesthetic. All are backend ops applied
 * to the CURRENT card/note; bury and suspend advance to the next card, mark toggles the ★.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ActionsMenu(
    marked: Boolean,
    onBury: () -> Unit,
    onSuspend: () -> Unit,
    onToggleMark: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        ActionRow(label = "BURY CARD", onClick = onBury)
        ActionRow(label = "SUSPEND CARD", onClick = onSuspend)
        ActionRow(label = if (marked) "UNMARK NOTE" else "MARK NOTE", onClick = onToggleMark)
        // Full-white like every other row: in a monochrome text UI a greyed row reads as
        // DISABLED, not "secondary" (and AttentionScreen's CANCEL is white — consistency).
        ActionRow(label = "CANCEL", onClick = onCancel)
    }
}

/** One tappable action row in [ActionsMenu]. */
@Composable
private fun ActionRow(label: String, onClick: () -> Unit, lighten: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Heading,
            lighten = lighten,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CardBody(
    card: CardPayload,
    showBack: Boolean,
    mediaLoader: MediaLoader?,
    onAutoPlay: (List<String>) -> Unit,
    onReplay: (List<String>) -> Unit,
    bottom: @Composable () -> Unit,
) {
    val sideAudio = activeSideAudio(card, showBack)

    // Auto-play the current side's audio once per (card, side) transition — front on
    // show, back on reveal (Anki's default). Keyed so it fires on the transition, not on
    // every recomposition; an empty list is a no-op play (nothing to hear this side).
    LaunchedEffect(card.cardId, showBack) {
        onAutoPlay(sideAudio)
    }

    LightScrollView(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        RenderNodeColumn(
            nodes = if (showBack) card.back else card.front,
            mediaLoader = mediaLoader,
        )
    }
    if (sideHasAudio(card, showBack)) {
        ReplayAudioRow(onReplay = { onReplay(sideAudio) })
    }
    bottom()
}

/**
 * A tappable "🔊 REPLAY AUDIO" row shown only when the current side has audio; a tap
 * replays that side's list from the start. Monochrome LightText in the design system,
 * sitting just above the bottom bar / grade buttons — discoverable but not intrusive.
 */
@Composable
private fun ReplayAudioRow(onReplay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onReplay)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = "🔊 REPLAY AUDIO",
            variant = LightTextVariant.Fine,
            lighten = true,
            align = TextAlign.Center,
        )
    }
}

/** The four grade buttons, evenly spaced, each a fixed word over its interval. */
@Composable
private fun GradeBar(buttons: List<GradeButton>, onGrade: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        buttons.forEach { button ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onGrade(button.rating) }
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightText(
                    text = button.word,
                    variant = LightTextVariant.Button,
                    align = TextAlign.Center,
                )
                LightText(
                    text = button.interval,
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FinishedBody(
    reviewed: Int,
    sync: SyncInfo?,
    counts: Counts?,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = "session done — $reviewed reviewed",
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )

        // "more due later today" when the queue emptied but counts remain.
        val remaining = counts?.let { it.new + it.learning + it.review } ?: 0
        if (remaining > 0) {
            LightText(
                text = "$remaining more due later today",
                variant = LightTextVariant.Copy,
                lighten = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }

        sync?.let {
            LightText(
                text = it.detail,
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }
    }
    LightBottomBar(
        items = listOf(
            LightBarButton.Text(text = "DONE", onClick = onBack),
        ),
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FailedBody(
    cause: FailCause,
    retriable: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = failMessage(cause),
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
        if (retriable) {
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
    LightBottomBar(
        items = listOf(
            LightBarButton.Text(text = "BACK", onClick = onBack),
        ),
    )
}

/**
 * Maps a [FailCause] to operator copy: transport causes reuse the shared
 * [SettingsMessages] error copy; an answer rejection gets its own line. The URL
 * is not carried into the session, so the Unreachable case gets a short generic
 * line instead of the URL-interpolated Settings copy.
 */
private fun failMessage(cause: FailCause): String = when (cause) {
    is FailCause.Transport -> when (cause.error) {
        is BridgeError.Unreachable -> "sync failed — check your connection"
        // A FULL_* divergence can't be resolved mid-session — the resolution flow lives on
        // Home (AttentionScreen). Point the operator there instead of the server-facing copy.
        is BridgeError.NeedsAttention -> "collections have diverged — resolve from the home screen"
        else -> SettingsMessages.errorLine(cause.error, "")
    }
    FailCause.AnswerRejected -> "answer rejected — try again"
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CenteredMessage(text: String) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center)
    }
}
