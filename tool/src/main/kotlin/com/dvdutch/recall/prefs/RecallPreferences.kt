package com.dvdutch.recall.prefs

import com.dvdutch.recall.BuildConfig
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

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

    /**
     * Local-only deck collapse state: the set of deck ids the user has EXPANDED
     * (as decimal strings). Absence = collapsed, so an empty/unset value means every
     * parent deck is collapsed — the deliberate default that shows only top-level decks
     * on first load. This is a pure UI preference; it is NEVER written back to the Anki
     * collection (no `setDeckCollapsed`), preserving the review-only "only write is
     * answerCard" guarantee.
     */
    val EXPANDED_DECK_IDS = stringSetPreferencesKey("expanded_deck_ids")

    /**
     * Default sync endpoint when nothing has been persisted yet. BUILD-TYPE scoped:
     * DEBUG prefills the host-local dev hub (`http://10.0.2.2:18080/`) so the emulator
     * workflow stays one-tap; RELEASE is EMPTY so the shipping build ships no dev server
     * and first-run forces the user to enter their own endpoint. The value comes from
     * `BuildConfig.DEV_DEFAULT_ENDPOINT`, set per build type in tool/build.gradle.kts.
     */
    val DEFAULT_SYNC_ENDPOINT: String = BuildConfig.DEV_DEFAULT_ENDPOINT
}
