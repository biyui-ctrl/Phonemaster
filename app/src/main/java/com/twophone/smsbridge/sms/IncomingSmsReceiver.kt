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

        // Every exit below is recorded. Silent early returns here were
        // indistinguishable from the broadcast never arriving at all.
        val role = BridgeState.role(context)
        if (role != BridgeState.ROLE_SOURCE) {
            BridgeState.noteSms(context, "received, but role is ${role ?: "unset"}")
            return
        }
        if (BridgeState.pairId(context) == null) {
            BridgeState.noteSms(context, "received, but no pair ID stored")
            return
        }
        if (BridgeState.pairKey(context) == null) {
            BridgeState.noteSms(context, "received, but pair key unreadable")
            return
        }

        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) {
            BridgeState.noteSms(context, "received, but no message parts")
            return
        }

        val sender = parts.first().displayOriginatingAddress ?: "Unknown"
        val body = parts.joinToString(separator = "") { it.displayMessageBody ?: "" }
        val timestamp = parts.first().timestampMillis

        BridgeState.noteSms(context, "received from $sender, relay queued")
        RelayScheduler.enqueue(context, sender, body, timestamp)
    }
}
