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
 * The first-run download screen, reached from [RecallHomeScreen] when no collection
 * file exists yet. It explains what's about to happen, collects the sync config
 * (endpoint / username / password), then runs a full download to pull the whole
 * collection down; a progress phase is shown throughout. On success it goes back to
 * Home (which re-checks and now finds the collection); on failure it shows the
 * reason and offers a retry.
 */
class FirstRunScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, FirstRunViewModel>(sealedActivity) {

    override val viewModelClass: Class<FirstRunViewModel>
        get() = FirstRunViewModel::class.java

    override fun createViewModel(): FirstRunViewModel =
        FirstRunViewModel(lightContext.filesDir, lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val editing by viewModel.editing.collectAsState()
        val editorSession by viewModel.editorSession.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        // The download completed — the collection is on disk; return to Home.
        LaunchedEffect(state.phase) {
            if (state.phase is FirstRunPhase.Done) goBack()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val field = editing
                if (field != null) {
                    val initial = when (field) {
                        FirstRunField.Endpoint -> state.endpoint
                        FirstRunField.Username -> state.username
                        FirstRunField.Password -> state.password
                    }
                    LightTextInputEditor(
                        title = field.title,
                        editorKey = "${field.name}-$editorSession",
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        state = rememberTextFieldState(initial),
                        onSubmit = { viewModel.submitField(field, it) },
                        onBack = viewModel::cancelEdit,
                        submitIcon = LightIcons.ACCEPT,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            center = LightTopBarCenter.Text("Welcome"),
                            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                        )
                        when (val phase = state.phase) {
                            is FirstRunPhase.Downloading -> CenteredMessage("downloading your collection…")
                            is FirstRunPhase.Done -> CenteredMessage("done")
                            else -> IntroBody(
                                state = state,
                                failure = (phase as? FirstRunPhase.Failed)?.reason,
                                onEdit = viewModel::openEditor,
                                onDownload = viewModel::startDownload,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroBody(
    state: FirstRunState,
    failure: String?,
    onEdit: (FirstRunField) -> Unit,
    onDownload: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = "Recall studies your Anki cards on this phone. Enter your sync " +
                "account below and we'll download your collection.",
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        FirstRunRow("Sync endpoint", state.endpoint.ifBlank { "not set" }) { onEdit(FirstRunField.Endpoint) }
        FirstRunRow("Username", state.username.ifBlank { "not set" }) { onEdit(FirstRunField.Username) }
        FirstRunRow(
            "Password",
            if (state.password.isBlank()) "not set" else "•".repeat(state.password.length.coerceIn(4, 8)),
        ) { onEdit(FirstRunField.Password) }

        failure?.let {
            LightText(
                text = "download failed: $it",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
            )
        }

        if (state.canDownload) {
            LightText(
                text = if (failure != null) "retry download" else "download collection",
                variant = LightTextVariant.Heading,
                underline = true,
                modifier = Modifier
                    .clickable(onClick = onDownload)
                    .padding(vertical = 1f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun FirstRunRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Detail, lighten = true)
        LightText(text = value, variant = LightTextVariant.Heading)
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

/** The editor title for a first-run field. */
private val FirstRunField.title: String
    get() = when (this) {
        FirstRunField.Endpoint -> "Sync endpoint"
        FirstRunField.Username -> "Sync username"
        FirstRunField.Password -> "Sync password"
    }
