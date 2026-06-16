package com.thelightphone.weather

import kotlinx.serialization.Serializable

@Serializable
data class CurrentConditions(
    val tempC: Double,
    val apparentTempC: Double? = null,
    val weatherCode: Int,
) {
    val weatherDescription: String get() = wmoWeatherDescription(weatherCode)
}

@Serializable
data class DayForecast(
    val date: String,
    val tempMaxC: Double,
    val tempMinC: Double,
    val apparentTempMaxC: Double,
    val apparentTempMinC: Double,
    val precipitationMm: Double,
    val precipitationProbabilityMax: Int?,
    val weatherCode: Int,
    val windSpeedMaxKmh: Double,
    val windDirectionDominant: Int,
    val uvIndexMax: Double,
    val sunrise: String,
    val sunset: String,
) {
    val weatherDescription: String get() = wmoWeatherDescription(weatherCode)
    val windCompass: String get() = degreesToCompass(windDirectionDominant)
}

@Serializable
data class HourlyForecast(
    val time: String,
    val tempC: Double,
    val apparentTempC: Double,
    val precipitationMm: Double,
    val precipitationProbability: Int? = null,
)

@Serializable
data class WeeklyDay(
    val date: String,
    val tempMaxC: Double,
    val tempMinC: Double,
    val precipitationMm: Double,
    val precipitationProbabilityMax: Int? = null,
    val weatherCode: Int = 0,
) {
    val weatherDescription: String get() = wmoWeatherDescription(weatherCode)
}

@Serializable
data class StoredForecast(
    val today: DayForecast,
    val tomorrow: DayForecast,
    val weekly: List<WeeklyDay> = emptyList(),
    val hourly: List<HourlyForecast> = emptyList(),
    val current: CurrentConditions? = null,
) {
    fun hoursForToday(): List<HourlyForecast> =
        hourly.filter { it.time.substringBefore('T') == today.date }
}

enum class WeatherDay {
    Today,
    Tomorrow,
}

internal fun wmoWeatherDescription(code: Int): String = when (code) {
    0 -> "Clear sky"
    1, 2, 3 -> "Mainly clear, partly cloudy, or overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61, 63, 65 -> "Rain"
    66, 67 -> "Freezing rain"
    71, 73, 75 -> "Snow"
    77 -> "Snow grains"
    80, 81, 82 -> "Rain showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm with hail"
    else -> "Weather code $code"
}

internal fun degreesToCompass(degrees: Int): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val index = ((degrees.toDouble() + 22.5) / 45.0).toInt() % 8
    return directions[index]
}
