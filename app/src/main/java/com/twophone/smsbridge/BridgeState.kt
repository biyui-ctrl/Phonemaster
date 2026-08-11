package com.twophone.smsbridge

import android.content.Context
import android.util.Base64

object BridgeState {
    private const val PREFS = "bridge_state"
    private const val ROLE = "role"
    private const val PAIR_ID = "pair_id"
    private const val WRAPPED_KEY = "wrapped_pair_key"
    private const val LAST_SEQ = "last_seq"

    const val ROLE_SOURCE = "source"
    const val ROLE_RECEIVER = "receiver"

    fun role(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ROLE, null)

    fun pairId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PAIR_ID, null)

    fun setPair(context: Context, role: String, pairId: String, rawKey: ByteArray) {
        val wrapped = KeyStoreBox.encrypt(rawKey)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ROLE, role)
            .putString(PAIR_ID, pairId)
            .putString(WRAPPED_KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putLong(LAST_SEQ, 0L)
            .apply()
    }

    fun pairKey(context: Context): ByteArray? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(WRAPPED_KEY, null) ?: return null
        return runCatching {
            KeyStoreBox.decrypt(Base64.decode(encoded, Base64.NO_WRAP))
        }.getOrNull()
    }

    fun lastSeq(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(LAST_SEQ, 0L)

    fun setLastSeq(context: Context, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(LAST_SEQ, value).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
