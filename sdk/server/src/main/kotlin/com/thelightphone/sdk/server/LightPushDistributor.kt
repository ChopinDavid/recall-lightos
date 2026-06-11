package com.thelightphone.sdk.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LightPushDistributor : BroadcastReceiver() {

    companion object {
        private const val TAG = "LightPushDistributor"

        private const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
        private const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
        private const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"

        private const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
        private const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
        private const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
        private const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun sendMessage(context: Context, packageName: String, token: String, message: ByteArray) {
            val intent = Intent(ACTION_MESSAGE).apply {
                setPackage(packageName)
                putExtra("token", token)
                putExtra("bytesMessage", message)
            }
            context.sendBroadcast(intent)
        }

        fun sendLocalMessage(context: Context, packageName: String, message: ByteArray) {
            scope.launch {
                val registration = LightPushRegistry.findLocal(context, packageName) ?: run {
                    Log.w(TAG, "No local channel registration for $packageName")
                    return@launch
                }
                sendMessage(context, packageName, registration.token, message)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return

        when (intent.action) {
            ACTION_REGISTER -> runAsync { handleRegister(context, intent) }
            ACTION_UNREGISTER -> runAsync { handleUnregister(context, intent) }
            ACTION_MESSAGE_ACK -> { /* no-op for now */ }
            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "Async receiver work failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleRegister(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token")
        val callerPackage = getCallerPackage(intent)

        if (token == null) {
            Log.e(TAG, "Registration missing token")
            sendRegistrationFailed(context, callerPackage, token)
            return
        }

        if (callerPackage == null) {
            Log.e(TAG, "Could not determine caller package")
            return
        }

        val channel = intent.getStringExtra("message")
        val vapid = intent.getStringExtra("vapid")

        val endpoint = runCatching {
            LightSdkServer.pushEndpointFetcher.invoke(callerPackage, token, vapid)
        }.getOrNull()

        if (endpoint == null) {
            Log.e(TAG, "Failed to fetch endpoint for $callerPackage token=$token")
            sendRegistrationFailed(context, callerPackage, token)
            return
        }

        LightPushRegistry.register(context, token, callerPackage, endpoint, channel, vapid)
        Log.i(TAG, "Registered $callerPackage with token $token (channel=$channel)")

        val response = Intent(ACTION_NEW_ENDPOINT).apply {
            setPackage(callerPackage)
            putExtra("token", token)
            putExtra("endpoint", endpoint)
        }
        context.sendBroadcast(response)
    }

    private fun sendRegistrationFailed(context: Context, packageName: String?, token: String?) {
        if (packageName == null) return
        val response = Intent(ACTION_REGISTRATION_FAILED).apply {
            setPackage(packageName)
            if (token != null) putExtra("token", token)
        }
        context.sendBroadcast(response)
    }

    private suspend fun handleUnregister(context: Context, intent: Intent) {
        val token = intent.getStringExtra("token")
        if (token == null) {
            Log.e(TAG, "Unregistration missing token")
            return
        }

        val registration = LightPushRegistry.remove(context, token)
        val callerPackage = registration?.packageName ?: getCallerPackage(intent) ?: return

        Log.i(TAG, "Unregistered $callerPackage with token $token")

        val response = Intent(ACTION_UNREGISTERED).apply {
            setPackage(callerPackage)
            putExtra("token", token)
        }
        context.sendBroadcast(response)
    }

    private fun getCallerPackage(intent: Intent): String? {
        // SDK >= 34 can use getSentFromPackage(), fall back to PendingIntent for older
        return intent.getParcelableExtra("pi", android.app.PendingIntent::class.java)?.creatorPackage
    }
}
