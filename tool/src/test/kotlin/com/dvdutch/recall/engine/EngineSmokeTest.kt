package com.dvdutch.recall.engine

import net.ankiweb.rsdroid.BackendFactory
import net.ankiweb.rsdroid.testing.RustBackendLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Task 0 JVM-viability spike.
 *
 * Proves that the on-device Anki engine can be driven from a plain JUnit test on
 * the desktop JVM — no Android, no Robolectric, no emulator. The
 * `anki-android-backend-testing` artifact bundles the desktop rslib natives
 * (`librsdroid.dylib`/`.so`/`.dll`) and a loader,
 * [net.ankiweb.rsdroid.testing.RustBackendLoader.ensureSetup], which extracts and
 * loads the right one for the host OS in place of `System.loadLibrary("rsdroid")`.
 *
 * If this test runs green under `:tool:testDebugUnitTest`, JVM engine tests are
 * VIABLE and later tasks may unit-test engine calls directly on the JVM. If the
 * loader had required Android/Robolectric classes, this would fail at
 * [RustBackendLoader.ensureSetup] and the strategy would fall back to emulator
 * logcat checks — see the Task 0 report for the verdict.
 */
class EngineSmokeTest {

    @Test
    fun `backend opens a collection and reads its deck tree on the JVM`() {
        // Load the host-appropriate desktop native (replaces loadLibrary on device).
        RustBackendLoader.ensureSetup()

        val backend = BackendFactory.getBackend()
        val tmpDir = Files.createTempDirectory("recall-engine-smoke")
        val colPath = tmpDir.resolve("collection.anki2").toString()
        try {
            // openCollection creates a fresh collection when the file is absent.
            backend.openCollection(colPath)

            // deckTree(0) reads the whole tree; every collection has the Default deck,
            // so the root reports at least one child. This is a real rslib round-trip.
            val root = backend.deckTree(0)
            assertTrue(
                root.childrenCount >= 1,
                "expected the Default deck under the tree root, got ${root.childrenCount} children",
            )
        } finally {
            backend.close()
            tmpDir.toFile().deleteRecursively()
        }
    }
}
