package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * The needs-attention screen, reached when the collection has diverged so far from the
 * server that a normal sync can't reconcile it (a FULL_* requirement). Resolution is a
 * deliberate two-tap: the operator picks which side to keep, then confirms the concrete
 * consequence — stated with the real local card count — on a per-direction screen. No
 * typing (the old typed-word gate is gone). On the confirm screen the destructive action
 * sits ABOVE the thumb-default Cancel so it takes a deliberate reach, and confirming runs
 * `fullSync(direction)`; on success it returns to Home.
 */
class AttentionScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, AttentionViewModel>(sealedActivity) {

    override val viewModelClass: Class<AttentionViewModel>
        get() = AttentionViewModel::class.java

    override fun createViewModel(): AttentionViewModel =
        AttentionViewModel(lightContext.filesDir, lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LaunchedEffect(state.phase) {
            if (state.phase is AttentionPhase.Done) goBack()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val phase = state.phase) {
                    is AttentionPhase.Confirm -> ConfirmBody(
                        phase = phase,
                        onConfirm = { viewModel.confirm(phase.direction) },
                        onCancel = viewModel::cancel,
                    )

                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                            ),
                            center = LightTopBarCenter.Text("Needs attention"),
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                        when (phase) {
                            is AttentionPhase.Running -> CenteredMessage("syncing…")
                            is AttentionPhase.Done -> CenteredMessage("done")
                            is AttentionPhase.Failed -> ChooseBody(
                                failure = phase.reason,
                                onChoose = viewModel::choose,
                            )
                            else -> ChooseBody(failure = null, onChoose = viewModel::choose)
                        }
                    }
                }
            }
        }
    }
}

/** Screen 1: explain the divergence and offer the two clearly-distinct directions. */
@Composable
private fun ChooseBody(
    failure: String?,
    onChoose: (AttentionDirection) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = "Your collection has diverged from the server and can't be merged " +
                "automatically. Choose which side to keep — the other side's recent " +
                "changes will be overwritten.",
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        failure?.let {
            LightText(
                text = "sync failed: $it",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(bottom = 0.75f.gridUnitsAsDp()),
            )
        }

        DirectionRow(
            label = "Keep the server's copy",
            detail = "download — overwrites this phone",
            onClick = { onChoose(AttentionDirection.Download) },
        )
        DirectionRow(
            label = "Keep this phone's copy",
            detail = "upload — overwrites the server",
            onClick = { onChoose(AttentionDirection.Upload) },
        )
    }
}

/**
 * Screen 2: state the concrete, irreversible consequence for the chosen direction —
 * with the real local card count when known — then two buttons. The destructive
 * confirm is a tappable line in the body; Cancel is the thumb-default at the bottom,
 * so undoing is easy and the destructive action takes a deliberate reach up.
 */
@Composable
private fun ConfirmBody(
    phase: AttentionPhase.Confirm,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onCancel,
            ),
            center = LightTopBarCenter.Text(confirmTitle(phase.direction)),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = confirmConsequence(phase.direction, phase.localCardCount),
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(bottom = 1.5f.gridUnitsAsDp()),
            )
            LightText(
                text = confirmAction(phase.direction),
                variant = LightTextVariant.Heading,
                underline = true,
                modifier = Modifier
                    .clickable(onClick = onConfirm)
                    .padding(vertical = 1f.gridUnitsAsDp()),
            )
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "CANCEL", onClick = onCancel),
            ),
        )
    }
}

private fun confirmTitle(direction: AttentionDirection): String = when (direction) {
    AttentionDirection.Download -> "Keep server's copy?"
    AttentionDirection.Upload -> "Keep phone's copy?"
}

/** The destructive-action label — names what gets destroyed, not a neutral "OK". */
private fun confirmAction(direction: AttentionDirection): String = when (direction) {
    AttentionDirection.Download -> "Delete this phone's copy"
    AttentionDirection.Upload -> "Overwrite the server"
}

/**
 * Plain-terms, irreversible consequence copy. Download names the local count (what is
 * about to be deleted); Upload names the local count as what replaces the server, and —
 * per the design — does NOT fabricate a server count (it isn't cheaply known without a
 * sync). [cards] falls back to "everything" when the count couldn't be read.
 */
private fun confirmConsequence(direction: AttentionDirection, localCardCount: Int?): String {
    val cards = localCardCount?.let { "$it cards" } ?: "everything"
    return when (direction) {
        AttentionDirection.Download ->
            "This deletes everything on THIS PHONE ($cards) and replaces it with the " +
                "server's copy. This can't be undone."
        AttentionDirection.Upload ->
            "This replaces the SERVER's collection with this phone's ($cards). Anything " +
                "on the server that isn't on this phone will be lost. This can't be undone."
    }
}

@Composable
private fun DirectionRow(label: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Heading)
        LightText(text = detail, variant = LightTextVariant.Detail, lighten = true)
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
