package com.dvdutch.recall.ui

import com.dvdutch.recall.engine.FullDownloadResult
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The destructive needs-attention resolution flow ([AttentionViewModel]). These pin the
 * two-tap confirm contract: NO destructive controller call fires before a confirm; the
 * confirm invokes the RIGHT op (plain fullSync for upload, guarded fullDownload for
 * download) with the right arguments; and every backend outcome — kept download, tripped
 * empty-server guard, failure, thrown exception — maps to the correct terminal UI phase.
 *
 * The engine/controller are faked (see ViewModelTestSupport); the coroutines run on
 * [Dispatchers.Unconfined] via an injected scope so each launched body completes inline.
 */
class AttentionViewModelTest {

    private fun vm(
        controller: FakeSyncController,
        engineBlock: (FakeEngine) -> Unit = {},
    ): Pair<AttentionViewModel, FakeEngine> = withEnv("attn-vm") { dir, scope, ds ->
        val engine = FakeEngine(dir, ds, controller = controller).also(engineBlock)
        val vm = AttentionViewModel(
            filesDir = dir,
            dataStore = ds,
            engine = engine,
            injectedScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
        vm to engine
    }

    // choose then cancel are pure phase moves and touch NO controller op.
    @Test
    fun `choosing a direction shows its confirm and runs no destructive op`() {
        val controller = FakeSyncController()
        val (vm, _) = vm(controller)

        vm.choose(AttentionDirection.Download)

        val phase = vm.uiState.value.phase
        assertTrue(phase is AttentionPhase.Confirm, "was $phase")
        assertEquals(AttentionDirection.Download, phase.direction)
        // The whole point of the two-tap gate: nothing destructive before confirm.
        assertEquals(0, controller.fullDownloadCalls)
        assertEquals(0, controller.fullSyncCalls)
    }

    @Test
    fun `cancel returns to the choice screen without acting`() {
        val controller = FakeSyncController()
        val (vm, _) = vm(controller)
        vm.choose(AttentionDirection.Upload)

        vm.cancel()

        assertTrue(vm.uiState.value.phase is AttentionPhase.Choose)
        assertEquals(0, controller.fullSyncCalls)
    }

    // Confirming an UPLOAD runs the plain fullSync(upload=true) and lands on Done.
    @Test
    fun `confirming upload runs fullSync upload and completes`() {
        val controller = FakeSyncController()
        val (vm, engine) = vm(controller)

        vm.confirm(AttentionDirection.Upload)

        assertEquals(1, controller.fullSyncCalls)
        assertEquals(listOf(true), controller.fullSyncUploadArgs)
        assertEquals(0, controller.fullDownloadCalls, "upload must NOT use the download guard")
        assertTrue(engine.openCalls >= 1, "collection opened before the destructive op")
        assertTrue(vm.uiState.value.phase is AttentionPhase.Done, "was ${vm.uiState.value.phase}")
    }

    // Confirming a DOWNLOAD runs the GUARDED fullDownload(force=false); a kept download → Done.
    @Test
    fun `confirming download runs guarded fullDownload unforced and completes on a kept download`() {
        val controller = FakeSyncController().apply { fullDownloadResult = FullDownloadResult.Downloaded }
        val (vm, _) = vm(controller)

        vm.confirm(AttentionDirection.Download)

        assertEquals(1, controller.fullDownloadCalls)
        assertEquals(listOf(false), controller.fullDownloadForceArgs, "confirm never forces past the guard")
        assertEquals(0, controller.fullSyncCalls)
        assertTrue(vm.uiState.value.phase is AttentionPhase.Done, "was ${vm.uiState.value.phase}")
    }

    // The empty-server guard trip surfaces the harder GuardConfirm with the phone's count.
    @Test
    fun `a tripped empty-server guard routes to GuardConfirm carrying the local card count`() {
        val controller = FakeSyncController().apply {
            fullDownloadResult = FullDownloadResult.GuardTripped(localCardCount = 42)
        }
        val (vm, _) = vm(controller)

        vm.confirm(AttentionDirection.Download)

        val phase = vm.uiState.value.phase
        assertTrue(phase is AttentionPhase.GuardConfirm, "was $phase")
        assertEquals(42, phase.localCardCount)
        // Crucially the divergence is NOT resolved — the phone kept its cards, no Done.
    }

    // A backend Failed result maps to the retriable Failed phase with the reason.
    @Test
    fun `a failed download surfaces the failure reason`() {
        val controller = FakeSyncController().apply {
            fullDownloadResult = FullDownloadResult.Failed("server 500")
        }
        val (vm, _) = vm(controller)

        vm.confirm(AttentionDirection.Download)

        val phase = vm.uiState.value.phase
        assertTrue(phase is AttentionPhase.Failed, "was $phase")
        assertEquals("server 500", phase.reason)
    }

    // A THROWN exception from the op (not a Failed result) also lands on Failed.
    @Test
    fun `a thrown sync exception lands on Failed`() {
        val controller = FakeSyncController().apply { fullSyncError = RuntimeException("boom") }
        val (vm, _) = vm(controller)

        vm.confirm(AttentionDirection.Upload)

        val phase = vm.uiState.value.phase
        assertTrue(phase is AttentionPhase.Failed, "was $phase")
        assertEquals("boom", phase.reason)
    }

    // forceDownload is the ONLY path that forces past the guard, and a kept forced
    // download resolves to Done.
    @Test
    fun `forceDownload forces past the guard and completes`() {
        val controller = FakeSyncController().apply { fullDownloadResult = FullDownloadResult.Downloaded }
        val (vm, _) = vm(controller)

        vm.forceDownload()

        assertEquals(1, controller.fullDownloadCalls)
        assertEquals(listOf(true), controller.fullDownloadForceArgs, "forceDownload MUST force")
        assertTrue(vm.uiState.value.phase is AttentionPhase.Done, "was ${vm.uiState.value.phase}")
    }

    // A forced download that fails still surfaces the reason (not a silent wipe).
    @Test
    fun `a failed forceDownload surfaces the reason`() {
        val controller = FakeSyncController().apply {
            fullDownloadResult = FullDownloadResult.Failed("nope")
        }
        val (vm, _) = vm(controller)

        vm.forceDownload()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AttentionPhase.Failed, "was $phase")
        assertEquals("nope", phase.reason)
    }

    // The init block reads the phone's card count for the confirm copy.
    @Test
    fun `the local card count is read up front for the confirm copy`() {
        val controller = FakeSyncController()
        val (vm, _) = vm(controller)

        waitFor(message = { "localCardCount populated" }) { vm.uiState.value.localCardCount == 7 }
        assertEquals(7, vm.uiState.value.localCardCount)
    }
}
