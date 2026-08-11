package com.twophone.smsbridge.push

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.functions.FirebaseFunctions
import com.twophone.smsbridge.BridgeState
import com.twophone.smsbridge.FirebaseSession
import com.twophone.smsbridge.MessageStore
import com.twophone.smsbridge.work.HistorySyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BridgeMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (BridgeState.role(this) != BridgeState.ROLE_RECEIVER) return
        val pairId = BridgeState.pairId(this) ?: return
        if (message.data["pairId"] != pairId) return

        when (message.data["type"]) {
            "pair_revoked" -> {
                MessageStore(this).clearAll()
                BridgeState.clear(this)
                WorkManager.getInstance(this).cancelUniqueWork("receiver-history-sync")
                return
            }
            "relayed_sms" -> {
                val request = OneTimeWorkRequestBuilder<HistorySyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(this).enqueue(request)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (BridgeState.role(this) != BridgeState.ROLE_RECEIVER) return
        val pairId = BridgeState.pairId(this) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                FirebaseSession.ensureSignedIn()
                FirebaseFunctions.getInstance()
                    .getHttpsCallable("registerReceiverToken")
                    .call(mapOf("pairId" to pairId, "token" to token))
                    .await()
            }
        }
    }
}
