package com.dvdutch.recall.prefs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure path-resolution tests for [RecallStorage]. The on-device collection lives
 * under the tool's `filesDir`; these assert the exact layout the engine, media
 * loader and first-run flow all agree on. No Android runtime needed — everything
 * is [File] arithmetic against a real temp directory.
 */
class RecallStorageTest {

    private fun tmpRoot(): File =
        File.createTempFile("recall-storage", "").let {
            it.delete(); it.mkdirs(); it
        }

    @Test
    fun `collection file sits under a collection dir`() {
        val root = tmpRoot()
        val storage = RecallStorage(root)
        assertEquals(File(root, "collection"), storage.collectionDir)
        assertEquals(File(root, "collection/collection.anki2"), storage.collectionFile)
        root.deleteRecursively()
    }

    @Test
    fun `media dir is collection dot media beside the collection file`() {
        val root = tmpRoot()
        val storage = RecallStorage(root)
        assertEquals(
            File(root, "collection/collection.media"),
            storage.mediaDir,
        )
        // The bridge's on-disk convention: media beside the .anki2 in <name>.media.
        assertEquals(storage.collectionFile.parentFile, storage.mediaDir.parentFile)
        root.deleteRecursively()
    }

    @Test
    fun `mediaFile resolves a bare filename under the media dir`() {
        val root = tmpRoot()
        val storage = RecallStorage(root)
        assertEquals(
            File(root, "collection/collection.media/map europe.webp"),
            storage.mediaFile("map europe.webp"),
        )
        root.deleteRecursively()
    }

    @Test
    fun `collectionExists is false before download and true after`() {
        val root = tmpRoot()
        val storage = RecallStorage(root)
        assertFalse(storage.collectionExists(), "no collection file yet")

        storage.collectionDir.mkdirs()
        storage.collectionFile.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(storage.collectionExists(), "collection file now present")
        root.deleteRecursively()
    }

    @Test
    fun `ensureCollectionDir creates the collection directory`() {
        val root = tmpRoot()
        val storage = RecallStorage(root)
        assertFalse(storage.collectionDir.exists())
        val path = storage.ensureCollectionDir()
        assertTrue(storage.collectionDir.isDirectory, "dir created")
        assertEquals(storage.collectionFile.absolutePath, path)
        root.deleteRecursively()
    }
}
