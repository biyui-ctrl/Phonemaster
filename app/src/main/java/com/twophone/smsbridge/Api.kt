package com.twophone.smsbridge

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import java.net.URL

/**
 * Endpoints are self-hosted rather than deployed as Firebase Cloud Functions,
 * but they speak the callable protocol. Going through FirebaseFunctions keeps
 * the Firebase Auth ID token and App Check token attached automatically.
 */
object Api {
    private val functions: FirebaseFunctions by lazy { FirebaseFunctions.getInstance() }

    fun callable(name: String): HttpsCallableReference {
        check(BuildConfig.API_BASE_URL.isNotBlank()) {
            "API_BASE_URL was not set at build time. Pass -PphonemasterApiBaseUrl=https://<host>/api."
        }
        return functions.getHttpsCallableFromUrl(URL("${BuildConfig.API_BASE_URL}/$name"))
    }
}
