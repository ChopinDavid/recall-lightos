package com.dvdutch.recall.engine

import anki.notes.Note
import com.dvdutch.recall.api.AnswerIn
import com.dvdutch.recall.api.OcclusionNode
import com.dvdutch.recall.api.OcclusionShapeState
import com.dvdutch.recall.api.ShapeState
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.UnsupportedNode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.testing.RustBackendLoader
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-backend JVM tests for [LocalEngineApi] — no mocks, a temp collection per test,
 * driven through the shared [EngineHolder] singleton exactly as on-device code does.
 * See [EngineSmokeTest] for the native-load seam pattern this reuses.
 */
class LocalEngineApiTest {

    private lateinit var tmpDir: java.nio.file.Path
    private lateinit var api: LocalEngineApi

    @BeforeTest
    fun setUp() {
        EngineHolder.nativeLoader = { RustBackendLoader.ensureSetup() }
        tmpDir = Files.createTempDirectory("recall-local-engine")
        val colPath = tmpDir.resolve("collection.anki2").toString()
        runBlocking { withContext(EngineHolder.lane) { EngineHolder.openCollection(colPath) } }
        api = LocalEngineApi(EngineHolder)
    }

    @AfterTest
    fun tearDown() {
        runBlocking { withContext(EngineHolder.lane) { EngineHolder.closeCollection() } }
        tmpDir.toFile().deleteRecursively()
    }

    /** On-lane access to the raw backend for test fixture seeding only. */
    private fun <T> onEngine(block: (Backend) -> T): T =
        runBlocking { withContext(EngineHolder.lane) { block(EngineHolder.backend()) } }

    /** Creates the named deck if absent (parents auto-created); returns its id. */
    private fun deckIdCreating(backend: Backend, name: String): Long {
        // getDeckIdByName throws NotFound (not 0) when the deck is absent.
        val existing = try {
            backend.getDeckIdByName(name)
        } catch (_: net.ankiweb.rsdroid.exceptions.BackendNotFoundException) {
            0L
        }
        if (existing != 0L) return existing
        val deck = backend.newDeck().toBuilder().setName(name).build()
        return backend.addDeck(deck).id
    }

    /** Selects [deckId] as the current deck (the queue is scoped to it). */
    private fun selectDeck(deckId: Long) = onEngine { it.setCurrentDeck(deckId) }

    /** Adds a Basic note (front/back) into a named deck; returns the deck id. */
    private fun seedNote(deckName: String, front: String, back: String): Long {
        return onEngine { backend ->
            // The stock "Basic" notetype is present in every fresh rslib collection.
            val ntid = backend.getNotetypeIdByName("Basic")
            require(ntid != 0L) { "fresh collection is missing the stock Basic notetype" }
            val deckId = deckIdCreating(backend, deckName)
            val n = Note.newBuilder()
                .setNotetypeId(ntid)
                .addFields(front)
                .addFields(back)
                .build()
            backend.addNote(n, deckId)
            deckId
        }
    }

    /**
     * Seeds a 3-shape (rect+ellipse+polygon) Image Occlusion note into [deckName] and
     * returns the deck id. Creates the stock IO notetype, writes a real 640×480 PNG to a
     * temp path, and calls the backend's `addImageOcclusionNote` with the verified grammar.
     */
    private fun seedOcclusionNote(deckName: String, occludeInactive: Boolean): Long {
        return onEngine { backend ->
            backend.addImageOcclusionNotetype()
            val ioId = backend.getNotetypeNames().first { nn ->
                backend.getNotetype(nn.id).config.originalStockKind ==
                    anki.notetypes.StockNotetype.OriginalStockKind.ORIGINAL_STOCK_KIND_IMAGE_OCCLUSION
            }.id
            val deckId = deckIdCreating(backend, deckName)
            backend.setCurrentDeck(deckId)
            val png = tmpDir.resolve("occ.png")
            Files.write(png, MINIMAL_PNG)
            val oi = if (occludeInactive) ":oi=1" else ""
            val occlusions =
                "{{c1::image-occlusion:rect:left=10:top=20:width=100:height=80$oi}}" +
                    "{{c2::image-occlusion:ellipse:left=200:top=50:width=60:height=40:rx=30:ry=20$oi}}" +
                    "{{c3::image-occlusion:polygon:points=10,10 60,10 35,50$oi}}"
            backend.addImageOcclusionNote(
                png.toString(), occlusions, "Cerebellum", "Coordinates motor", emptyList(), ioId,
            )
            deckId
        }
    }

    @Test
    fun `queue emits a native OcclusionNode for an Image Occlusion card not a canvas`() {
        // card ord 0 tests occlusion ordinal 1 (the rect). Hide-all mode.
        selectDeck(seedOcclusionNote("Anatomy", occludeInactive = true))
        val q = runBlocking { api.queue(20) }
        // Cards come back in ordinal order; ord 0 is the rect card.
        val card = q.cards.first()

        // The front must carry a real OcclusionNode, NOT the broken canvas/unsupported path.
        val occ = card.front.filterIsInstance<OcclusionNode>().single()
        assertTrue(
            card.front.none { it is UnsupportedNode && it.kind == "canvas" },
            "occlusion card must not fall through to a canvas unsupported node: ${card.front}",
        )
        assertEquals("occ.png", occ.image)
        assertEquals(640, occ.naturalW, "natural width decoded from image bytes")
        assertEquals(480, occ.naturalH, "natural height decoded from image bytes")
        assertEquals("front", occ.side)
        assertEquals(3, occ.shapes.size)

        // The tested shape (ordinal 1 = rect) is MASKED_TESTED on the front — the asked
        // region, drawn distinct from the other (inactive) hide-all masks.
        val rect = occ.shapes.filterIsInstance<OcclusionShapeState.Rect>().single()
        assertEquals(ShapeState.MASKED_TESTED, rect.state)
        // Hide-all: the inactive ellipse/polygon are plainly MASKED on the front.
        assertTrue(
            occ.shapes.filter { it !is OcclusionShapeState.Rect }.all { it.state == ShapeState.MASKED },
            "hide-all front inactive masks plain MASKED: ${occ.shapes}",
        )

        // Header is a text node, present before the image on both sides.
        val frontText = card.front.filterIsInstance<TextNode>().flatMap { it.runs }.joinToString("") { it.s }
        assertTrue("Cerebellum" in frontText, "header text node missing on front: $frontText")

        // Back reveals the tested rect (outline) and carries Back Extra text.
        val backOcc = card.back.filterIsInstance<OcclusionNode>().single()
        assertEquals("back", backOcc.side)
        val backRect = backOcc.shapes.filterIsInstance<OcclusionShapeState.Rect>().single()
        assertEquals(ShapeState.REVEALED_OUTLINE, backRect.state)
        val backText = card.back.filterIsInstance<TextNode>().flatMap { it.runs }.joinToString("") { it.s }
        assertTrue("Coordinates motor" in backText, "back extra text node missing: $backText")
    }

    @Test
    fun `decks flattens the tree with colon-colon names and hides the empty default`() {
        seedNote("Spanish::Verbs", "hola", "hello")
        val decks = runBlocking { api.decks() }
        val names = decks.map { it.name }
        assertTrue("Spanish" in names, "expected parent 'Spanish' deck, got $names")
        assertTrue("Spanish::Verbs" in names, "expected '::'-joined child, got $names")
        // The empty Default deck (id 1) must be hidden.
        assertTrue(decks.none { it.id == 1L }, "empty Default deck must be hidden, got $names")
        val verbs = decks.first { it.name == "Spanish::Verbs" }
        assertTrue(verbs.new >= 1, "the seeded new card should be counted, got ${verbs.new}")
    }

    @Test
    fun `queue payload is self-contained states round-trip labels keys and Cyrillic text`() {
        selectDeck(seedNote("Russian", "Привет", "Hello"))
        val q = runBlocking { api.queue(20) }
        assertTrue(q.cards.isNotEmpty(), "expected at least one queued card")
        val card = q.cards.first()

        // States round-trip: valid base64 that decodes to a SchedulingStates proto.
        val decoded = Base64.getDecoder().decode(card.states)
        val states = anki.scheduler.SchedulingStates.parseFrom(decoded)
        assertTrue(states.hasCurrent(), "states must carry a current scheduling state")

        // Label keys are exactly again/hard/good/easy.
        assertEquals(setOf("again", "hard", "good", "easy"), card.nextDueLabels.keys)
        assertTrue(card.nextDueLabels.values.all { it.isNotBlank() })

        // Cyrillic content survives the real render path as a text node.
        val frontText = card.front.filterIsInstance<TextNode>()
            .flatMap { it.runs }.joinToString("") { it.s }
        assertTrue("Привет" in frontText, "Cyrillic front lost, got: $frontText")
    }

    @Test
    fun `queue appends an audio placeholder when the card has AV tags`() {
        // [sound:x.mp3] on the front produces an av tag → audio UnsupportedNode.
        selectDeck(seedNote("Audio", "listen [sound:hello.mp3]", "back"))
        val q = runBlocking { api.queue(20) }
        val front = q.cards.first().front
        assertTrue(
            front.any { it is UnsupportedNode && it.kind == "audio" },
            "expected an audio placeholder node, got $front",
        )
        // The [anki:play] marker must never reach the phone as literal text.
        val text = front.filterIsInstance<TextNode>().flatMap { it.runs }.joinToString("") { it.s }
        assertTrue("anki:play" !in text && "sound:" !in text, "AV marker leaked: $text")
    }

    @Test
    fun `queue surfaces ordered sound filenames and never leaks AV markers`() {
        // The [sound:test.mp3] reference lands on the front (question) side.
        selectDeck(seedNote("AudioFiles", "front [sound:test.mp3]", "back"))
        val card = runBlocking { api.queue(20) }.cards.first()

        // The ordered filename is surfaced for playback...
        assertTrue(
            "test.mp3" in card.frontAudio,
            "expected front_audio to contain test.mp3, got ${card.frontAudio}",
        )
        // ...while neither the raw [sound:] ref nor the [anki:play] rewrite reaches the
        // compiled render nodes as literal text, on either side.
        val allText = (card.front + card.back)
            .filterIsInstance<TextNode>().flatMap { it.runs }.joinToString("") { it.s }
        assertTrue(
            "sound:" !in allText && "anki:play" !in allText,
            "AV marker leaked into render nodes: $allText",
        )
    }

    @Test
    fun `answer applied then re-answering the same states is stale`() {
        selectDeck(seedNote("Grading", "q", "a"))
        val q = runBlocking { api.queue(20) }
        val newBefore = q.counts.new
        val card = q.cards.first()
        val ans = AnswerIn(
            uuid = "test-uuid-0001",
            cardId = card.cardId,
            rating = "good",
            states = card.states,
            msTaken = 1500,
            answeredAt = 1_700_000_000_000,
        )
        val results = runBlocking { api.answer(listOf(ans)) }
        assertEquals("applied", results.single().status)

        // Behavioural applied-ness: the card left the new queue.
        val after = runBlocking { api.queue(20) }
        assertTrue(after.counts.new < newBefore, "new count should drop after answering")

        // Re-answering with the SAME (now stale) states must be rejected as stale.
        val second = runBlocking { api.answer(listOf(ans.copy(uuid = "test-uuid-0002"))) }
        assertEquals("stale", second.single().status, "re-answering stale states must be 'stale'")
    }

    @Test
    fun `undo reverts the last answer restoring the card and counts`() {
        selectDeck(seedNote("Undoable", "q", "a"))
        val before = runBlocking { api.queue(20) }
        val newBefore = before.counts.new
        val card = before.cards.first()
        // Before answering, nothing is undoable.
        assertEquals(false, before.undoableAnswer, "a fresh queue must report no undoable op")

        val ans = AnswerIn(
            uuid = "undo-uuid-0001",
            cardId = card.cardId,
            rating = "good",
            states = card.states,
            msTaken = 1500,
            answeredAt = 1_700_000_000_000,
        )
        assertEquals("applied", runBlocking { api.answer(listOf(ans)) }.single().status)

        // The card left the new queue and the engine now reports an undoable op.
        val afterAnswer = runBlocking { api.queue(20) }
        assertTrue(afterAnswer.counts.new < newBefore, "new count should drop after answering")
        assertTrue(afterAnswer.undoableAnswer, "the engine must report the answer as undoable")

        // rslib's own undo un-answers the card.
        val result = runBlocking { api.undo() }
        assertTrue(result.undone, "undo must revert the answer")

        // The same card is queued again and counts are restored to the pre-answer state.
        val afterUndo = runBlocking { api.queue(20) }
        assertEquals(newBefore, afterUndo.counts.new, "new count restored after undo")
        assertTrue(
            afterUndo.cards.any { it.cardId == card.cardId },
            "the un-answered card must be queued again",
        )
    }

    @Test
    fun `undo with no answer on the stack is a safe no-op`() {
        selectDeck(seedNote("NoUndo", "q", "a"))
        // Nothing has been answered. The stack is NOT empty — opening the deck pushes a
        // "Select Deck" config op — so this also proves undo refuses to revert a
        // non-answer op: it must be a review-only undo, never a deck-selection undo.
        val result = runBlocking { api.undo() }
        assertEquals(false, result.undone, "no answer on the stack must yield undone = false")
        // And the deck is still selected: the queue still returns the card.
        assertTrue(
            runBlocking { api.queue(20) }.cards.isNotEmpty(),
            "undo must not have reverted the deck selection",
        )
    }

    @Test
    fun `answer on a missing card is gone`() {
        selectDeck(seedNote("Gone", "q", "a"))
        val q = runBlocking { api.queue(20) }
        val realStates = q.cards.first().states
        val ans = AnswerIn(
            uuid = "gone-uuid-0001",
            cardId = 999_999_999L, // no such card
            rating = "good",
            states = realStates,
            msTaken = 100,
            answeredAt = 1_700_000_000_000,
        )
        val results = runBlocking { api.answer(listOf(ans)) }
        assertEquals("gone", results.single().status)
    }

    @Test
    fun `answer with undecodable states is stale`() {
        selectDeck(seedNote("BadStates", "q", "a"))
        val q = runBlocking { api.queue(20) }
        val ans = AnswerIn(
            uuid = "bad-uuid-0001",
            cardId = q.cards.first().cardId,
            rating = "good",
            states = "!!!not base64!!!",
            msTaken = 100,
            answeredAt = 1_700_000_000_000,
        )
        val results = runBlocking { api.answer(listOf(ans)) }
        assertEquals("stale", results.single().status)
    }

    @Test
    fun `studyStart selects the deck and reports counts with sync-not-configured`() {
        val deckId = seedNote("StudyDeck", "q", "a")
        val resp = runBlocking { api.studyStart(deckId) }
        assertTrue(resp.counts.new >= 1, "expected the seeded new card in counts")
        assertEquals(false, resp.sync.synced)
        assertNotNull(resp.sync.detail)
    }

    @Test
    fun `studyFinish reports sync not configured`() {
        val resp = runBlocking { api.studyFinish() }
        assertEquals(false, resp.synced)
    }

    // --- Sync-aware study brackets (Task 3) ---------------------------------------

    private companion object {
        /** A real 640×480 PNG (all-black) so `imageData` decodes to natural size 640×480. */
        val MINIMAL_PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAoAAAAHgCAIAAAC6s0uzAAADk0lEQVR42u3BAQEAAACCIP+vbkhAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADwaBKyAAEWSyeZAAAAAElFTkSuQmCC",
        )
        const val SYNC_ENDPOINT = "http://127.0.0.1:18080/"
        const val SYNC_USER = "test"
        const val SYNC_PW = "test123"
    }

    private fun syncReachable(): Boolean =
        try {
            val c = (java.net.URI(SYNC_ENDPOINT).toURL().openConnection() as java.net.HttpURLConnection)
            c.connectTimeout = 500; c.readTimeout = 500; c.requestMethod = "GET"; c.connect()
            c.responseCode; c.disconnect(); true
        } catch (_: Exception) {
            false
        }

    private fun syncConfig() = SyncConfig(SYNC_ENDPOINT, SYNC_USER, SYNC_PW)

    @Test
    fun `studyStart throws NeedsAttention when the controller is latched`() {
        val controller = SyncController(syncConfig(), EngineHolder).apply {
            setNeedsAttentionForTest(true)
        }
        val syncApi = LocalEngineApi(EngineHolder, controller)
        val deckId = seedNote("Latched", "q", "a")
        // The frozen contract: a latched FULL_* requirement surfaces as a BridgeError,
        // NOT a Failed answer path — StudyMachine already maps transport BridgeErrors.
        assertFailsWith<com.dvdutch.recall.api.BridgeError.NeedsAttention> {
            runBlocking { syncApi.studyStart(deckId) }
        }
    }

    @Test
    fun `studyStart with a configured controller syncs and reports synced live`() {
        org.junit.Assume.assumeTrue("sync server not reachable", syncReachable())
        val controller = SyncController(syncConfig(), EngineHolder)
        // Establish lineage so the study-start sync is a clean normal sync.
        runBlocking { controller.fullSync(upload = true) }
        val syncApi = LocalEngineApi(EngineHolder, controller)
        val deckId = seedNote("SyncedStudy", "q", "a")
        val resp = runBlocking { syncApi.studyStart(deckId) }
        assertTrue(resp.sync.synced, "study-start sync must succeed, got: ${resp.sync.detail}")
        assertTrue(resp.counts.new >= 1, "the seeded new card must appear in counts")
    }

    @Test
    fun `studyFinish syncs and writes a best-effort backup file live`() {
        org.junit.Assume.assumeTrue("sync server not reachable", syncReachable())
        val controller = SyncController(syncConfig(), EngineHolder)
        runBlocking { controller.fullSync(upload = true) }
        val backupDir = tmpDir.resolve("backups")
        Files.createDirectories(backupDir)
        val syncApi = LocalEngineApi(EngineHolder, controller, backupFolder = backupDir.toString())
        val resp = runBlocking { syncApi.studyFinish() }
        assertTrue(resp.synced, "study-finish sync must succeed, got: ${resp.detail}")
        // createBackup(force=false, waitForCompletion=false) writes into the folder.
        // waitForCompletion=false means the file may lag; poll briefly for it.
        var backups = backupDir.toFile().listFiles()?.toList().orEmpty()
        var tries = 0
        while (backups.isEmpty() && tries < 50) {
            Thread.sleep(100); tries++
            backups = backupDir.toFile().listFiles()?.toList().orEmpty()
        }
        assertTrue(backups.isNotEmpty(), "createBackup must have written a backup into $backupDir")
    }
}
