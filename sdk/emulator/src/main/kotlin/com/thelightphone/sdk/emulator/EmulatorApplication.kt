package com.thelightphone.sdk.emulator

import android.app.Application
import android.util.Log
import com.thelightphone.sdk.emulator.http.EmulatorHttpServer
import com.thelightphone.sdk.server.ClientCertType
import com.thelightphone.sdk.server.LightSdkServer
import com.thelightphone.sdk.shared.LightResult

class EmulatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val mollysocketUriString = BuildConfig.MOLLYSOCKET_URI
        LightSdkServer.customServiceMethodResolver = { callingId, methodId, payload ->
            if (methodId == "GetMollySocketUri" && mollysocketUriString.isNotEmpty()) {
                val json = "{\"mollySocketUri\":\"$mollysocketUriString\"}"
                LightResult.Success(json)
            } else {
                LightResult.Error(LightResult.ErrorCode.Unknown)
            }
        }
        val pushDomain = BuildConfig.PUSH_DOMAIN.ifEmpty { "http://localhost:8090" }
        LightSdkServer.pushEndpointFetcher = { callingPackage, token, vapid ->
            Log.d("LightEmulator", "getting push endpoint for token: $token, vapid: $vapid")
            "$pushDomain/push/$token"
        }

        LightSdkServer.checkCert = {
            ClientCertType.LightSdkApproved
        }

        EmulatorHttpServer(this).start()
    }
}
