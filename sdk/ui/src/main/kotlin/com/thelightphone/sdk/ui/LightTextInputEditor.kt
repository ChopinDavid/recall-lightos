package com.thelightphone.sdk.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.sdk.ui.keyboard.EmbeddedLp3KeyboardViewModel
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.keyboard.TextInputKeyboardCallback

/**
 * Full-screen text entry matching LightOS `DisplayWithKeyboardPortrait`
 *
 * - Top bar with back button + title
 * - Remaining space shows underlined heading-style input (top-aligned)
 * - Embedded LP3 keyboard, and [LightBottomBar] below it
 */
@Composable
fun LightTextInputEditor(
    title: String,
    value: String,
    onValueChange: (String) -> Unit = {},
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    editorKey: Any = title,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val colors = LightThemeTokens.colors
    val inputStyle = lightInputTextStyle()

    val textState = remember(editorKey) { mutableStateOf(value) }
    val onValueChangeState = rememberUpdatedState(onValueChange)

    val keyboardViewModel: EmbeddedLp3KeyboardViewModel = viewModel(
        key = "LightTextInputEditor-$editorKey",
        factory = EmbeddedLp3KeyboardViewModel.factory(
            TextInputKeyboardCallback(
                currentValue = { textState.value },
                onValueChange = { newValue ->
                    textState.value = newValue
                    onValueChangeState.value(newValue)
                },
            ),
        ),
    )

    LaunchedEffect(Unit) {
        keyboardController?.hide()
    }

    Column(modifier = modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text(title),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.TopStart,
        ) {
            BasicTextField(
                value = textState.value,
                onValueChange = {},
                readOnly = true,
                textStyle = inputStyle,
                singleLine = false,
                cursorBrush = SolidColor(colors.content),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = false },
            )
        }

        LightEmbeddedLp3Keyboard(viewModel = keyboardViewModel)

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = submitLabel,
                    onClick = { onSubmit(textState.value) },
                ),
            ),
        )
    }
}

@Composable
private fun lightInputTextStyle(): TextStyle {
    val colors = LightThemeTokens.colors
    val t = LightThemeTokens.typography
    return t.heading
        .copy(
            color = colors.content,
            textDecoration = TextDecoration.Underline,
        )
        .scaledForScreenHeight()
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightTextInputEditorDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightTextInputEditor(
            title = "Name",
            value = "London",
            onValueChange = {},
            onSubmit = { },
            onBack = {},
        )
    }
}
