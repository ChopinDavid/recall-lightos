package com.thelightphone.weather

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

@Serializable
internal data class GeocodingResponse(
    val results: List<GeocodingResult> = emptyList(),
)

@Serializable
internal data class GeocodingResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val admin1: String? = null,
)

@Serializable
internal data class OpenMeteoDailyResponse(
    val daily: OpenMeteoDaily? = null,
)

@Serializable
internal data class OpenMeteoDaily(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m_max") val temperature2mMax: List<Double> = emptyList(),
    @SerialName("temperature_2m_min") val temperature2mMin: List<Double> = emptyList(),
    @SerialName("apparent_temperature_max") val apparentTemperatureMax: List<Double> = emptyList(),
    @SerialName("apparent_temperature_min") val apparentTemperatureMin: List<Double> = emptyList(),
    @SerialName("precipitation_sum") val precipitationSum: List<Double> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?> = emptyList(),
    val weathercode: List<Int> = emptyList(),
    @SerialName("windspeed_10m_max") val windspeed10mMax: List<Double> = emptyList(),
    @SerialName("winddirection_10m_dominant") val winddirection10mDominant: List<Int> = emptyList(),
    @SerialName("uv_index_max") val uvIndexMax: List<Double> = emptyList(),
    val sunrise: List<String> = emptyList(),
    val sunset: List<String> = emptyList(),
)

internal class WeatherApi {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun resolveLocation(query: String): Result<GeocodingResult> {
        val encoded = URLEncoder.encode(query.trim(), UTF_8.name())
        val geo: GeocodingResponse = client
            .get("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1")
            .body()

        val first = geo.results.firstOrNull()
            ?: return Result.failure(LocationNotFoundException())

        return Result.success(first)
    }

    suspend fun fetchForecast(latitude: Double, longitude: Double): Result<StoredForecast> {
        val response: OpenMeteoDailyResponse = client
            .get(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$latitude&longitude=$longitude" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    ",apparent_temperature_max,apparent_temperature_min" +
                    ",precipitation_sum,precipitation_probability_max" +
                    ",weathercode,windspeed_10m_max,winddirection_10m_dominant" +
                    ",uv_index_max,sunrise,sunset" +
                    "&timezone=auto&forecast_days=7",
            )
            .body()

        val daily = response.daily
            ?: return Result.failure(IllegalStateException("No forecast data available."))

        if (daily.time.size < 2) {
            return Result.failure(IllegalStateException("Forecast did not include today and tomorrow."))
        }

        val today = daily.toDayForecast(index = 0)
        val tomorrow = daily.toDayForecast(index = 1)
        val weekly = daily.time.indices.map { index ->
            WeeklyDay(
                date = daily.time[index],
                tempMaxC = daily.temperature2mMax[index],
                tempMinC = daily.temperature2mMin[index],
                precipitationMm = daily.precipitationSum[index],
            )
        }
        return Result.success(StoredForecast(today = today, tomorrow = tomorrow, weekly = weekly))
    }

    fun close() {
        client.close()
    }
}

internal class LocationNotFoundException : Exception("Location not found.")

private fun OpenMeteoDaily.toDayForecast(index: Int): DayForecast {
    return DayForecast(
        date = time[index],
        tempMaxC = temperature2mMax[index],
        tempMinC = temperature2mMin[index],
        apparentTempMaxC = apparentTemperatureMax[index],
        apparentTempMinC = apparentTemperatureMin[index],
        precipitationMm = precipitationSum[index],
        precipitationProbabilityMax = precipitationProbabilityMax.getOrNull(index),
        weatherCode = weathercode[index],
        windSpeedMaxKmh = windspeed10mMax[index],
        windDirectionDominant = winddirection10mDominant[index],
        uvIndexMax = uvIndexMax[index],
        sunrise = sunrise[index],
        sunset = sunset[index],
    )
}

internal fun GeocodingResult.displayName(): String {
    val region = listOfNotNull(admin1, country).joinToString(", ")
    return if (region.isEmpty()) name else "$name, $region"
}
