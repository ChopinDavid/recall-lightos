package com.thelightphone.weather

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class TemperatureUnit {
    Celsius,
    Fahrenheit,
}

fun TemperatureUnit.toggle(): TemperatureUnit = when (this) {
    TemperatureUnit.Celsius -> TemperatureUnit.Fahrenheit
    TemperatureUnit.Fahrenheit -> TemperatureUnit.Celsius
}

fun TemperatureUnit.storageValue(): String = when (this) {
    TemperatureUnit.Celsius -> "C"
    TemperatureUnit.Fahrenheit -> "F"
}

fun temperatureUnitFromStorage(value: String?): TemperatureUnit = when (value) {
    "F" -> TemperatureUnit.Fahrenheit
    else -> TemperatureUnit.Celsius
}

fun formatTemperature(celsius: Double, unit: TemperatureUnit): String {
    val value = when (unit) {
        TemperatureUnit.Celsius -> celsius
        TemperatureUnit.Fahrenheit -> celsius * 9.0 / 5.0 + 32.0
    }
    val suffix = when (unit) {
        TemperatureUnit.Celsius -> "°C"
        TemperatureUnit.Fahrenheit -> "°F"
    }
    return "${value.round1()}$suffix"
}

fun formatRain(mm: Double, unit: TemperatureUnit): String = when (unit) {
    TemperatureUnit.Celsius -> "${mm.round1()} mm"
    TemperatureUnit.Fahrenheit -> "${(mm / 25.4).round1()} in"
}

fun formatWeeklyDayLabel(isoDate: String): String {
    return try {
        val date = LocalDate.parse(isoDate)
        val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val month = date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
        "$dayOfWeek $month ${date.dayOfMonth}"
    } catch (_: Exception) {
        isoDate
    }
}

private fun Double.round1(): String {
    val rounded = (this * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}
