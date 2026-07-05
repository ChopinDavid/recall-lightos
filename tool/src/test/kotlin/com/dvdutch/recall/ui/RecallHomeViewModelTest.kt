package com.dvdutch.recall.ui

import com.dvdutch.recall.api.Deck
import com.dvdutch.recall.prefs.RecallPreferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deck-list Home screen routing ([RecallHomeViewModel]). Pins the three [load] routes
 * (no collection → NeedsFirstRun; diverged → NeedsAttention; else the flattened deck tree),
 * the error path, and the [toggle] expand/collapse persistence round-trip (which re-filters
 * the cached tree without reopening the collection).
 */
class RecallHomeViewModelTest {

    private fun vm(
        engineFactory: (dir: java.io.File, ds: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> FakeEngine,
        seed: (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit = {},
        body: (RecallHomeViewModel, FakeEngine, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit,
    ) = withEnv("home-vm") { dir, _, ds ->
        seed(ds)
        val engine = engineFactory(dir, ds)
        val vm = RecallHomeViewModel(
            filesDir = dir,
            dataStore = ds,
            engine = engine,
            injectedScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
        body(vm, engine, ds)
    }

    private fun deck(id: Long, name: String, new: Int = 0) = Deck(id, name, new = new, learning = 0, review = 0)

    // No collection file → bounce to first-run without opening anything.
    @Test
    fun `load with no collection routes to NeedsFirstRun`() {
        vm({ dir, ds -> FakeEngine(dir, ds, collectionPresent = false) }) { vm, engine, _ ->
            vm.load()
            waitFor(message = { "NeedsFirstRun" }) { vm.uiState.value.mode is HomeMode.NeedsFirstRun }
            assertEquals(0, engine.openCalls, "no open when there's nothing to open")
        }
    }

    // A durable divergence latch on a configured controller → NeedsAttention.
    @Test
    fun `load with a diverged collection routes to NeedsAttention`() {
        vm({ dir, ds ->
            FakeEngine(dir, ds, controller = FakeSyncController(configuredResult = true), durableNeedsAttention = true)
        }) { vm, _, _ ->
            vm.load()
            waitFor(message = { "NeedsAttention" }) { vm.uiState.value.mode is HomeMode.NeedsAttention }
        }
    }

    // A live-flow divergence (not the durable pref) also routes to attention.
    @Test
    fun `load routes to NeedsAttention when only the live controller flow is latched`() {
        val controller = FakeSyncController(configuredResult = true).apply { setNeedsAttentionForTest(true) }
        vm({ dir, ds -> FakeEngine(dir, ds, controller = controller, durableNeedsAttention = false) }) { vm, _, _ ->
            vm.load()
            waitFor(message = { "NeedsAttention" }) { vm.uiState.value.mode is HomeMode.NeedsAttention }
        }
    }

    // Normal case: decks load and flatten into the collapsed-by-default tree (top-level only).
    @Test
    fun `load flattens decks into the visible tree`() {
        val decks = listOf(
            deck(10, "Spanish", new = 3),
            deck(11, "Spanish::Verbs", new = 2), // child, hidden until expanded
            deck(12, "French", new = 1),
        )
        vm({ dir, ds ->
            FakeEngine(dir, ds, controller = FakeSyncController(configuredResult = true), decks = decks)
        }) { vm, _, _ ->
            vm.load()
            waitFor(message = { "Loaded" }) { vm.uiState.value.mode is HomeMode.Loaded }
            val rows = (vm.uiState.value.mode as HomeMode.Loaded).rows
            // Default (empty expanded set) shows only top-level decks; the child is hidden.
            assertEquals(listOf("Spanish", "French"), rows.map { it.label })
            val spanish = rows.first { it.id == 10L }
            assertTrue(spanish.hasChildren, "Spanish has a ::child so it gets the expand glyph")
            assertTrue(!spanish.isExpanded)
        }
    }

    // A load failure surfaces the Error mode with the message.
    @Test
    fun `a load failure routes to Error`() {
        vm({ dir, ds ->
            FakeEngine(dir, ds, controller = FakeSyncController(configuredResult = true)).apply {
                openError = RuntimeException("torn collection")
            }
        }) { vm, _, _ ->
            vm.load()
            waitFor(message = { "Error" }) { vm.uiState.value.mode is HomeMode.Error }
            assertEquals("torn collection", (vm.uiState.value.mode as HomeMode.Error).message)
        }
    }

    // toggle expands a parent: it persists the id and re-filters the cached tree so the
    // child becomes visible — without reopening the collection.
    @Test
    fun `toggle expands a parent, persists the id, and reveals children without reopening`() {
        val decks = listOf(
            deck(10, "Spanish", new = 3),
            deck(11, "Spanish::Verbs", new = 2),
        )
        vm({ dir, ds ->
            FakeEngine(dir, ds, controller = FakeSyncController(configuredResult = true), decks = decks)
        }) { vm, engine, ds ->
            vm.load()
            waitFor(message = { "Loaded" }) { vm.uiState.value.mode is HomeMode.Loaded }
            val openCallsAfterLoad = engine.openCalls

            vm.toggle(10L) // expand Spanish

            waitFor(message = { "child revealed" }) {
                (vm.uiState.value.mode as? HomeMode.Loaded)?.rows?.any { it.label == "Verbs" } == true
            }
            val rows = (vm.uiState.value.mode as HomeMode.Loaded).rows
            assertEquals(listOf("Spanish", "Verbs"), rows.map { it.label })
            assertTrue(rows.first { it.id == 10L }.isExpanded)
            assertEquals(openCallsAfterLoad, engine.openCalls, "toggle must NOT reopen the collection")

            // The expanded id was persisted.
            val persisted = runBlocking { ds.data.first() }[RecallPreferences.EXPANDED_DECK_IDS]
            assertEquals(setOf("10"), persisted)

            // Toggling again collapses and removes the id.
            vm.toggle(10L)
            waitFor(message = { "collapsed" }) {
                (vm.uiState.value.mode as? HomeMode.Loaded)?.rows?.none { it.label == "Verbs" } == true
            }
            val after = runBlocking { ds.data.first() }[RecallPreferences.EXPANDED_DECK_IDS]
            assertTrue(after.isNullOrEmpty(), "collapse removes the id, was $after")
        }
    }
}
