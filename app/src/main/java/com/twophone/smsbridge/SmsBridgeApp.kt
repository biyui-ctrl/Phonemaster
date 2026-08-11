package com.twophone.smsbridge

import android.app.Application
import com.google.firebase.FirebaseApp

class SmsBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        AppCheckInstaller.install()
        NotificationHelper.createChannel(this)
    }
}
