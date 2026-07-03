package com.dvdutch.recall.prefs

import java.io.File

/**
 * The on-device Anki collection layout, resolved from the tool's private storage
 * root (`SealedLightContext.filesDir`).
 *
 * The collection is a single Anki database plus its media directory, laid out to
 * match the bridge's on-disk convention (media beside the `.anki2` in a sibling
 * `<name>.media` folder), so `collection.anki2` and `collection.media/` sit
 * together under one `collection/` directory:
 *
 * ```
 * <filesDir>/collection/
 *   ├── collection.anki2        # the rslib collection (SyncController downloads this)
 *   └── collection.media/       # media files, read by MediaLoader by bare filename
 * ```
 *
 * Pure [File] arithmetic — no Android runtime — so it is unit-tested directly and
 * shared by the engine wiring, the first-run download flow and the media loader,
 * which must all agree on exactly one path.
 */
class RecallStorage(private val filesDir: File) {

    /** The directory holding the collection database and its media folder. */
    val collectionDir: File
        get() = File(filesDir, COLLECTION_DIR_NAME)

    /** The rslib collection database file. Opened via [collectionPath]. */
    val collectionFile: File
        get() = File(collectionDir, COLLECTION_FILE_NAME)

    /** The media directory (`collection.media`) beside the collection file. */
    val mediaDir: File
        get() = File(collectionDir, MEDIA_DIR_NAME)

    /** The absolute path passed to `EngineHolder.openCollection`. */
    val collectionPath: String
        get() = collectionFile.absolutePath

    /** Resolves a bare media [filename] to its on-disk file under [mediaDir]. */
    fun mediaFile(filename: String): File = File(mediaDir, filename)

    /**
     * True once a collection database has been downloaded. First-run is entered
     * only when this is false; after a full download it is true.
     */
    fun collectionExists(): Boolean = collectionFile.isFile

    /**
     * Creates [collectionDir] (and parents) if absent and returns [collectionPath].
     * rslib creates the `.anki2` itself on open / full-download; we only guarantee
     * the containing directory exists.
     */
    fun ensureCollectionDir(): String {
        collectionDir.mkdirs()
        return collectionPath
    }

    private companion object {
        const val COLLECTION_DIR_NAME = "collection"
        const val COLLECTION_FILE_NAME = "collection.anki2"
        const val MEDIA_DIR_NAME = "collection.media"
    }
}
