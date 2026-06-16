package com.thelightphone.weather

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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

fun TemperatureUnit.displayLabel(): String = when (this) {
    TemperatureUnit.Celsius -> "Metric"
    TemperatureUnit.Fahrenheit -> "Imperial"
}

fun shortLocationName(fullName: String): String = fullName.substringBefore(',').trim()

fun displayTemperatureC(day: DayForecast, current: CurrentConditions?, unit: TemperatureUnit): String {
    val celsius = current?.tempC ?: day.tempMaxC
    return formatDegrees(celsius, unit)
}

fun displayWeatherDescription(day: DayForecast, current: CurrentConditions?): String {
    return current?.weatherDescription ?: day.weatherDescription
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

fun formatDegrees(celsius: Double, unit: TemperatureUnit): String {
    val value = when (unit) {
        TemperatureUnit.Celsius -> celsius
        TemperatureUnit.Fahrenheit -> celsius * 9.0 / 5.0 + 32.0
    }
    return "${value.roundToInt()}°"
}

fun formatHighLowLine(day: DayForecast, unit: TemperatureUnit): String {
    val high = formatDegrees(day.tempMaxC, unit)
    val low = formatDegrees(day.tempMinC, unit)
    val feelsHigh = formatDegrees(day.apparentTempMaxC, unit)
    val feelsLow = formatDegrees(day.apparentTempMinC, unit)
    return "$high / $low (feels like $feelsHigh / $feelsLow)"
}

fun formatPrecipitationDetail(day: DayForecast, unit: TemperatureUnit): String {
    val amount = formatRain(day.precipitationMm, unit)
    val probability = day.precipitationProbabilityMax
    return if (probability != null) "$amount ($probability%)" else amount
}

fun formatWindSpeed(kmh: Double, compass: String, unit: TemperatureUnit): String = when (unit) {
    TemperatureUnit.Fahrenheit -> "${(kmh * 0.621371).roundToInt()} mph $compass"
    TemperatureUnit.Celsius -> "${kmh.roundToInt()} km/h $compass"
}

fun formatTimeAmPm(iso: String): String {
    return try {
        val timePart = iso.substringAfter('T')
        val localTime = LocalTime.parse(timePart.take(5))
        localTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
    } catch (_: Exception) {
        iso.substringAfter('T', iso).take(5)
    }
}

fun formatUvIndex(value: Double): String = value.round1()

fun formatRain(mm: Double, unit: TemperatureUnit): String = when (unit) {
    TemperatureUnit.Celsius -> "${mm.round1()} mm"
    TemperatureUnit.Fahrenheit -> "${(mm / 25.4).round1()} in"
}

fun formatWeeklySummaryLine(day: WeeklyDay, unit: TemperatureUnit): String {
    val temps = "${formatDegrees(day.tempMaxC, unit)} / ${formatDegrees(day.tempMinC, unit)}"
    val rain = formatRain(day.precipitationMm, unit)
    val probability = day.precipitationProbabilityMax
    val rainPart = if (probability != null) "Rain: $rain ($probability%)" else "Rain: $rain"
    return "$temps - $rainPart"
}

fun formatHourLabel(isoDateTime: String): String {
    return try {
        val time = LocalTime.parse(isoDateTime.substringAfter('T').take(5))
        time.format(DateTimeFormatter.ofPattern("ha", Locale.US)).uppercase(Locale.US)
    } catch (_: Exception) {
        isoDateTime.substringAfter('T').take(5)
    }
}

fun formatHourlyTempLine(hour: HourlyForecast, unit: TemperatureUnit): String {
    val temp = formatDegrees(hour.tempC, unit)
    val feels = formatDegrees(hour.apparentTempC, unit)
    return "$temp (feels like $feels)"
}

fun formatHourlyRainLine(hour: HourlyForecast, unit: TemperatureUnit): String {
    val rain = formatRain(hour.precipitationMm, unit)
    val probability = hour.precipitationProbability
    return if (probability != null) "Rain: $rain ($probability%)" else "Rain: $rain"
}

fun formatDailyTitle(isoDate: String): String {
    return try {
        val date = LocalDate.parse(isoDate)
        val weekday = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "Mon"
            DayOfWeek.TUESDAY -> "Tues"
            DayOfWeek.WEDNESDAY -> "Weds"
            DayOfWeek.THURSDAY -> "Thurs"
            DayOfWeek.FRIDAY -> "Fri"
            DayOfWeek.SATURDAY -> "Sat"
            DayOfWeek.SUNDAY -> "Sun"
        }
        val month = date.month.getDisplayName(TextStyle.FULL, Locale.US)
        "$weekday $month ${date.dayOfMonth}"
    } catch (_: Exception) {
        isoDate
    }
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
