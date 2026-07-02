package com.dvdutch.recall.prefs

import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore keys for Recall's persisted settings. The values are read/written
 * through `lightContext.dataStore` exactly as the SDK's weather example does
 * (`WeatherPreferences` + its `WeatherViewModel` usage) — that is the sanctioned
 * way a tool reaches DataStore through the SDK.
 */
object RecallPreferences {
    /** Base URL of the Anki bridge. Emulator reaches the host at 10.0.2.2. */
    val BRIDGE_URL = stringPreferencesKey("bridge_url")

    /** Bearer token sent to the bridge. */
    val BRIDGE_TOKEN = stringPreferencesKey("bridge_token")

    /** Default bridge URL when nothing has been persisted yet. */
    const val DEFAULT_BRIDGE_URL = "http://10.0.2.2:8000"
}
