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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.dvdutch.recall.prefs.RecallPreferences
import com.thelightphone.sdk.InitialScreen
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
 * Recall settings: bridge URL + token, and a "Test connection" action that hits
 * `/v1/status` and reports the outcome via [SettingsMessages]. Mirrors the
 * weather example's Screen/ViewModel pairing and its mode-based full-screen
 * text editing (weather's LocationInput mode).
 *
 * Temporarily marked `@InitialScreen` for Task 5 so the tool builds/runs; Task 6
 * moves the initial screen to the deck list.
 */
@InitialScreen
class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel =
        SettingsViewModel(lightContext.dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (state.mode) {
                    is SettingsMode.EditUrl -> {
                        LightTextInputEditor(
                            title = "Bridge URL",
                            editorKey = "url-${state.editorSession}",
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            state = rememberTextFieldState(state.bridgeUrl),
                            onSubmit = viewModel::submitUrl,
                            onBack = viewModel::cancelEdit,
                            submitIcon = LightIcons.ACCEPT,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    is SettingsMode.EditToken -> {
                        LightTextInputEditor(
                            title = "Bridge token",
                            editorKey = "token-${state.editorSession}",
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            state = rememberTextFieldState(state.bridgeToken),
                            onSubmit = viewModel::submitToken,
                            onBack = viewModel::cancelEdit,
                            submitIcon = LightIcons.ACCEPT,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    is SettingsMode.Main -> {
                        SettingsMain(
                            bridgeUrl = state.bridgeUrl,
                            bridgeToken = state.bridgeToken,
                            statusLine = state.statusLine,
                            testing = state.testing,
                            onEditUrl = viewModel::openEditUrl,
                            onEditToken = viewModel::openEditToken,
                            onTestConnection = viewModel::testConnection,
                            onOpenGallery = {
                                navigateTo(::GalleryScreen)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsMain(
    bridgeUrl: String,
    bridgeToken: String,
    statusLine: String?,
    testing: Boolean,
    onEditUrl: () -> Unit,
    onEditToken: () -> Unit,
    onTestConnection: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text("Settings"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            SettingRow(
                label = "Bridge URL",
                value = bridgeUrl.ifBlank { RecallPreferences.DEFAULT_BRIDGE_URL },
                onClick = onEditUrl,
            )
            SettingRow(
                label = "Token",
                value = if (bridgeToken.isBlank()) "not set" else maskToken(bridgeToken),
                onClick = onEditToken,
            )
            SettingRow(
                label = "Connection",
                value = if (testing) "testing…" else "Test connection",
                onClick = onTestConnection,
            )

            statusLine?.let { line ->
                LightText(
                    text = line,
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }

            // Dev-only: long-press-equivalent row into the render-node gallery
            // (per plan Task 4, the gallery is reached from Settings).
            SettingRow(
                label = "Developer",
                value = "Render gallery",
                onClick = onOpenGallery,
            )
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        LightText(
            text = value,
            variant = LightTextVariant.Heading,
        )
    }
}

/** Shows only the last 4 chars of the token so it is recognizable but not exposed. */
private fun maskToken(token: String): String =
    if (token.length <= 4) "••••" else "••••" + token.takeLast(4)
