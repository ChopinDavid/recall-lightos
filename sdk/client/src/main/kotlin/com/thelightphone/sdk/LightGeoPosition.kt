package com.thelightphone.sdk

data class LightGeoPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val timestampMs: Long,
)
