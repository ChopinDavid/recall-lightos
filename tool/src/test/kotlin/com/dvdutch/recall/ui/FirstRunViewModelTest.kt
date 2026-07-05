package com.dvdutch.recall.ui

import androidx.datastore.preferences.core.edit
import com.dvdutch.recall.prefs.RecallPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first-run download flow ([FirstRunViewModel]). Pins the empty-guard on [startDownload]
 * (no-op until all three fields are set), the persist-then-download side effect ordering
 * (config saved, login fired, fullSync(upload=false) pulls the collection), and the
 * success/failure terminal phases. Field sanitisation is asserted on submit.
 */
class FirstRunViewModelTest {

    private fun vm(
        controller: FakeSyncController,
        env: (dir: java.io.File, ds: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit = { _, _ -> },
        body: (FirstRunViewModel, FakeEngine, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit,
    ) = withEnv("firstrun-vm") { dir, _, ds ->
        env(dir, ds)
        val engine = FakeEngine(dir, ds, controller = controller, collectionPresent = false)
        val vm = FirstRunViewModel(
            filesDir = dir,
            dataStore = ds,
            engine = engine,
            injectedScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
        body(vm, engine, ds)
    }

    private fun fillAllFields(vm: FirstRunViewModel) {
        vm.submitField(FirstRunField.Endpoint, "https://sync.example/")
        vm.submitField(FirstRunField.Username, "alice")
        vm.submitField(FirstRunField.Password, "hunter2")
    }

    // startDownload is a no-op while fields are incomplete (canDownload guard).
    @Test
    fun `startDownload is a no-op until every field is filled`() {
        val controller = FakeSyncController()
        vm(controller) { vm, engine, _ ->
            // Only endpoint filled (username/password blank) → cannot download.
            vm.submitField(FirstRunField.Endpoint, "https://sync.example/")

            vm.startDownload()

            assertTrue(vm.state.value.phase is FirstRunPhase.Intro, "must stay on intro")
            assertEquals(0, engine.openCalls)
            assertEquals(0, controller.loginCalls)
        }
    }

    // Happy path: config persisted, login + fullSync(upload=false) fired, phase → Done.
    @Test
    fun `a complete startDownload persists config, logs in, pulls, and succeeds`() {
        val controller = FakeSyncController()
        vm(controller) { vm, engine, ds ->
            fillAllFields(vm)

            vm.startDownload()

            waitFor(message = { "phase Done" }) { vm.state.value.phase is FirstRunPhase.Done }
            assertEquals(1, controller.loginCalls, "logs in to fail fast on bad creds")
            assertEquals(1, controller.fullSyncCalls)
            assertEquals(listOf(false), controller.fullSyncUploadArgs, "pulls the collection DOWN")
            assertTrue(engine.openCalls >= 1)

            // The entered config was persisted before the pull.
            val prefs = runBlocking { ds.data.first() }
            assertEquals("https://sync.example/", prefs[RecallPreferences.SYNC_ENDPOINT])
            assertEquals("alice", prefs[RecallPreferences.SYNC_USERNAME])
            assertEquals("hunter2", prefs[RecallPreferences.SYNC_PASSWORD])
        }
    }

    // A login failure surfaces the reason and does NOT pull.
    @Test
    fun `a login failure surfaces the reason and skips the pull`() {
        val controller = FakeSyncController().apply { loginError = RuntimeException("bad creds") }
        vm(controller) { vm, _, _ ->
            fillAllFields(vm)

            vm.startDownload()

            waitFor(message = { "phase Failed" }) { vm.state.value.phase is FirstRunPhase.Failed }
            val phase = vm.state.value.phase as FirstRunPhase.Failed
            assertEquals("bad creds", phase.reason)
            assertEquals(0, controller.fullSyncCalls, "no pull after a failed login")
        }
    }

    // Sanitisation on submit: a stray trailing newline/space is stripped from credentials.
    @Test
    fun `submitField sanitises credentials`() {
        val controller = FakeSyncController()
        vm(controller) { vm, _, _ ->
            vm.submitField(FirstRunField.Username, "  alice\n")
            assertEquals("alice", vm.state.value.username)
            vm.submitField(FirstRunField.Endpoint, "https://sync.example/\n")
            assertEquals("https://sync.example/", vm.state.value.endpoint)
        }
    }

    // A second startDownload while one is in flight is ignored (Downloading guard).
    @Test
    fun `startDownload is ignored while already downloading`() {
        // SUSPEND (not block) the controller's login so the first download parks in
        // Downloading with login reached exactly once; a second call must early-return.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val controller = object : FakeSyncController() {
            override suspend fun login(): anki.sync.SyncAuth {
                loginCalls++ // record before suspending
                gate.await()
                return anki.sync.SyncAuth.getDefaultInstance()
            }
        }
        vm(controller) { vm, _, _ ->
            fillAllFields(vm)
            vm.startDownload() // parks at login (suspended), phase = Downloading

            waitFor(message = { "first download reached login" }) { controller.loginCalls == 1 }
            assertTrue(vm.state.value.phase is FirstRunPhase.Downloading)

            vm.startDownload() // second call: must early-return, no second login attempt

            assertEquals(1, controller.loginCalls, "the in-flight guard blocks a second attempt")
            gate.complete(Unit)
        }
    }
}
