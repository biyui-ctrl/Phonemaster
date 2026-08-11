package com.twophone.smsbridge

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val iv: String,
    val ciphertext: String
)

data class SmsPayload(
    val sender: String,
    val body: String,
    val timestamp: Long
)

object Crypto {
    private val random = SecureRandom()

    fun newPairKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun keyToText(key: ByteArray): String =
        Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun keyFromText(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun encrypt(keyBytes: ByteArray, sms: SmsPayload): EncryptedPayload {
        val json = JSONObject()
            .put("sender", sms.sender)
            .put("body", sms.body)
            .put("timestamp", sms.timestamp)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, iv)
        )
        val ciphertext = cipher.doFinal(json)
        return EncryptedPayload(
            Base64.encodeToString(iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        )
    }

    fun decrypt(keyBytes: ByteArray, payload: EncryptedPayload): SmsPayload {
        val iv = Base64.decode(payload.iv, Base64.NO_WRAP)
        val ciphertext = Base64.decode(payload.ciphertext, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(128, iv)
        )
        val obj = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        return SmsPayload(
            sender = obj.getString("sender"),
            body = obj.getString("body"),
            timestamp = obj.getLong("timestamp")
        )
    }
}
