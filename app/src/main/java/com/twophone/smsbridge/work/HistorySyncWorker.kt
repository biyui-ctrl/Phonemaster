package com.twophone.smsbridge.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.functions.FirebaseFunctionsException
import com.twophone.smsbridge.*
import kotlinx.coroutines.tasks.await

class HistorySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (BridgeState.role(applicationContext) != BridgeState.ROLE_RECEIVER) {
            return Result.success()
        }
        val pairId = BridgeState.pairId(applicationContext) ?: return Result.failure()
        if (BridgeState.pairKey(applicationContext) == null) return Result.failure()

        return try {
            FirebaseSession.ensureSignedIn()
            val result = Api.callable("getMessages")
                .call(
                    mapOf(
                        "pairId" to pairId,
                        "afterSeq" to BridgeState.lastSeq(applicationContext)
                    )
                )
                .await()

            @Suppress("UNCHECKED_CAST")
            val root = result.data as? Map<String, Any?> ?: return Result.success()
            @Suppress("UNCHECKED_CAST")
            val messages = root["messages"] as? List<Map<String, Any?>> ?: emptyList()

            var latest = BridgeState.lastSeq(applicationContext)
            val cursorSeq = (root["cursorSeq"] as? Number)?.toLong() ?: latest
            val store = MessageStore(applicationContext)

            for (m in messages) {
                val id = m["messageId"] as? String ?: continue
                val iv = m["iv"] as? String ?: continue
                val ciphertext = m["ciphertext"] as? String ?: continue
                val smsTimestamp = (m["timestamp"] as? Number)?.toLong() ?: continue
                val seq = (m["seq"] as? Number)?.toLong() ?: continue

                val inserted = store.insert(
                    messageId = id,
                    payload = EncryptedPayload(iv, ciphertext),
                    smsTimestamp = smsTimestamp,
                    seq = seq
                )
                if (inserted) NotificationHelper.show(applicationContext, id)
                latest = maxOf(latest, seq)
            }

            BridgeState.setLastSeq(applicationContext, maxOf(latest, cursorSeq))
            Result.success()
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> Result.failure()
                else -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
