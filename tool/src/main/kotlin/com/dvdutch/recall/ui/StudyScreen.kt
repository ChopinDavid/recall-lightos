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
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )

                CountsHeader(state.currentCounts())

                when (val s = state) {
                    is StudyState.Loading -> CenteredMessage("…")

                    is StudyState.ShowingFront -> CardBody(
                        card = s.card,
                        showBack = false,
                        mediaLoader = mediaLoader,
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

/** Slim `new · learning · review` header sitting under the top bar. */
@Composable
private fun CountsHeader(counts: Counts?) {
    val text = counts?.let { "${it.new} new · ${it.learning} learning · ${it.review} review" }
        ?: " "
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CardBody(
    card: CardPayload,
    showBack: Boolean,
    mediaLoader: MediaLoader?,
    bottom: @Composable () -> Unit,
) {
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
    bottom()
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
        is BridgeError.Unreachable -> "can't reach your bridge"
        // A FULL_* divergence can't be resolved mid-session — the resolution flow lives on
        // Home (AttentionScreen). Point the operator there instead of the server-facing copy.
        is BridgeError.NeedsAttention -> "collections have diverged — resolve from the home screen"
        else -> SettingsMessages.errorLine(cause.error, "")
    }
    FailCause.AnswerRejected -> "answer rejected by the bridge — try again"
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
