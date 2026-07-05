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
 * Recall settings, sync-era: the sync endpoint, username and password (masked),
 * a "Test login" action that runs a real `syncLogin` and reports the outcome via
 * [SettingsMessages], a last-sync line, and the dev row into the render gallery.
 * Mirrors the weather example's Screen/ViewModel pairing and its mode-based
 * full-screen text editing.
 *
 * The initial screen is [RecallHomeScreen]; Settings is reached from there via the
 * gear row.
 */
class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel =
        SettingsViewModel(lightContext.filesDir, lightContext.dataStore)

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
                    is SettingsMode.EditEndpoint -> FieldEditor(
                        title = "Sync endpoint",
                        editorKey = "endpoint-${state.editorSession}",
                        initial = state.endpoint,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitEndpoint,
                        onBack = viewModel::cancelEdit,
                    )

                    is SettingsMode.EditUsername -> FieldEditor(
                        title = "Sync username",
                        editorKey = "username-${state.editorSession}",
                        initial = state.username,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitUsername,
                        onBack = viewModel::cancelEdit,
                    )

                    is SettingsMode.EditPassword -> FieldEditor(
                        title = "Sync password",
                        editorKey = "password-${state.editorSession}",
                        initial = state.password,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitPassword,
                        onBack = viewModel::cancelEdit,
                    )

                    is SettingsMode.Main -> SettingsMain(
                        endpoint = state.endpoint,
                        username = state.username,
                        password = state.password,
                        statusLine = state.statusLine,
                        lastSync = state.lastSync,
                        testing = state.testing,
                        onBack = { goBack() },
                        onEditEndpoint = viewModel::openEditEndpoint,
                        onEditUsername = viewModel::openEditUsername,
                        onEditPassword = viewModel::openEditPassword,
                        onTestLogin = viewModel::testLogin,
                        onOpenGallery = { navigateTo(::GalleryScreen) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldEditor(
    title: String,
    editorKey: String,
    initial: String,
    keyboardOptionsFlow: kotlinx.coroutines.flow.StateFlow<com.thelightphone.lp3Keyboard.ui.KeyboardOptions>,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
) {
    LightTextInputEditor(
        title = title,
        editorKey = editorKey,
        keyboardOptionsFlow = keyboardOptionsFlow,
        state = rememberTextFieldState(initial),
        onSubmit = onSubmit,
        onBack = onBack,
        submitIcon = LightIcons.ACCEPT,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SettingsMain(
    endpoint: String,
    username: String,
    password: String,
    statusLine: String?,
    lastSync: Long?,
    testing: Boolean,
    onBack: () -> Unit,
    onEditEndpoint: () -> Unit,
    onEditUsername: () -> Unit,
    onEditPassword: () -> Unit,
    onTestLogin: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text("Settings"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            SettingRow(
                label = "Sync endpoint",
                value = endpoint.ifBlank { "not set" },
                onClick = onEditEndpoint,
            )
            if (com.dvdutch.recall.prefs.TextSanitizer.isInsecureEndpoint(endpoint)) {
                LightText(
                    text = "http — traffic is unencrypted; use https if your server supports it",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
                )
            }
            SettingRow(
                label = "Username",
                value = username.ifBlank { "not set" },
                onClick = onEditUsername,
            )
            SettingRow(
                label = "Password",
                value = if (password.isBlank()) "not set" else maskSecret(password),
                onClick = onEditPassword,
            )
            SettingRow(
                label = "Login",
                value = if (testing) "testing…" else "Test login",
                onClick = onTestLogin,
            )

            statusLine?.let { line ->
                LightText(
                    text = line,
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }

            LightText(
                text = SettingsMessages.lastSyncLine(lastSync),
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
            )

            // Dev-only row into the render-node gallery.
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

/** Masks a secret so it is present-but-not-exposed (all dots; length hidden). */
private fun maskSecret(secret: String): String = "•".repeat(secret.length.coerceIn(4, 8))
