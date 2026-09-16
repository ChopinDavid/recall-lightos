package com.thelightphone.sdk

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

data class LightGeoPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timestampMs: Long,
)

fun SealedLightContext.hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        androidContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        androidContext,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}
