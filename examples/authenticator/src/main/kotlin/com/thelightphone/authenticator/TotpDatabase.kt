package com.thelightphone.authenticator

import android.database.sqlite.SQLiteDatabase
import java.io.File

internal class TotpDatabase(
    databaseFile: File,
) {
    private val database: SQLiteDatabase by lazy {
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).also { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS totp_accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    issuer TEXT NOT NULL,
                    label TEXT NOT NULL,
                    digits INTEGER NOT NULL,
                    period INTEGER NOT NULL,
                    algorithm TEXT NOT NULL,
                    encrypted_secret BLOB NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    fun upsertAccount(
        issuer: String,
        label: String,
        digits: Int,
        period: Int,
        algorithm: String,
        encryptedSecret: ByteArray,
    ): Long {
        val values = android.content.ContentValues().apply {
            put("issuer", issuer)
            put("label", label)
            put("digits", digits)
            put("period", period)
            put("algorithm", algorithm)
            put("encrypted_secret", encryptedSecret)
        }

        val existingId = findAccountId(issuer, label)
        if (existingId != null) {
            val updated = database.update(TABLE_NAME, values, "id = ?", arrayOf(existingId.toString()))
            if (updated == 0) error("Failed to update TOTP account")
            return existingId
        }

        database.insert(TABLE_NAME, null, values).let { rowId ->
            if (rowId == -1L) error("Failed to insert TOTP account")
            return rowId
        }
    }

    private fun findAccountId(issuer: String, label: String): Long? {
        database.query(
            TABLE_NAME,
            arrayOf("id"),
            "issuer = ? COLLATE NOCASE AND label = ? COLLATE NOCASE",
            arrayOf(issuer, label),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(cursor.getColumnIndexOrThrow("id"))
        }
    }

    fun getAccount(id: Long): StoredAccount? {
        database.query(
            TABLE_NAME,
            arrayOf("id", "issuer", "label", "digits", "period", "algorithm"),
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return StoredAccount(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                issuer = cursor.getString(cursor.getColumnIndexOrThrow("issuer")),
                label = cursor.getString(cursor.getColumnIndexOrThrow("label")),
                digits = cursor.getInt(cursor.getColumnIndexOrThrow("digits")),
                period = cursor.getInt(cursor.getColumnIndexOrThrow("period")),
                algorithm = cursor.getString(cursor.getColumnIndexOrThrow("algorithm")),
            )
        }
    }

    fun listAccounts(): List<StoredAccount> {
        database.query(
            TABLE_NAME,
            arrayOf("id", "issuer", "label", "digits", "period", "algorithm"),
            null,
            null,
            null,
            null,
            "issuer COLLATE NOCASE, label COLLATE NOCASE",
        ).use { cursor ->
            val accounts = ArrayList<StoredAccount>(cursor.count)
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val issuerIndex = cursor.getColumnIndexOrThrow("issuer")
            val labelIndex = cursor.getColumnIndexOrThrow("label")
            val digitsIndex = cursor.getColumnIndexOrThrow("digits")
            val periodIndex = cursor.getColumnIndexOrThrow("period")
            val algorithmIndex = cursor.getColumnIndexOrThrow("algorithm")

            while (cursor.moveToNext()) {
                accounts += StoredAccount(
                    id = cursor.getLong(idIndex),
                    issuer = cursor.getString(issuerIndex),
                    label = cursor.getString(labelIndex),
                    digits = cursor.getInt(digitsIndex),
                    period = cursor.getInt(periodIndex),
                    algorithm = cursor.getString(algorithmIndex),
                )
            }
            return accounts
        }
    }

    fun deleteAccount(id: Long): Boolean {
        val deleted = database.delete(TABLE_NAME, "id = ?", arrayOf(id.toString()))
        return deleted > 0
    }

    fun getEncryptedSecret(id: Long): ByteArray? {
        database.query(
            TABLE_NAME,
            arrayOf("encrypted_secret"),
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getBlob(cursor.getColumnIndexOrThrow("encrypted_secret"))
        }
    }

    companion object {
        private const val TABLE_NAME = "totp_accounts"
    }
}
