package com.twophone.smsbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    private const val CHANNEL = "sms_bridge_messages"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Relayed SMS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Encrypted messages relayed from your paired SIM phone"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun show(context: Context, messageId: String) {
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val publicVersion = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle("Phonemaster")
            .setContentText("New encrypted message")
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle("Phonemaster")
            .setContentText("New relayed SMS received. Unlock Phonemaster to read it.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(messageId.hashCode(), notification)
    }
}
