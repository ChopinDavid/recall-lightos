package com.thelightphone.authenticator

import java.io.File

class TotpAccountRepository private constructor(
    databaseFile: File,
    private val cipher: TotpSecretCipher,
) {
    private val database = TotpDatabase(databaseFile)

    fun addAccount(account: Account): StoredAccount {
        val encryptedSecret = cipher.encrypt(account.secret)
        val id = database.upsertAccount(
            issuer = account.issuer,
            label = account.label,
            digits = account.digits,
            period = account.period,
            algorithm = account.algorithm,
            encryptedSecret = encryptedSecret,
        )
        return StoredAccount(
            id = id,
            issuer = account.issuer,
            label = account.label,
            digits = account.digits,
            period = account.period,
            algorithm = account.algorithm,
        )
    }

    fun getAccount(id: Long): StoredAccount? = database.getAccount(id)

    fun listAccounts(): List<StoredAccount> = database.listAccounts()

    fun deleteAccount(id: Long): Boolean = database.deleteAccount(id)

    fun decryptSecret(id: Long): String? {
        val encrypted = database.getEncryptedSecret(id) ?: return null
        return cipher.decrypt(encrypted)
    }

    companion object {
        const val DATABASE_FILE_NAME = "totp_accounts.db"

        @Volatile
        private var instance: TotpAccountRepository? = null

        fun getInstance(databaseFile: File): TotpAccountRepository {
            return instance ?: synchronized(this) {
                instance ?: TotpAccountRepository(
                    databaseFile = databaseFile,
                    cipher = TotpSecretCipher(),
                ).also { instance = it }
            }
        }
    }
}
