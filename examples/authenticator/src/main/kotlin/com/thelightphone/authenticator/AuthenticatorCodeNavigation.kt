package com.thelightphone.authenticator

internal object AuthenticatorCodeNavigation {
    var accountId: Long? = null

    fun open(accountId: Long) {
        this.accountId = accountId
    }

    fun consumeAccountId(): Long? {
        val id = accountId
        accountId = null
        return id
    }

    fun clear() {
        accountId = null
    }
}
