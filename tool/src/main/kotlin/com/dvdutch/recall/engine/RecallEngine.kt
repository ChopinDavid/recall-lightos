package com.dvdutch.recall.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dvdutch.recall.prefs.RecallPreferences
import com.dvdutch.recall.prefs.RecallStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The single place the app assembles the on-device engine stack from persisted
 * config + storage: it reads the sync [SyncConfig] out of DataStore, resolves the
 * collection path via [RecallStorage], opens the collection on [EngineHolder.lane],
 * and hands back a wired [SyncController] and a [com.dvdutch.recall.engine.LocalEngineApi].
 *
 * Every screen/ViewModel that needs the engine (Home, Study, first-run, attention,
 * the periodic job) resolves it through here so there is exactly one collection-path
 * and one sync-config convention across the app.
 */
class RecallEngine(
    private val filesDir: File,
    private val dataStore: DataStore<Preferences>,
    private val holder: EngineHolder = EngineHolder,
) {

    val storage: RecallStorage = RecallStorage(filesDir)

    /** Reads the persisted sync config; blank fields make the controller unconfigured. */
    suspend fun syncConfig(): SyncConfig {
        val prefs = dataStore.data.first()
        return SyncConfig(
            endpoint = prefs[RecallPreferences.SYNC_ENDPOINT] ?: RecallPreferences.DEFAULT_SYNC_ENDPOINT,
            username = prefs[RecallPreferences.SYNC_USERNAME].orEmpty(),
            password = prefs[RecallPreferences.SYNC_PASSWORD].orEmpty(),
        )
    }

    /**
     * A [SyncController] built from the persisted config (unconfigured if fields are blank),
     * wired to persist its needs-attention latch to DataStore. The durable pref outlives the
     * controller instance, so Home can still route to attention after the session that latched
     * the divergence is long gone.
     */
    suspend fun controller(): SyncController =
        SyncController(syncConfig(), holder, persistNeedsAttention = ::writeNeedsAttention)

    /** The durable "collections have diverged" latch (defaults false when never written). */
    suspend fun needsAttention(): Boolean =
        dataStore.data.first()[RecallPreferences.NEEDS_ATTENTION] ?: false

    private suspend fun writeNeedsAttention(value: Boolean) {
        dataStore.edit { it[RecallPreferences.NEEDS_ATTENTION] = value }
    }

    /**
     * Opens the on-device collection (creating its directory if needed) on the engine
     * lane, so subsequent engine calls have a live collection. Idempotent — reopening
     * the same path is a no-op in [EngineHolder.openCollection]. Returns the path opened.
     */
    suspend fun openCollection(): String {
        val path = storage.ensureCollectionDir()
        withContext(holder.lane) { holder.openCollection(path) }
        return path
    }

    /**
     * Builds a [LocalEngineApi] over the open collection with the given [sync]
     * controller and a best-effort backup folder under storage. Callers must have
     * called [openCollection] first.
     */
    fun api(sync: SyncController?): LocalEngineApi =
        LocalEngineApi(holder, sync, backupFolder = storage.collectionDir.absolutePath)
}
