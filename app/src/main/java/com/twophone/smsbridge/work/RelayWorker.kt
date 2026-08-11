package com.twophone.smsbridge.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.twophone.smsbridge.BridgeState
import com.twophone.smsbridge.Crypto
import com.twophone.smsbridge.FirebaseSession
import com.twophone.smsbridge.SmsPayload
import kotlinx.coroutines.tasks.await

class RelayWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (BridgeState.role(applicationContext) != BridgeState.ROLE_SOURCE) {
            return Result.success()
        }

        val pairId = BridgeState.pairId(applicationContext) ?: return Result.failure()
        val key = BridgeState.pairKey(applicationContext) ?: return Result.failure()
        val relayId = inputData.getString("relayId") ?: return Result.failure()
        val sender = inputData.getString("sender") ?: return Result.failure()
        val body = inputData.getString("body") ?: return Result.failure()
        val timestamp = inputData.getLong("timestamp", System.currentTimeMillis())

        return try {
            FirebaseSession.ensureSignedIn()
            val encrypted = Crypto.encrypt(
                key,
                SmsPayload(sender, body, timestamp)
            )
            FirebaseFunctions.getInstance()
                .getHttpsCallable("relaySms")
                .call(
                    mapOf(
                        "pairId" to pairId,
                        "relayId" to relayId,
                        "iv" to encrypted.iv,
                        "ciphertext" to encrypted.ciphertext,
                        "timestamp" to timestamp
                    )
                )
                .await()
            Result.success()
        } catch (e: FirebaseFunctionsException) {
            when (e.code) {
                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.FAILED_PRECONDITION,
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> Result.failure()
                else -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
