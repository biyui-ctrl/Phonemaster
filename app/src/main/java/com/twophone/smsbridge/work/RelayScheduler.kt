package com.twophone.smsbridge.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

object RelayScheduler {
    fun enqueue(
        context: Context,
        sender: String,
        body: String,
        timestamp: Long
    ) {
        val relayId = UUID.randomUUID().toString()
        val data = Data.Builder()
            .putString("relayId", relayId)
            .putString("sender", sender)
            .putString("body", body)
            .putLong("timestamp", timestamp)
            .build()

        val request = OneTimeWorkRequestBuilder<RelayWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}
