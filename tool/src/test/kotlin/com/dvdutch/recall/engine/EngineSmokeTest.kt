package com.dvdutch.recall.engine

import net.ankiweb.rsdroid.testing.RustBackendLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Task 0 JVM-viability spike, now driven THROUGH [EngineHolder].
 *
 * Proves that the on-device Anki engine can be exercised from a plain JUnit test on
 * the desktop JVM — no Android, no Robolectric, no emulator — via the real
 * [EngineHolder] API that on-device code uses. The
 * `anki-android-backend-testing` artifact bundles the desktop rslib natives
 * (`librsdroid.dylib`/`.so`/`.dll`) and a loader,
 * [net.ankiweb.rsdroid.testing.RustBackendLoader.ensureSetup], which extracts and
 * loads the right one for the host OS in place of `System.loadLibrary("rsdroid")`.
 *
 * On device, [EngineHolder.backend] calls `System.loadLibrary("rsdroid")`, which
 * throws [UnsatisfiedLinkError] on the desktop JVM. To make [EngineHolder] itself
 * testable on the JVM, its native-load step is an injectable seam
 * ([EngineHolder.nativeLoader]): this test overrides it with
 * [RustBackendLoader.ensureSetup] BEFORE the first [EngineHolder.backend] call.
 *
 * If this test runs green under `:tool:testDebugUnitTest`, JVM engine tests through
 * [EngineHolder] are VIABLE and later tasks may unit-test engine calls directly on
 * the JVM. See the Task 0 report for the verdict.
 *
 * Reset contract: [EngineHolder] is a process-wide singleton `object`, so this test
 * MUST leave no open collection behind for other tests. It closes the collection in
 * a `finally`; the backend handle itself is intentionally retained (that is
 * [EngineHolder]'s design — one native backend per process).
 */
class EngineSmokeTest {

    @Test
    fun `EngineHolder opens a collection and reads its deck tree on the JVM`() {
        // Override the native-load seam so EngineHolder loads the host desktop native
        // via the -testing loader instead of System.loadLibrary("rsdroid"), which
        // would throw UnsatisfiedLinkError on the JVM. Must precede the first
        // backend() call.
        EngineHolder.nativeLoader = { RustBackendLoader.ensureSetup() }

        val tmpDir = Files.createTempDirectory("recall-engine-smoke")
        val colPath = tmpDir.resolve("collection.anki2").toString()
        try {
            // openCollection creates a fresh collection when the file is absent.
            EngineHolder.openCollection(colPath)

            // deckTree(0) reads the whole tree; every collection has the Default deck,
            // so the root reports at least one child. This is a real rslib round-trip
            // through EngineHolder's own backend handle.
            val root = EngineHolder.backend().deckTree(0)
            assertTrue(
                root.childrenCount >= 1,
                "expected the Default deck under the tree root, got ${root.childrenCount} children",
            )
        } finally {
            // Reset contract: leave no open collection for other tests.
            EngineHolder.closeCollection()
            tmpDir.toFile().deleteRecursively()
        }
    }
}
