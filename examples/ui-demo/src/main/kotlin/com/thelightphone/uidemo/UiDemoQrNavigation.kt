package com.thelightphone.uidemo

internal object UiDemoQrNavigation {
    var scannedValue: String? = null

    fun setResult(value: String) {
        scannedValue = value
    }

    fun consumeResult(): String? {
        val value = scannedValue
        scannedValue = null
        return value
    }

    fun clear() {
        scannedValue = null
    }
}
