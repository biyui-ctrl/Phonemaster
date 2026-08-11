package com.twophone.smsbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class StoredEncryptedMessage(
    val id: Long,
    val messageId: String,
    val iv: String,
    val ciphertext: String,
    val smsTimestamp: Long,
    val seq: Long
)

class MessageStore(context: Context) :
    SQLiteOpenHelper(context, "sms_bridge.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT UNIQUE NOT NULL,
                iv TEXT NOT NULL,
                ciphertext TEXT NOT NULL,
                sms_ts INTEGER NOT NULL,
                seq INTEGER NOT NULL UNIQUE
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        onCreate(db)
    }

    fun insert(
        messageId: String,
        payload: EncryptedPayload,
        smsTimestamp: Long,
        seq: Long
    ): Boolean {
        val values = ContentValues().apply {
            put("message_id", messageId)
            put("iv", payload.iv)
            put("ciphertext", payload.ciphertext)
            put("sms_ts", smsTimestamp)
            put("seq", seq)
        }
        return writableDatabase.insertWithOnConflict(
            "messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun latest(limit: Int = 100): List<StoredEncryptedMessage> {
        val out = mutableListOf<StoredEncryptedMessage>()
        readableDatabase.rawQuery(
            "SELECT id,message_id,iv,ciphertext,sms_ts,seq FROM messages ORDER BY seq DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += StoredEncryptedMessage(
                    id = c.getLong(0),
                    messageId = c.getString(1),
                    iv = c.getString(2),
                    ciphertext = c.getString(3),
                    smsTimestamp = c.getLong(4),
                    seq = c.getLong(5)
                )
            }
        }
        return out
    }

    fun clearAll() {
        writableDatabase.delete("messages", null, null)
    }
}
