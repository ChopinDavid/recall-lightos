package com.thelightphone.weather

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class WeatherScreenMode {
    data object LocationInput : WeatherScreenMode()
    data object Loading : WeatherScreenMode()
    data object Settings : WeatherScreenMode()
    data class Weekly(
        val locationName: String,
        val days: List<WeeklyDay>,
        val selectedDay: WeatherDay,
    ) : WeatherScreenMode()

    data class Weather(
        val locationName: String,
        val forecast: StoredForecast,
        val selectedDay: WeatherDay = WeatherDay.Today,
    ) : WeatherScreenMode()
}

data class WeatherUiState(
    val mode: WeatherScreenMode = WeatherScreenMode.Loading,
    val canCancelLocationInput: Boolean = false,
    val locationInputSession: Int = 0,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.Celsius,
    val errorModal: String? = null,
)

private enum class LocationInputSource {
    Initial,
    Settings,
}

class WeatherViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel() {
    private val api = WeatherApi()
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private var lastSelectedDay: WeatherDay = WeatherDay.Today
    private var savedLocationQuery: String = ""
    private var locationInputSource: LocationInputSource = LocationInputSource.Initial
    private var screenBeforeSettings: WeatherScreenMode? = null

    private fun WeatherUiState.openLocationInput(
        canCancel: Boolean = canCancelLocationInput,
    ): WeatherUiState = copy(
        mode = WeatherScreenMode.LocationInput,
        canCancelLocationInput = canCancel,
        locationInputSession = locationInputSession + 1,
        errorModal = null,
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadStoredState()
        }
    }

    private suspend fun loadStoredState() {
        val prefs = dataStore.data.first()
        val unit = temperatureUnitFromStorage(prefs[WeatherPreferences.TEMPERATURE_UNIT])
        val query = prefs[WeatherPreferences.LOCATION_QUERY]
        val locationName = prefs[WeatherPreferences.LOCATION_NAME]
        val lat = prefs[WeatherPreferences.LATITUDE]?.toDoubleOrNull()
        val lon = prefs[WeatherPreferences.LONGITUDE]?.toDoubleOrNull()
        val forecastJson = prefs[WeatherPreferences.FORECAST_JSON]

        _uiState.update { it.copy(temperatureUnit = unit) }

        if (query == null || locationName == null || lat == null || lon == null) {
            _uiState.update { it.copy(temperatureUnit = unit).openLocationInput(canCancel = false) }
            return
        }

        val cachedForecast = forecastJson?.let { runCatching { json.decodeFromString<StoredForecast>(it) }.getOrNull() }
        savedLocationQuery = query
        if (cachedForecast != null && cachedForecast.weekly.isNotEmpty()) {
            _uiState.value = WeatherUiState(
                mode = WeatherScreenMode.Weather(
                    locationName = locationName,
                    forecast = cachedForecast,
                ),
                canCancelLocationInput = true,
                temperatureUnit = unit,
            )
        } else {
            _uiState.update {
                it.copy(mode = WeatherScreenMode.Loading, temperatureUnit = unit)
            }
        }

        val needsRefresh = cachedForecast == null || cachedForecast.weekly.isEmpty()
        refreshForecast(query, locationName, lat, lon, showLoadingScreen = needsRefresh)
    }

    fun submitLocation(rawQuery: CharSequence) {
        val query = rawQuery.toString().trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(errorModal = "Please enter a location.") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    mode = WeatherScreenMode.Loading,
                    errorModal = null,
                )
            }

            val geoResult = api.resolveLocation(query)
            geoResult.fold(
                onSuccess = { geo ->
                    val displayName = geo.displayName()
                    refreshForecast(
                        query = query,
                        locationName = displayName,
                        latitude = geo.latitude,
                        longitude = geo.longitude,
                        showLoadingScreen = true,
                    )
                },
                onFailure = { error ->
                    val message = when (error) {
                        is LocationNotFoundException -> "Location not found."
                        else -> error.message ?: "Could not load weather."
                    }
                    when {
                        locationInputSource == LocationInputSource.Settings -> {
                            _uiState.update {
                                it.copy(mode = WeatherScreenMode.Settings, errorModal = message)
                            }
                        }
                        _uiState.value.canCancelLocationInput -> restoreSavedWeather(message)
                        else -> {
                            _uiState.update {
                                it.openLocationInput(canCancel = false).copy(errorModal = message)
                            }
                        }
                    }
                },
            )
        }
    }

    private suspend fun refreshForecast(
        query: String,
        locationName: String,
        latitude: Double,
        longitude: Double,
        showLoadingScreen: Boolean,
    ) {
        if (showLoadingScreen) {
            _uiState.update { it.copy(mode = WeatherScreenMode.Loading) }
        }

        val forecastResult = api.fetchForecast(latitude, longitude)
        forecastResult.fold(
            onSuccess = { forecast ->
                dataStore.edit { prefs ->
                    prefs[WeatherPreferences.LOCATION_QUERY] = query
                    prefs[WeatherPreferences.LOCATION_NAME] = locationName
                    prefs[WeatherPreferences.LATITUDE] = latitude.toString()
                    prefs[WeatherPreferences.LONGITUDE] = longitude.toString()
                    prefs[WeatherPreferences.FORECAST_JSON] = json.encodeToString(forecast)
                }
                savedLocationQuery = query
                locationInputSource = LocationInputSource.Initial
                _uiState.update { state ->
                    state.copy(
                        mode = WeatherScreenMode.Weather(
                            locationName = locationName,
                            forecast = forecast,
                            selectedDay = lastSelectedDay,
                        ),
                        canCancelLocationInput = true,
                    )
                }
            },
            onFailure = { error ->
                val message = error.message ?: "Could not load weather."
                if (_uiState.value.canCancelLocationInput && locationInputSource != LocationInputSource.Settings) {
                    restoreSavedWeather(message)
                } else if (locationInputSource == LocationInputSource.Settings) {
                    _uiState.update {
                        it.copy(mode = WeatherScreenMode.Settings, errorModal = message)
                    }
                } else {
                    _uiState.update {
                        it.openLocationInput(canCancel = false).copy(errorModal = message)
                    }
                }
            },
        )
    }

    fun toggleDay() {
        _uiState.update { state ->
            val mode = state.mode as? WeatherScreenMode.Weather ?: return@update state
            val nextDay = when (mode.selectedDay) {
                WeatherDay.Today -> WeatherDay.Tomorrow
                WeatherDay.Tomorrow -> WeatherDay.Today
            }
            lastSelectedDay = nextDay
            state.copy(mode = mode.copy(selectedDay = nextDay))
        }
    }

    fun openSettings() {
        val current = _uiState.value.mode
        if (current !is WeatherScreenMode.Settings) {
            screenBeforeSettings = current
        }
        _uiState.update { it.copy(mode = WeatherScreenMode.Settings, errorModal = null) }
    }

    fun closeSettings() {
        val previous = screenBeforeSettings
        screenBeforeSettings = null
        if (previous != null && previous !is WeatherScreenMode.Settings) {
            _uiState.update { it.copy(mode = previous, errorModal = null) }
        } else {
            restoreWeatherScreen()
        }
    }

    fun openWeekly() {
        _uiState.update { state ->
            val weather = state.mode as? WeatherScreenMode.Weather ?: return@update state
            if (weather.forecast.weekly.isEmpty()) return@update state
            state.copy(
                mode = WeatherScreenMode.Weekly(
                    locationName = weather.locationName,
                    days = weather.forecast.weekly,
                    selectedDay = weather.selectedDay,
                ),
            )
        }
    }

    fun closeWeekly() {
        restoreWeatherScreen()
    }

    fun openLocationFromSettings() {
        locationInputSource = LocationInputSource.Settings
        _uiState.update { it.openLocationInput(canCancel = true) }
    }

    fun toggleTemperatureUnit() {
        val next = _uiState.value.temperatureUnit.toggle()
        _uiState.update { it.copy(temperatureUnit = next) }
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[WeatherPreferences.TEMPERATURE_UNIT] = next.storageValue()
            }
        }
    }

    fun cancelLocationInput() {
        if (!_uiState.value.canCancelLocationInput) return
        when (locationInputSource) {
            LocationInputSource.Settings -> {
                locationInputSource = LocationInputSource.Initial
                _uiState.update { it.copy(mode = WeatherScreenMode.Settings) }
            }
            LocationInputSource.Initial -> {
                viewModelScope.launch(Dispatchers.IO) { restoreSavedWeather() }
            }
        }
    }

    private fun restoreWeatherScreen() {
        val state = _uiState.value
        val weather = state.mode as? WeatherScreenMode.Weather
        if (weather != null) return
        val weekly = state.mode as? WeatherScreenMode.Weekly
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            val locationName = prefs[WeatherPreferences.LOCATION_NAME] ?: return@launch
            val forecastJson = prefs[WeatherPreferences.FORECAST_JSON] ?: return@launch
            val forecast = runCatching { json.decodeFromString<StoredForecast>(forecastJson) }.getOrNull()
                ?: return@launch
            _uiState.update {
                it.copy(
                    mode = WeatherScreenMode.Weather(
                        locationName = locationName,
                        forecast = forecast,
                        selectedDay = weekly?.selectedDay ?: lastSelectedDay,
                    ),
                )
            }
        }
    }

    private suspend fun restoreSavedWeather(errorMessage: String? = null) {
        val prefs = dataStore.data.first()
        val locationName = prefs[WeatherPreferences.LOCATION_NAME]
        val forecastJson = prefs[WeatherPreferences.FORECAST_JSON]
        val query = prefs[WeatherPreferences.LOCATION_QUERY]
        if (locationName == null || forecastJson == null || query == null) {
            _uiState.update {
                it.openLocationInput(canCancel = false).copy(errorModal = errorMessage)
            }
            return
        }
        val forecast = runCatching { json.decodeFromString<StoredForecast>(forecastJson) }.getOrNull()
        if (forecast == null) {
            _uiState.update {
                it.openLocationInput(canCancel = false).copy(errorModal = errorMessage)
            }
            return
        }
        savedLocationQuery = query
        locationInputSource = LocationInputSource.Initial
        _uiState.value = WeatherUiState(
            mode = WeatherScreenMode.Weather(
                locationName = locationName,
                forecast = forecast,
                selectedDay = lastSelectedDay,
            ),
            canCancelLocationInput = true,
            temperatureUnit = _uiState.value.temperatureUnit,
            errorModal = errorMessage,
        )
    }

    fun dismissError() {
        _uiState.update { it.copy(errorModal = null) }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}
