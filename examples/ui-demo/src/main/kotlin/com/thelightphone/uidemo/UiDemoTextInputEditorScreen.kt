package com.thelightphone.uidemo

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

class UiDemoTextInputEditorViewModel : LightViewModel()

class UiDemoTextInputEditorScreen(sealedActivity: SealedLightActivity) :
    LightScreen<UiDemoTextInputEditorViewModel>(sealedActivity) {

    override val viewModelClass: Class<UiDemoTextInputEditorViewModel>
        get() = UiDemoTextInputEditorViewModel::class.java

    override val showBackBar: Boolean = false

    override fun createViewModel() = UiDemoTextInputEditorViewModel()

    @Composable
    override fun Content() {
        val editorRequest = remember { UiDemoTextInputNavigation.request }
        if (editorRequest == null) {
            return
        }

        val textState = rememberTextFieldState(editorRequest.initialValue)
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = editorRequest.title,
                state = textState,
                onSubmit = { result ->
                    UiDemoTextInputNavigation.submitResult(result.toString())
                    goBack()
                },
                onBack = {
                    UiDemoTextInputNavigation.cancel()
                    goBack()
                },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
