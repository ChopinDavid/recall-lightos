package com.thelightphone.sdk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.*
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.keyboard.TextInputKeyboardCallback


@Composable
fun LightTextInputEditor(
    title: String,
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
    editorKey: Any = title,
) {

    val keyboardCallback = remember(state) { TextInputKeyboardCallback(state) }

    val keyboardViewModel: Lp3KeyboardViewModel = viewModel<DefaultLp3KeyboardViewModel>(
        key = "LightTextInputEditor-$editorKey",
        factory = factory(keyboardCallback),
    )

    LightTextInputEditor(title, state, onSubmit, onBack, keyboardViewModel, modifier, submitLabel)
}

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
    state: TextFieldState,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    viewModel: Lp3KeyboardViewModel,
    modifier: Modifier = Modifier,
    submitLabel: String = "SUBMIT",
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val colors = LightThemeTokens.colors
    val inputStyle = lightInputTextStyle()

    LaunchedEffect(Unit) {
        keyboardController?.hide()
    }

    Surface {
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
                    state = state,
                    readOnly = true,
                    textStyle = inputStyle,
                    cursorBrush = SolidColor(colors.content),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties { canFocus = false },
                )
            }

            LightEmbeddedLp3Keyboard(viewModel = viewModel)

            LightBottomBar(
                items = listOf(
                    LightBarButton.Text(
                        text = submitLabel,
                        onClick = { onSubmit(state.text) },
                    ),
                ),
            )
        }
    }
}

private fun factory(callback: Lp3RepeatableKeyboardCallback): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DefaultLp3KeyboardViewModel(
                callback,
                showCloseButtonForLayout = {
                    when (it) {
                        EmojiLayout, is ExtendedCharKeyboard -> true
                        CapsLockedLayout, LowerCaseLayout, NumberLayout, SymbolsLayout, UpperCaseLayout -> false
                    }
                }
            ) as T
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
    val state = rememberTextFieldState("hi")
    LightTheme(colors = LightThemeColors.Dark) {
        LightTextInputEditor(
            title = "Name",
            state = state,
            onSubmit = {},
            onBack = {},
        )
    }
}
