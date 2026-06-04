package com.thelightphone.sdk.ui.keyboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.thelightphone.lp3Keyboard.ui.CapsLockedLayout
import com.thelightphone.lp3Keyboard.ui.CapsMode
import com.thelightphone.lp3Keyboard.ui.EmojiLayout
import com.thelightphone.lp3Keyboard.ui.ExtendedCharKeyboard
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.Layout
import com.thelightphone.lp3Keyboard.ui.LowerCaseLayout
import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.NumberLayout
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.SymbolsLayout
import com.thelightphone.lp3Keyboard.ui.UpperCaseLayout
import com.thelightphone.lp3Keyboard.ui.defaultEmojis
import com.thelightphone.lp3Keyboard.ui.extendedCharMapping
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class EmbeddedLp3KeyboardViewModel(
    private val delegateCallback: Lp3RepeatableKeyboardCallback,
    keyboardOptions: KeyboardOptions = textInputKeyboardOptions(),
    private val haptic: () -> Unit = {},
) : ViewModel(),
    Lp3KeyboardViewModel {

    var previousLayout: Layout? = null
        private set

    override val layoutFlow: MutableStateFlow<Layout> = MutableStateFlow(LowerCaseLayout)
    override val optionsFlow: StateFlow<KeyboardOptions> = MutableStateFlow(keyboardOptions)

    companion object {
        private const val REPEAT_INTERVAL_MS = 200L

        fun textInputKeyboardOptions(): KeyboardOptions = KeyboardOptions(
            emojis = defaultEmojis,
            displayClose = false,
            displayReturn = false,
            displayVoice = false,
        )

        fun factory(callback: Lp3RepeatableKeyboardCallback): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EmbeddedLp3KeyboardViewModel(callback) as T
            }
    }

    private val heldSpecialKeys = mutableMapOf<SpecialKey, Job>()
    private val heldKeys = mutableMapOf<Int, Job>()
    var capsMode: CapsMode = CapsMode.Off
        private set

    private fun setLayout(layout: Layout) {
        previousLayout = layoutFlow.value
        layoutFlow.value = layout
    }

    private fun showAlphabetLayout() {
        setLayout(
            when (capsMode) {
                CapsMode.Off -> LowerCaseLayout
                CapsMode.Single -> UpperCaseLayout
                CapsMode.Locked -> CapsLockedLayout
            },
        )
    }

    override fun onKeyPressed(code: Int) {
        haptic()
        delegateCallback.onKeyPressed(code)
    }

    override fun onSpecialKeyPressed(key: SpecialKey) {
        haptic()
        delegateCallback.onSpecialKeyPressed(key)
    }

    override fun onKeyReleased(code: Int) {
        heldKeys.remove(code)?.cancel()
        if (layoutFlow.value is ExtendedCharKeyboard) {
            setLayout(previousLayout ?: LowerCaseLayout)
        }
        delegateCallback.onKeyReleased(code)
    }

    override fun onSpecialKeyReleased(key: SpecialKey) {
        val repeatJob = heldSpecialKeys.remove(key)
        repeatJob?.cancel()
        var consumed = true
        when (key) {
            SpecialKey.UpCase, SpecialKey.DownCase -> {
                if (repeatJob != null) return
                capsMode = when (capsMode) {
                    CapsMode.Off -> CapsMode.Single
                    CapsMode.Single, CapsMode.Locked -> CapsMode.Off
                }
                showAlphabetLayout()
            }
            SpecialKey.Numbers -> setLayout(NumberLayout)
            SpecialKey.Letters -> showAlphabetLayout()
            SpecialKey.Symbols -> setLayout(SymbolsLayout)
            SpecialKey.Emojis -> setLayout(EmojiLayout)
            SpecialKey.Close -> {
                if (!layoutFlow.value.isRootLayout) {
                    showAlphabetLayout()
                } else {
                    consumed = false
                }
            }
            else -> consumed = false
        }
        if (!consumed) {
            delegateCallback.onSpecialKeyReleased(key)
        }
    }

    fun setCapsMode(enabled: Boolean) {
        if (capsMode == CapsMode.Locked) return
        capsMode = if (enabled) CapsMode.Single else CapsMode.Off
        when (layoutFlow.value) {
            LowerCaseLayout, UpperCaseLayout, CapsLockedLayout -> showAlphabetLayout()
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) {
        heldKeys[code]?.cancel()
        if (extendedCharMapping.containsKey(code)) {
            haptic()
            setLayout(ExtendedCharKeyboard(code))
            return
        }
        delegateCallback.onKeyLongPressed(code)
        heldKeys[code] = viewModelScope.launch {
            while (true) {
                delay(REPEAT_INTERVAL_MS)
                delegateCallback.onKeyRepeated(code)
            }
        }
    }

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        val allowRepeats = when (key) {
            SpecialKey.UpCase, SpecialKey.DownCase -> {
                capsMode = if (capsMode == CapsMode.Locked) CapsMode.Off else CapsMode.Locked
                showAlphabetLayout()
                false
            }
            else -> true
        }
        haptic()
        delegateCallback.onSpecialKeyLongPressed(key)
        if (allowRepeats) {
            heldSpecialKeys[key]?.cancel()
            heldSpecialKeys[key] = viewModelScope.launch {
                while (true) {
                    delay(REPEAT_INTERVAL_MS)
                    delegateCallback.onSpecialKeyRepeated(key)
                }
            }
        }
    }
}
