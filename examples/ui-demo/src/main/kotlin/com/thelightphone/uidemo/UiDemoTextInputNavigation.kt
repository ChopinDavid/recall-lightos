package com.thelightphone.uidemo

/**
 * Passes editor configuration between demo screens
 */
internal object UiDemoTextInputNavigation {
    data class EditorRequest(
        val title: String,
        val initialValue: String,
    )

    var request: EditorRequest? = null
    private var resultHandler: ((String) -> Unit)? = null

    fun openEditor(handler: (String) -> Unit, request: EditorRequest) {
        resultHandler = handler
        this.request = request
    }

    fun submitResult(value: String) {
        resultHandler?.invoke(value)
        request = null
        resultHandler = null
    }

    fun cancel() {
        request = null
        resultHandler = null
    }
}
