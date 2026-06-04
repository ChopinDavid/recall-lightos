package com.thelightphone.sdk.ui.keyboard

import com.thelightphone.lp3Keyboard.ui.SpecialKey

internal class TextInputKeyboardCallback(
    private val currentValue: () -> String,
    private val onValueChange: (String) -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
        if (key == SpecialKey.Space) {
            append(" ")
        }
    }

    override fun onKeyReleased(code: Int) {
        appendCodePoint(code)
    }

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> deleteChars(surrogateAwareDeleteCount(currentValue(), 1))
            SpecialKey.Return -> append("\n")
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            deleteChars(deleteWordCount(currentValue()))
        }
    }

    override fun onKeyRepeated(code: Int) {
        appendCodePoint(code)
    }

    override fun onSpecialKeyRepeated(key: SpecialKey) {
        if (key == SpecialKey.Space) {
            append(" ")
        }
    }

    private fun append(text: String) {
        onValueChange(currentValue() + text)
    }

    private fun appendCodePoint(code: Int) {
        append(buildString { appendCodePoint(code) })
    }

    private fun deleteChars(count: Int) {
        if (count <= 0) return
        val value = currentValue()
        if (value.isEmpty()) return
        onValueChange(value.dropLast(count.coerceAtMost(value.length)))
    }
}

private fun surrogateAwareDeleteCount(value: String, defaultCount: Int): Int {
    if (value.isEmpty()) return 0
    val last = value[value.length - 1]
    return if (Character.isLowSurrogate(last)) 2 else defaultCount
}

private fun deleteWordCount(value: String): Int {
    val trimmed = value.trimEnd()
    val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
    return value.length - if (lastSpace >= 0) lastSpace + 1 else 0
}
