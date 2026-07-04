package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlin.math.roundToInt

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
        val typeAnswerEditing by viewModel.typeAnswerEditing.collectAsState()
        val typeAnswerSession by viewModel.typeAnswerSession.collectAsState()
        val keyboardOptionsFlow = com.thelightphone.sdk.rememberKeyboardOptions()

        // Whether the card-actions menu (bury / suspend / mark) is open. Local UI state:
        // it only ever opens over a live card and closes the moment an action fires.
        var actionsOpen by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) { viewModel.begin() }

        LightTheme(colors = themeColors) {
            // The full-screen type-answer editor takes over when open — the same SDK editor
            // FirstRun/Settings use. It starts empty each open (bumped session key) and is
            // sanitized on submit (interior spaces kept; newlines stripped).
            if (typeAnswerEditing) {
                LightTextInputEditor(
                    title = "Type your answer",
                    editorKey = "type-answer-$typeAnswerSession",
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    state = androidx.compose.foundation.text.input.rememberTextFieldState(""),
                    onSubmit = { viewModel.submitTypeAnswer(it) },
                    onBack = viewModel::cancelTypeAnswer,
                    submitIcon = LightIcons.ACCEPT,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LightThemeTokens.colors.background),
                )
                return@LightTheme
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // LightTopBar exposes a single right-button slot, so the two right
                // controls (undo + MORE) are overlaid at CenterEnd using the bar's own
                // metrics (height 3 units, horizontal padding 1 unit — mirrored from
                // LightTopBar.kt, whose internals are private).
                Box(modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp())) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                        ),
                        center = LightTopBarCenter.Text("Study"),
                        rightButton = null,
                    )
                    val undoable = state.undoAvailable()
                    if (state.hasCard() || undoable) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .height(3f.gridUnitsAsDp())
                                .padding(end = 1f.gridUnitsAsDp()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // AnkiDroid-style undo affordance: ALWAYS visible during
                            // study, ghosted when there is nothing to undo and full
                            // white (tappable) after a grade — so the control teaches
                            // that undo exists before it's needed. Fires rslib's own
                            // undo; the card returns and the counts tick back.
                            // The ↶ glyph's ink hangs low in its line box (arrow glyphs sit
                            // near the baseline), so its visual center lands ~0.35 grid units
                            // below the bar's other elements; the offset re-centers the INK
                            // against "Study"/"MORE" (measured on-device).
                            LightText(
                                text = "↶",
                                variant = LightTextVariant.Copy,
                                lighten = !undoable,
                                modifier = (
                                    if (undoable) {
                                        Modifier.clickable(onClick = viewModel::undo)
                                    } else {
                                        Modifier
                                    }
                                )
                                    .offset(y = (-0.35f).gridUnitsAsDp())
                                    .padding(horizontal = 0.5f.gridUnitsAsDp()),
                            )
                            // "MORE" opens the card-actions menu, only over a live card.
                            if (state.hasCard()) {
                                LightText(
                                    text = "MORE",
                                    variant = LightTextVariant.Fine,
                                    modifier = Modifier
                                        .clickable(onClick = { actionsOpen = true })
                                        .padding(start = 0.5f.gridUnitsAsDp()),
                                )
                            }
                        }
                    }
                }

                CountsHeader(state.currentCounts(), state.marked())

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
                        // A type-answer card ({{type:Field}}) shows the TYPE ANSWER row above
                        // REVEAL; a normal card leaves this null and shows no affordance.
                        onTypeAnswer = if (s.typeAnswerExpected != null) {
                            { viewModel.openTypeAnswerEditor() }
                        } else {
                            null
                        },
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
                        // The type-answer result block (diff or expected-answer line) sits at
                        // the top of the back; null for a normal card.
                        typeAnswerReveal = s.typeAnswer,
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
    onTypeAnswer: (() -> Unit)? = null,
    typeAnswerReveal: com.dvdutch.recall.study.TypeAnswerReveal? = null,
) {
    val sideAudio = activeSideAudio(card, showBack)

    // Auto-play the current side's audio once per (card, side) transition — front on
    // show, back on reveal (Anki's default). Keyed so it fires on the transition, not on
    // every recomposition; an empty list is a no-op play (nothing to hear this side).
    LaunchedEffect(card.cardId, showBack) {
        onAutoPlay(sideAudio)
    }

    // We own the card body's scroll offset (via our CardScrollView, since the SDK's
    // LightScrollView keeps its scrollState private) so we can auto-scroll a TALL card's
    // answer into view on reveal. Anchor: the window-space top of the scroll viewport and of
    // the answer-boundary node (the first back node past the {{FrontSide}} prefix — the <hr>
    // rule when present, else the first answer node), captured via onGloballyPositioned; their
    // difference plus the current scroll gives that boundary's position in content space,
    // which revealScrollTarget turns into a scroll offset.
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // The gap left above the divider after an auto-scroll (one grid unit). Resolved here in
    // composable scope; gridUnitsAsDp is @Composable and cannot be called inside the effect.
    val topMargin = with(density) { 1f.gridUnitsAsDp().toPx() }.roundToInt()
    var viewportTopY by remember { mutableStateOf(0f) }
    var viewportH by remember { mutableStateOf(0) }
    var dividerWindowY by remember(card.cardId) { mutableStateOf<Float?>(null) }

    // On the front→back transition (and only then), if the divider sits below the fold,
    // smooth-scroll it to a small margin below the viewport top. Keyed on (card, showBack)
    // so it fires once per reveal, never on plain recomposition; a short card whose divider
    // is already visible yields a null target and does not move (the common case, no motion).
    // A quick animated scroll (not an instant jump) reads best on the Light aesthetic: the
    // small, deliberate motion signals "the answer continues below" without the jarring
    // teleport of an instant jump. It runs after the answer subtree has been positioned
    // (dividerWindowY non-null), so it composes with — never fights — the scrollbar's
    // one-frame show debounce.
    LaunchedEffect(card.cardId, showBack, dividerWindowY, viewportH) {
        if (!showBack) return@LaunchedEffect
        val dividerY = dividerWindowY ?: return@LaunchedEffect
        if (viewportH <= 0) return@LaunchedEffect
        val dividerContentY = (dividerY - viewportTopY).roundToInt() + scrollState.value
        val target = revealScrollTarget(
            dividerY = dividerContentY,
            viewportH = viewportH,
            currentScroll = scrollState.value,
            maxScroll = scrollState.maxValue,
            topMargin = topMargin,
        )
        if (target != null) scrollState.animateScrollTo(target)
    }

    CardScrollView(
        scrollState = scrollState,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp())
            .onGloballyPositioned { coords ->
                viewportTopY = coords.positionInWindow().y
                viewportH = coords.size.height
            },
    ) {
        // The type-answer result (diff or expected-answer line) sits at the very top of the
        // revealed back, clearly set off above the card content. Only on the back.
        if (showBack && typeAnswerReveal != null) {
            TypeAnswerRevealBlock(reveal = typeAnswerReveal, mediaLoader = mediaLoader)
        }
        RenderNodeColumn(
            nodes = if (showBack) card.back else card.front,
            mediaLoader = mediaLoader,
            // The answer boundary is the first back node past the {{FrontSide}} prefix, i.e.
            // index == the number of front nodes (the <hr> rule if the template has one, else
            // the first answer node). Only meaningful on the back; −1 on the front never fires.
            dividerIndex = if (showBack) card.front.size else -1,
            onNodePositioned = if (showBack) {
                { coords -> dividerWindowY = coords.positionInWindow().y }
            } else {
                null
            },
        )
    }
    if (sideHasAudio(card, showBack)) {
        ReplayAudioRow(onReplay = { onReplay(sideAudio) })
    }
    // The TYPE ANSWER affordance sits just above REVEAL on a type-answer front.
    if (!showBack && onTypeAnswer != null) {
        TypeAnswerRow(onOpen = onTypeAnswer)
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

/**
 * The pure done-screen copy. The session serves cards until the engine's queue is
 * TRULY exhausted (AnkiDroid's serve-until-done model), so [StudyState.Finished] is
 * only ever reached at real exhaustion — never a fixed batch cap. The copy must
 * therefore be truthful about what, if anything, returns later today:
 *   - always the reviewed count;
 *   - if learning cards remain, they were held back only because they are scheduled a
 *     few minutes out, so they WILL be due again later today (pluralized on count);
 *   - otherwise a plain "all caught up".
 *
 * Kept as a plain object (no Compose) so the copy is unit-tested on the JVM.
 */
object FinishedCopy {
    fun lines(reviewed: Int, counts: Counts?): List<String> {
        val head = "session done — $reviewed reviewed"
        val learning = counts?.learning ?: 0
        val tail = if (learning > 0) {
            val noun = if (learning == 1) "card" else "cards"
            "$learning $noun will be due again later today"
        } else {
            "all caught up"
        }
        return listOf(head, tail)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FinishedBody(
    reviewed: Int,
    sync: SyncInfo?,
    counts: Counts?,
    onBack: () -> Unit,
) {
    val lines = FinishedCopy.lines(reviewed, counts)
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = lines[0],
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )

        LightText(
            text = lines[1],
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )

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
