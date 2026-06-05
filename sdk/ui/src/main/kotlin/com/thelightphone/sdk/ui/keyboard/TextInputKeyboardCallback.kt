package com.thelightphone.sdk.ui.keyboard

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import com.thelightphone.lp3Keyboard.ui.Lp3RepeatableKeyboardCallback
import com.thelightphone.lp3Keyboard.ui.SpecialKey

internal class TextInputKeyboardCallback(
    private val state: TextFieldState,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) {
            state.edit { append(" ") }
        }
    }

    override fun onKeyReleased(code: Int) {
        appendCodePoint(code)
    }

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> deleteLast(surrogateAwareDeleteCount(state.text, 1))
            SpecialKey.Return -> state.edit { append("\n") }
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            deleteLast(deleteWordCount(state.text))
        }
    }

    override fun onKeyRepeated(code: Int) {
        appendCodePoint(code)
    }

    override fun onSpecialKeyRepeated(key: SpecialKey) {
        if (key == SpecialKey.Space) {
            state.edit { append(" ") }
        }
    }

    private fun appendCodePoint(code: Int) {
        state.edit { append(buildString { appendCodePoint(code) }) }
    }

    private fun deleteLast(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = length
            val start = (end - count).coerceAtLeast(0)
            if (start < end) delete(start, end)
        }
    }
}

private fun surrogateAwareDeleteCount(value: CharSequence, defaultCount: Int): Int {
    if (value.isEmpty()) return 0
    val last = value[value.length - 1]
    return if (Character.isLowSurrogate(last)) 2 else defaultCount
}

private fun deleteWordCount(value: CharSequence): Int {
    val trimmed = value.trimEnd()
    val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
    return value.length - if (lastSpace >= 0) lastSpace + 1 else 0
}
