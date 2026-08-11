package com.twophone.smsbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.twophone.smsbridge.BridgeState
import com.twophone.smsbridge.work.RelayScheduler

class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (BridgeState.role(context) != BridgeState.ROLE_SOURCE) return
        if (BridgeState.pairId(context) == null || BridgeState.pairKey(context) == null) return

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return

        val sender = parts.first().displayOriginatingAddress ?: "Unknown"
        val body = parts.joinToString(separator = "") { it.displayMessageBody ?: "" }
        val timestamp = parts.first().timestampMillis

        RelayScheduler.enqueue(context, sender, body, timestamp)
    }
}
