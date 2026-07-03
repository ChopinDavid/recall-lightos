package com.dvdutch.recall.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore keys for Recall's persisted settings. The values are read/written
 * through `lightContext.dataStore` exactly as the SDK's weather example does
 * (`WeatherPreferences` + its `WeatherViewModel` usage) — that is the sanctioned
 * way a tool reaches DataStore through the SDK.
 *
 * Sync-era: the phone runs the Anki engine on-device and syncs directly against a
 * sync server (AnkiWeb or a self-hosted `anki.syncserver`), so the persisted
 * config is a sync endpoint + credentials, not a bridge URL/token.
 */
object RecallPreferences {
    /** Sync server endpoint. Emulator reaches a host-local hub at `http://10.0.2.2:<port>/`. */
    val SYNC_ENDPOINT = stringPreferencesKey("sync_endpoint")

    /** Sync account username. */
    val SYNC_USERNAME = stringPreferencesKey("sync_username")

    /** Sync account password. Stored in DataStore; masked in the UI. */
    val SYNC_PASSWORD = stringPreferencesKey("sync_password")

    /**
     * Durable "collections have diverged" latch. A [SyncController] sets this true when a
     * normal sync reports a FULL_* requirement and clears it on a successful full sync.
     * Unlike the controller's in-memory StateFlow (which dies with each fresh controller
     * instance a ViewModel builds), this pref outlives the controller and the process, so
     * Home can route to AttentionScreen even sessions/restarts after the divergence latched.
     */
    val NEEDS_ATTENTION = booleanPreferencesKey("needs_attention")

    /** Default sync endpoint when nothing has been persisted yet (host-local dev hub). */
    const val DEFAULT_SYNC_ENDPOINT = "http://10.0.2.2:18080/"
}
