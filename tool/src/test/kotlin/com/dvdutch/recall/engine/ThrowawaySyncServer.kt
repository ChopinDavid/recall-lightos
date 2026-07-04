package com.dvdutch.recall.engine

import org.junit.Assume
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

/**
 * A throwaway `anki.syncserver` for a single test class.
 *
 * Real-backend sync tests drive the genuine rslib sync client against a REAL
 * anki-sync-server. They must NEVER touch the developer's live sync hub on :18080
 * (running the suite full-uploads an empty collection and would clobber it), so each
 * test class spins its OWN server instead:
 *
 *  - a RANDOM free port (never :18080) and a fresh temp `SYNC_BASE` dir,
 *  - launched as `python -m anki.syncserver` from the project's tooling venv,
 *  - torn down (process killed + temp dir deleted) when the class finishes.
 *
 * rslib's built-in sync server lives in the native lib but is only reachable through
 * the *python* `RustBackend.syncserver()` binding — rsdroid's JVM backend surfaces only
 * client-side sync methods (`syncCollection`, `fullUploadOrDownload`, …), no server
 * entrypoint — so we shell out to that venv's python.
 *
 * The venv path is machine-specific, so [start] gates on it with a JUnit assumption:
 * on a machine without it (CI!) the calling test is SKIPPED, not failed. The suite is
 * green with no external deps; the on-device sync path is validated separately on device.
 */
class ThrowawaySyncServer private constructor(
    private val process: Process,
    private val baseDir: Path,
    /** Endpoint URL of this throwaway server, e.g. `http://127.0.0.1:54321/`. */
    val endpoint: String,
) {
    companion object {
        const val USER = "test"
        const val PW = "test123"

        /** venv python that carries the `anki` package with its native sync server. */
        private val VENV_PYTHON: Path =
            Path.of("/Users/david/anki-light/bridge/.venv/bin/python")

        /** Overridable via env for other machines/CI that ship an anki-carrying python. */
        private fun pythonPath(): Path =
            System.getenv("RECALL_SYNC_TEST_PYTHON")?.let { Path.of(it) } ?: VENV_PYTHON

        /**
         * Starts a throwaway server, or SKIPS the calling test (JUnit assumption) when no
         * anki-carrying python is available. Never returns against an unavailable server.
         */
        fun start(): ThrowawaySyncServer {
            val python = pythonPath()
            Assume.assumeTrue(
                "no anki-carrying python for a throwaway sync server " +
                    "(set RECALL_SYNC_TEST_PYTHON or provide $VENV_PYTHON); skipping real-backend sync test",
                Files.isExecutable(python),
            )

            val port = freePort()
            val baseDir = Files.createTempDirectory("recall-throwaway-sync")
            val endpoint = "http://127.0.0.1:$port/"

            val process = ProcessBuilder(
                python.toString(), "-m", "anki.syncserver",
            ).apply {
                environment()["SYNC_USER1"] = "$USER:$PW"
                environment()["SYNC_BASE"] = baseDir.toString()
                environment()["SYNC_HOST"] = "127.0.0.1"
                environment()["SYNC_PORT"] = port.toString()
                redirectErrorStream(true)
                redirectOutput(baseDir.resolve("server.log").toFile())
            }.start()

            val server = ThrowawaySyncServer(process, baseDir, endpoint)
            try {
                server.awaitReady(port)
            } catch (t: Throwable) {
                server.close()
                throw t
            }
            return server
        }

        /** Binds :0 to let the OS hand us a free port, then releases it for the server. */
        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }

    fun config(username: String = USER, password: String = PW): SyncConfig =
        SyncConfig(endpoint = endpoint, username = username, password = password)

    /** Polls until the server accepts a TCP connection, or fails with the server log. */
    private fun awaitReady(port: Int) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                error("throwaway sync server exited early:\n${readLog()}")
            }
            try {
                java.net.Socket("127.0.0.1", port).use { return }
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        error("throwaway sync server did not become ready within 20s:\n${readLog()}")
    }

    private fun readLog(): String =
        try {
            baseDir.resolve("server.log").toFile().readText()
        } catch (_: Exception) {
            "(no server log)"
        }

    /** Kills the server process and deletes its temp dir. Idempotent. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun close() {
        process.destroy()
        if (process.isAlive && !process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        try {
            baseDir.deleteRecursively()
        } catch (_: Exception) {
            // best-effort temp cleanup
        }
    }
}
