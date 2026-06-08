package com.thelightphone.sdk.server

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.util.Log

enum class ClientFilterLevel {
    // LightOS will not show any tools other than the ones baked in by default
    ExcludeAllApks,

    // LightOS will show default tools as well as those curated from the community
    AllowLightApprovedApks,

    // LightOS will show default tools as well as any that have been built as SDK clients and signed using a Light SDK cert
    AllowLightSignedApks,

    // LightOS will show default tools as well as any other APKs that have been installed on the device.
    AllowAllApks
}

class LightSdkServerSettings(context: Context) {

    companion object {
        const val TAG = "LightSdkServerSettings"
        private const val CLIENT_FILTER_LEVEL = "client_filter_level"
    }

    private val preferences: SharedPreferences =
        context.getSharedPreferences("light_sdk_server", MODE_PRIVATE)

    var clientFilterLevel: ClientFilterLevel
        get() = preferences
            .getInt(CLIENT_FILTER_LEVEL, ClientFilterLevel.ExcludeAllApks.ordinal)
            .let { index ->
                ClientFilterLevel.entries.getOrElse(index) {
                    Log.e(TAG, "Invalid value for client filter level: $it")
                    ClientFilterLevel.ExcludeAllApks
                }
            }
        set(value) {
            preferences.edit().putInt(CLIENT_FILTER_LEVEL, value.ordinal).apply()
        }

}