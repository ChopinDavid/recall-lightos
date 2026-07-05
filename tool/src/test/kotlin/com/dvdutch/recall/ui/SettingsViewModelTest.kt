package com.dvdutch.recall.ui

import com.dvdutch.recall.prefs.RecallPreferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings ([SettingsViewModel]): the "Test login" action's outcome mapping (unconfigured
 * guidance / ok / failed) and the field-save path (sanitiser applied, value persisted to
 * DataStore, editor returns to Main). Init's stored-state load is asserted too.
 */
class SettingsViewModelTest {

    private fun vm(
        controller: FakeSyncController,
        seed: (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit = {},
        body: (SettingsViewModel, FakeEngine, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit,
    ) = withEnv("settings-vm") { dir, _, ds ->
        seed(ds)
        val engine = FakeEngine(dir, ds, controller = controller)
        val vm = SettingsViewModel(
            filesDir = dir,
            dataStore = ds,
            engine = engine,
            injectedScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
        body(vm, engine, ds)
    }

    // Test login when unconfigured never touches the network: it just guides the user.
    @Test
    fun `testLogin on an unconfigured controller shows fill-in guidance and never logs in`() {
        val controller = FakeSyncController(configuredResult = false)
        vm(controller) { vm, _, _ ->
            vm.testLogin()

            waitFor(message = { "status set" }) { vm.uiState.value.statusLine?.contains("fill in") == true }
            assertEquals(false, vm.uiState.value.testing)
            assertEquals(0, controller.loginCalls, "unconfigured must not attempt a login")
        }
    }

    // A successful live login maps to the ok line.
    @Test
    fun `testLogin success shows the login-ok line`() {
        val controller = FakeSyncController(configuredResult = true)
        vm(controller) { vm, _, _ ->
            vm.testLogin()

            waitFor(message = { "ok line" }) { vm.uiState.value.statusLine == SettingsMessages.loginOkLine() }
            assertEquals(1, controller.loginCalls)
            assertEquals(false, vm.uiState.value.testing)
        }
    }

    // A login failure maps through SettingsMessages.loginFailedLine with the reason.
    @Test
    fun `testLogin failure shows the mapped failure line`() {
        val controller = FakeSyncController(configuredResult = true).apply {
            loginError = RuntimeException("token rejected")
        }
        vm(controller) { vm, _, _ ->
            vm.testLogin()

            waitFor(message = { "failure line" }) {
                vm.uiState.value.statusLine == SettingsMessages.loginFailedLine("token rejected")
            }
            assertEquals("login failed: token rejected", vm.uiState.value.statusLine)
        }
    }

    // Saving the endpoint applies the endpoint sanitiser, returns to Main, and persists.
    @Test
    fun `submitEndpoint sanitises, returns to Main, and persists`() {
        val controller = FakeSyncController()
        vm(
            controller,
            // Seed a sentinel so we can await init's async load completing before we submit —
            // otherwise a late loadStoredState could clobber the value we just set (a race,
            // not a production concern: the real editor only opens after the screen settles).
            seed = { ds -> runBlocking { ds.edit { it[RecallPreferences.SYNC_ENDPOINT] = "https://seed.example/" } } },
        ) { vm, _, ds ->
            waitFor(message = { "init loaded seed" }) { vm.uiState.value.endpoint == "https://seed.example/" }
            vm.openEditEndpoint()
            assertTrue(vm.uiState.value.mode is SettingsMode.EditEndpoint)

            vm.submitEndpoint("https://sync.example/\n")

            assertEquals("https://sync.example/", vm.uiState.value.endpoint)
            assertTrue(vm.uiState.value.mode is SettingsMode.Main, "editor closes to Main on save")

            waitFor(message = { "endpoint persisted" }) {
                runBlocking { ds.data.first() }[RecallPreferences.SYNC_ENDPOINT] == "https://sync.example/"
            }
        }
    }

    // Saving a credential uses the credential sanitiser (interior spaces would be fine, but
    // edge whitespace/newlines are stripped).
    @Test
    fun `submitPassword sanitises and persists`() {
        val controller = FakeSyncController()
        vm(
            controller,
            seed = { ds -> runBlocking { ds.edit { it[RecallPreferences.SYNC_PASSWORD] = "seedpw" } } },
        ) { vm, _, ds ->
            waitFor(message = { "init loaded seed" }) { vm.uiState.value.password == "seedpw" }
            vm.submitPassword("  s3cret\n")
            assertEquals("s3cret", vm.uiState.value.password)
            waitFor(message = { "password persisted" }) {
                runBlocking { ds.data.first() }[RecallPreferences.SYNC_PASSWORD] == "s3cret"
            }
        }
    }

    // init reads previously-stored config into the UI state.
    @Test
    fun `init loads stored config into the ui state`() {
        val controller = FakeSyncController()
        vm(
            controller,
            seed = { ds ->
                runBlocking {
                    ds.edit {
                        it[RecallPreferences.SYNC_ENDPOINT] = "https://saved.example/"
                        it[RecallPreferences.SYNC_USERNAME] = "bob"
                        it[RecallPreferences.SYNC_PASSWORD] = "pw"
                    }
                }
            },
        ) { vm, _, _ ->
            waitFor(message = { "loaded stored endpoint" }) {
                vm.uiState.value.endpoint == "https://saved.example/"
            }
            assertEquals("bob", vm.uiState.value.username)
            assertEquals("pw", vm.uiState.value.password)
        }
    }
}
