package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
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

/**
 * The needs-attention screen, reached when the collection has diverged so far from
 * the server that a normal sync can't reconcile it (a FULL_* requirement). It
 * explains the divergence and offers two destructive resolutions — pull the server
 * down or push the local up — each gated behind typing the exact direction word
 * ("download"/"upload"), mirroring the bridge CLI's safety. Confirming runs
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
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state.phase) {
            if (state.phase is AttentionPhase.Resolved) goBack()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val phase = state.phase) {
                    is AttentionPhase.Confirming -> LightTextInputEditor(
                        title = "Type \"${phase.direction.word}\" to confirm",
                        editorKey = "attention-${phase.direction.word}",
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        state = rememberTextFieldState(""),
                        onSubmit = { viewModel.confirm(phase.direction, it) },
                        onBack = viewModel::cancel,
                        submitIcon = LightIcons.ACCEPT,
                        modifier = Modifier.fillMaxSize(),
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
                            is AttentionPhase.Resolving -> CenteredMessage("syncing…")
                            is AttentionPhase.Resolved -> CenteredMessage("done")
                            else -> ExplainBody(
                                failure = (phase as? AttentionPhase.Failed)?.reason,
                                onChoose = viewModel::choose,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplainBody(
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
