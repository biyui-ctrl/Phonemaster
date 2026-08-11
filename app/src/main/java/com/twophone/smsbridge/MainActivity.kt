package com.twophone.smsbridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var oneTimeBundle: String? = null
    private var unlocked = false

    private val smsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        lifecycleScope.launch {
            runCatching { FirebaseSession.ensureSignedIn() }
            render()
        }
    }

    override fun onStop() {
        unlocked = false
        super.onStop()
    }

    private fun render() {
        root.removeAllViews()
        title("Phonemaster")
        text("Secure two-phone SMS relay for devices you explicitly pair.")
        when (BridgeState.role(this)) {
            BridgeState.ROLE_SOURCE -> renderSource()
            BridgeState.ROLE_RECEIVER -> renderReceiver()
            else -> renderRoleChoice()
        }
    }

    private fun renderRoleChoice() {
        title("Choose this phone")
        button("Phone A — SIM phone") { setupSource() }
        button("Phone B — receiving phone") { setupReceiverForm() }
        text("Android permission prompts remain visible and require your approval during setup.")
    }

    private fun setupSource() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            smsPermission.launch(Manifest.permission.RECEIVE_SMS)
            return
        }
        lifecycleScope.launch {
            try {
                FirebaseSession.ensureSignedIn()
                @Suppress("UNCHECKED_CAST")
                val data = Api.callable("startPair").call().await().data as Map<String, Any?>
                val pairId = data["pairId"] as String
                val joinCode = data["joinCode"] as String
                val key = Crypto.newPairKey()
                BridgeState.setPair(this@MainActivity, BridgeState.ROLE_SOURCE, pairId, key)
                oneTimeBundle = PairBundle(pairId, joinCode, Crypto.keyToText(key)).encode()
                render()
            } catch (e: Exception) {
                toast("Pair setup failed: ${e.message}")
            }
        }
    }

    private fun setupReceiverForm() {
        title("Pair Phone B")
        val input = EditText(this).apply {
            hint = "Paste one-time bundle from Phone A"
            minLines = 3
        }
        root.addView(input)
        button("Pair this phone") {
            val bundle = runCatching { PairBundle.decode(input.text.toString()) }.getOrNull()
            if (bundle == null) {
                toast("Invalid pairing bundle")
                return@button
            }
            lifecycleScope.launch {
                try {
                    FirebaseSession.ensureSignedIn()
                    Api.callable("joinPair")
                        .call(mapOf("pairId" to bundle.pairId, "joinCode" to bundle.joinCode))
                        .await()
                    BridgeState.setPair(
                        this@MainActivity,
                        BridgeState.ROLE_RECEIVER,
                        bundle.pairId,
                        Crypto.keyFromText(bundle.keyText)
                    )
                    registerReceiverToken()
                    scheduleHistorySync()
                    requestNotifications()
                    render()
                } catch (e: Exception) {
                    toast("Pairing failed: ${e.message}")
                }
            }
        }
    }

    private fun renderSource() {
        title("Phone A — SIM phone")
        text("Unattended relay is enabled after setup. Pair ID: ${BridgeState.pairId(this)}")
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        text("SMS permission: ${if (granted) "Granted" else "Not granted"}")
        if (!granted) button("Grant SMS permission") { smsPermission.launch(Manifest.permission.RECEIVE_SMS) }

        oneTimeBundle?.let { bundle ->
            title("One-time pairing secret")
            val view = EditText(this).apply {
                setText(bundle)
                keyListener = null
                setTextIsSelectable(true)
                minLines = 3
            }
            root.addView(view)
            button("Copy pairing bundle") {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Phonemaster pairing", bundle))
                toast("Copied. Paste directly into Phone B.")
            }
            button("Hide pairing secret") {
                oneTimeBundle = null
                render()
            }
        }
        button("Check Phone B status") { checkStatus() }
        button("Revoke pair and reset") { revokeAndReset() }
    }

    private fun renderReceiver() {
        title("Phone B — receiver")
        text("Background delivery is enabled. Stored message bodies remain encrypted until you unlock history.")
        requestNotifications()
        if (deviceCannotAuthenticate()) {
            text("WARNING: this device has no lock screen, so message history cannot be protected. Anyone who opens this app can read relayed messages.")
        }
        if (!unlocked) {
            button("Unlock message history") { authenticate() }
            button("Revoke pair and reset") { revokeAndReset() }
            return
        }
        button("Sync missed messages") { enqueueSync() }
        title("Relayed history")
        lifecycleScope.launch {
            val key = BridgeState.pairKey(this@MainActivity)
            val items = withContext(Dispatchers.IO) { MessageStore(this@MainActivity).latest(100) }
            if (key == null || items.isEmpty()) {
                text(if (key == null) "Encryption key unavailable." else "No messages yet.")
            } else {
                val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                items.forEach { item ->
                    val sms = runCatching {
                        Crypto.decrypt(key, EncryptedPayload(item.iv, item.ciphertext))
                    }.getOrNull()
                    if (sms != null) text("${sms.sender}\n${sms.body}\n${df.format(Date(sms.timestamp))}")
                }
            }
        }
        button("Lock history") { unlocked = false; render() }
        button("Revoke pair and reset") { revokeAndReset() }
    }

    /**
     * True when the device can never satisfy an authentication prompt: no
     * biometric hardware, no enrolled credential, or an unsupported platform.
     * Deliberately excludes transient states such as HW_UNAVAILABLE, where a
     * lock does exist and the prompt should still be required.
     */
    private fun deviceCannotAuthenticate(): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return when (BiometricManager.from(this).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> true
            else -> false
        }
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val status = BiometricManager.from(this).canAuthenticate(authenticators)

        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            // Some devices (desktop Android runtimes, images without a lock
            // screen) can never show a credential prompt. There is no lock to
            // derive protection from, so history is shown unprotected and the
            // user is told plainly. Transient failures still refuse.
            if (deviceCannotAuthenticate()) {
                unlocked = true
                toast("No device lock on this device - history is NOT protected.")
                render()
            } else {
                toast("Set a secure device lock first.")
            }
            return
        }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlocked = true
                render()
            }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Phonemaster")
                .setSubtitle("Decrypt relayed SMS history")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private suspend fun registerReceiverToken() {
        val pairId = BridgeState.pairId(this) ?: return
        val token = FirebaseMessaging.getInstance().token.await()
        Api.callable("registerReceiverToken")
            .call(mapOf("pairId" to pairId, "token" to token)).await()
    }

    private fun scheduleHistorySync() {
        val work = PeriodicWorkRequestBuilder<com.twophone.smsbridge.work.HistorySyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "receiver-history-sync", ExistingPeriodicWorkPolicy.UPDATE, work
        )
    }

    private fun enqueueSync() {
        val work = OneTimeWorkRequestBuilder<com.twophone.smsbridge.work.HistorySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueue(work)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun checkStatus() {
        val pairId = BridgeState.pairId(this) ?: return
        lifecycleScope.launch {
            try {
                @Suppress("UNCHECKED_CAST")
                val data = Api.callable("pairStatus")
                    .call(mapOf("pairId" to pairId)).await().data as Map<String, Any?>
                toast(if (data["joined"] == true) "Phone B is paired." else "Waiting for Phone B.")
            } catch (e: Exception) {
                toast("Status check failed: ${e.message}")
            }
        }
    }

    private fun revokeAndReset() {
        val pairId = BridgeState.pairId(this)
        lifecycleScope.launch {
            if (pairId != null) runCatching {
                Api.callable("revokePair").call(mapOf("pairId" to pairId)).await()
            }
            MessageStore(this@MainActivity).clearAll()
            BridgeState.clear(this@MainActivity)
            WorkManager.getInstance(this@MainActivity).cancelUniqueWork("receiver-history-sync")
            oneTimeBundle = null
            unlocked = false
            render()
        }
    }

    private fun title(value: String) {
        root.addView(TextView(this).apply { text = value; textSize = 22f; setPadding(0, 24, 0, 12) })
    }
    private fun text(value: String) {
        root.addView(TextView(this).apply { text = value; textSize = 16f; setPadding(0, 6, 0, 14) })
    }
    private fun button(value: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = value; setOnClickListener { action() } })
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
