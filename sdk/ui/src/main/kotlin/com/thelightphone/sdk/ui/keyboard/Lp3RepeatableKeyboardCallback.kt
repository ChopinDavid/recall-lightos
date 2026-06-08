package com.thelightphone.sdk.ui.keyboard

import com.thelightphone.lp3Keyboard.ui.Lp3KeyboardCallback
import com.thelightphone.lp3Keyboard.ui.SpecialKey

/** Keyboard callback including key-repeat events (LP3 backspace / space hold). */
interface Lp3RepeatableKeyboardCallback : Lp3KeyboardCallback {
    fun onKeyRepeated(code: Int)
    fun onSpecialKeyRepeated(specialKey: SpecialKey)
}
