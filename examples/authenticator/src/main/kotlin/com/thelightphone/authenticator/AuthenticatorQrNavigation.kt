package com.thelightphone.authenticator

internal object AuthenticatorQrNavigation {
    var storedAccount: StoredAccount? = null
    var parseError: String? = null

    fun setResult(account: StoredAccount) {
        storedAccount = account
        parseError = null
    }

    fun setError(message: String) {
        storedAccount = null
        parseError = message
    }

    fun consumeAccount(): StoredAccount? {
        val account = storedAccount
        storedAccount = null
        return account
    }

    fun consumeError(): String? {
        val error = parseError
        parseError = null
        return error
    }

    fun clear() {
        storedAccount = null
        parseError = null
    }
}
