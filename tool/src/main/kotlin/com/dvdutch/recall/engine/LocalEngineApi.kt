package com.dvdutch.recall.engine

import anki.scheduler.BuryOrSuspendCardsRequest
import anki.scheduler.CardAnswer
import anki.scheduler.QueuedCards
import anki.scheduler.SchedulingStates
import com.dvdutch.recall.api.AnswerIn
import com.dvdutch.recall.api.AnswerResult
import com.dvdutch.recall.api.EngineApi
import com.dvdutch.recall.api.BridgeError
import com.dvdutch.recall.api.CardPayload
import com.dvdutch.recall.api.Counts
import com.dvdutch.recall.api.Deck
import com.dvdutch.recall.api.OcclusionNode
import com.dvdutch.recall.api.QueueResponse
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.api.StudyStartResponse
import com.dvdutch.recall.api.SyncInfo
import com.dvdutch.recall.api.UndoResult
import com.dvdutch.recall.api.UnsupportedNode
import com.dvdutch.recall.compiler.compileHtml
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.withContext
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.exceptions.BackendInvalidInputException
import net.ankiweb.rsdroid.exceptions.BackendNotFoundException
import java.util.Base64

/**
 * On-device implementation of the decks / queue / answer surface ([EngineApi]),
 * backed directly by rslib through [EngineHolder]. It preserves the frozen
 * former-bridge contract verbatim — same method names, same DTOs, same
 * `states`-as-opaque-base64 boundary — so [com.dvdutch.recall.study.StudyMachine]
 * and the rest of the app are unchanged by the move on-device.
 *
 * Every engine touch is confined to [EngineHolder.lane] (the serial native lane) via
 * `withContext(holder.lane) { ... }`; the raw [Backend] handle is never used off-lane.
 *
 * ## Semantic reference
 * The behaviour mirrors the Anki bridge's Python routes
 * (`anki_bridge.routes.{decks,queue,answer}`) so the two engines are
 * interchangeable:
 *   - deck-tree flattening: `_flatten` (decks.py) — full `::` names, empty Default hidden;
 *   - queue payload assembly (queue.py) — render → av-tag strip → compile → states + labels;
 *   - answer application (answer.py) — parse states, build [CardAnswer], apply.
 *
 * ## Contract change vs the bridge: no UUID dedup ledger
 * The bridge keeps a per-session `answers_store` and returns `"duplicate"` for a
 * previously-seen answer uuid, because HTTP retries can resubmit an answer. Here
 * there is no network in between: [answer] runs on the serial engine lane in the
 * same process that issued the queue, in submission order, so a duplicate
 * submission cannot occur by construction. The ledger is therefore intentionally
 * NOT ported, and `"duplicate"` is never returned. The `uuid` field is still
 * carried through unchanged so results correlate 1:1 with requests.
 */
class LocalEngineApi(
    private val holder: EngineHolder,
    /**
     * The sync layer, or null when sync is unconfigured. When null, [studyStart]/
     * [studyFinish] behave exactly like the pre-sync stub (`"sync not configured"`),
     * so an unconfigured on-device session still runs against the local collection.
     */
    private val sync: SyncController? = null,
    /**
     * Directory under tool storage where [studyFinish] writes a best-effort backup
     * via `createBackup`. Null skips the backup (e.g. sync unconfigured or no storage).
     */
    private val backupFolder: String? = null,
) : EngineApi {

    private companion object {
        val LABEL_KEYS = listOf("again", "hard", "good", "easy")

        /**
         * The tag AnkiDroid (and desktop Anki) uses for a marked note. Toggling this tag
         * on the note IS "Mark Note"; a note carrying it renders with the star indicator.
         */
        const val MARK_TAG = "marked"
    }

    /**
     * Flattens the due deck tree exactly like the bridge's `_flatten`: full `::`
     * names, hiding the unused Default deck (id 1 with no children and zero due
     * counts). Mirrors `anki_bridge.routes.decks`.
     */
    suspend fun decks(): List<Deck> = withContext(holder.lane) {
        val out = mutableListOf<Deck>()
        // deckTree(now) with `now` = current epoch SECONDS computes the DUE tree
        // (new/learning/review counts), matching the bridge's `col.sched.deck_due_tree()`.
        // deckTree(0) would return the bare structural tree with all counts zero.
        val nowSecs = System.currentTimeMillis() / 1000
        flatten(holder.backend().deckTree(nowSecs), prefix = "", out)
        out
    }

    private fun flatten(node: anki.decks.DeckTreeNode, prefix: String, out: MutableList<Deck>) {
        for (child in node.childrenList) {
            val fullName = if (prefix.isEmpty()) child.name else "$prefix::${child.name}"
            val isUnusedDefault = child.deckId == 1L &&
                child.childrenList.isEmpty() &&
                (child.newCount + child.learnCount + child.reviewCount) == 0
            if (!isUnusedDefault) {
                out.add(
                    Deck(
                        id = child.deckId,
                        name = fullName,
                        new = child.newCount,
                        learning = child.learnCount,
                        review = child.reviewCount,
                    ),
                )
            }
            flatten(child, fullName, out)
        }
    }

    /**
     * Builds the study queue: `getQueuedCards` then, per entry, a self-contained
     * [CardPayload] (rendered front/back nodes, opaque base64 `states`, next-due
     * labels). Mirrors `anki_bridge.routes.queue`.
     */
    override suspend fun queue(limit: Int): QueueResponse = withContext(holder.lane) {
        val backend = holder.backend()
        val queued = backend.getQueuedCards(limit, false)
        val cards = queued.cardsList.map { entry -> cardPayload(backend, entry) }
        QueueResponse(
            cards = cards,
            counts = Counts(
                new = queued.newCount,
                learning = queued.learningCount,
                review = queued.reviewCount,
            ),
            // True only when the TOP undo op is an Answer Card op — the gate for the
            // UNDO control. A non-empty undo stack is NOT sufficient: opening a deck
            // pushes a "Select Deck" config op, so `undo.isNotEmpty()` would falsely
            // report undoability before any card was answered (and would offer to undo
            // the deck selection). We compare the localized op label against rslib's
            // own `actionsAnswerCard()` translation so this stays correct in any locale
            // and is exactly the op AnkiDroid's toolbar Undo would revert here.
            undoableAnswer = isAnswerUndoable(backend),
        )
    }

    /**
     * Whether rslib's top undo op is an Answer Card op (vs. a "Select Deck" config op
     * or an empty stack). Compares the localized undo label to [Backend]'s own
     * `actionsAnswerCard()` translation, so it is locale-correct and matches the exact
     * op the toolbar Undo would revert. MUST be called on [EngineHolder.lane].
     */
    private fun isAnswerUndoable(backend: Backend): Boolean =
        backend.getUndoStatus().undo == backend.tr.actionsAnswerCard()

    /** MUST be called on [EngineHolder.lane]. */
    private fun cardPayload(backend: Backend, entry: QueuedCards.QueuedCard): CardPayload {
        val card = entry.card
        occlusionPayload(backend, entry)?.let { return it }
        val rendered = backend.renderExistingCard(card.id, false, true)
        val css = rendered.css
        // Assemble the question HTML first: rslib leaves the answer's {{FrontSide}}
        // replacement node empty, so we must inject the rendered front into the back
        // (mirrors TemplateManager.applyCustomFilters(anodes, frontSide = qout.text)).
        // Without this the whole front line vanishes from the revealed answer.
        val frontHtml = assembleCardSide(rendered.questionNodesList)
        val front = compileSide(backend, frontHtml, "front", css, isQuestion = true)
        val backHtml = assembleCardSide(rendered.answerNodesList, frontSide = frontHtml)
        val back = compileSide(backend, backHtml, "back", css, isQuestion = false)
        val labels = backend.describeNextStates(entry.states)
        return CardPayload(
            cardId = card.id,
            noteId = card.noteId,
            front = front.nodes,
            back = back.nodes,
            states = Base64.getEncoder().encodeToString(entry.states.toByteArray()),
            nextDueLabels = LABEL_KEYS.zip(labels).toMap(),
            frontAudio = front.audio,
            backAudio = back.audio,
            marked = isMarked(backend, card.noteId),
        )
    }

    /**
     * Whether [noteId]'s note carries the [MARK_TAG] "marked" tag — i.e. AnkiDroid's Mark
     * Note state. Reads the note's structured tag list (never a string search), so it stays
     * correct regardless of tag order/spacing. MUST be called on [EngineHolder.lane].
     */
    private fun isMarked(backend: Backend, noteId: Long): Boolean =
        backend.getNote(noteId).tagsList.any { it == MARK_TAG }

    /**
     * If [entry]'s card belongs to an Image Occlusion notetype, builds its self-contained
     * [CardPayload] natively from rslib's structured `getImageOcclusionNote` and returns it;
     * otherwise returns null so the caller falls through to the normal HTML→node path.
     *
     * This deliberately BYPASSES [compileHtml]: the occlusion template is canvas + JS we
     * cannot execute (the source of the current `▢ [canvas]` + "No cloze found" breakage),
     * but every fact we need — mask geometry in natural pixels, the tested ordinal, the
     * hide mode, header/back-extra, and the image bytes — is available structured.
     *
     * Detection is by `Notetype.config.originalStockKind == ORIGINAL_STOCK_KIND_IMAGE_OCCLUSION`
     * (locale-independent, unlike the notetype name; the IO notetype's *kind* is KIND_CLOZE,
     * so only originalStockKind distinguishes it). MUST be called on [EngineHolder.lane].
     */
    private fun occlusionPayload(backend: Backend, entry: QueuedCards.QueuedCard): CardPayload? {
        val card = entry.card
        // The scheduler Card proto carries no notetype id, so resolve it via the note.
        val note = backend.getNote(card.noteId)
        val notetype = backend.getNotetype(note.notetypeId)
        if (notetype.config.originalStockKind !=
            anki.notetypes.StockNotetype.OriginalStockKind.ORIGINAL_STOCK_KIND_IMAGE_OCCLUSION
        ) {
            return null
        }
        val resp = backend.getImageOcclusionNote(card.noteId)
        // A backend-side error (e.g. malformed field) → fall back to the generic path
        // rather than crash the whole queue on one bad note.
        if (resp.hasError() || !resp.hasNote()) return null
        val occNote = resp.note

        val parsed = ParsedOcclusion(
            occludeInactive = occNote.occludeInactive,
            occlusions = occNote.occlusionsList.map { occ ->
                RawOcclusion(
                    ordinal = occ.ordinal,
                    shapes = occ.shapesList.map { sh ->
                        RawShape(sh.shape, sh.propertiesList.associate { it.name to it.value })
                    },
                )
            },
        )
        // card.templateIdx is the 0-based card ordinal; the tested occlusion is 1-based.
        val tested = card.templateIdx + 1
        val dims = imageDims(occNote.imageData.toByteArray()) ?: ImageDims(0, 0)

        fun sideNode(isBack: Boolean) = OcclusionNode(
            image = occNote.imageFileName,
            naturalW = dims.width,
            naturalH = dims.height,
            shapes = resolveShapes(parsed, tested, isBack, dims.width, dims.height),
            side = if (isBack) "back" else "front",
        )

        // Header (both sides) and Back Extra (back only) become ordinary text nodes.
        val header = textNodeOrNull(occNote.header)
        val backExtra = textNodeOrNull(occNote.backExtra)
        val front = buildList {
            header?.let { add(it) }
            add(sideNode(isBack = false))
        }
        val back = buildList {
            header?.let { add(it) }
            add(sideNode(isBack = true))
            backExtra?.let { add(it) }
        }
        val labels = backend.describeNextStates(entry.states)
        return CardPayload(
            cardId = card.id,
            noteId = card.noteId,
            front = front,
            back = back,
            states = Base64.getEncoder().encodeToString(entry.states.toByteArray()),
            nextDueLabels = LABEL_KEYS.zip(labels).toMap(),
            frontAudio = emptyList(),
            backAudio = emptyList(),
            // The note was already fetched above for notetype detection; reuse its tags.
            marked = note.tagsList.any { it == MARK_TAG },
        )
    }

    /** A single-run [TextNode] for non-blank text, or null when the text is blank. */
    private fun textNodeOrNull(text: String): RenderNode? =
        text.trim().takeIf { it.isNotEmpty() }?.let { TextNode(listOf(TextRun(it))) }

    /** One compiled card side: its render nodes plus the ordered audio filenames. */
    private data class CompiledSide(val nodes: List<RenderNode>, val audio: List<String>)

    /**
     * Compiles one card side to render nodes AND extracts its ordered `[sound:]`
     * media filenames. Mirrors the bridge's `anki_bridge.rendering.compile_side` for
     * the text path, extended with the filename list for on-device playback.
     *
     * Two backend calls, by design, because they serve different needs:
     *   - `stripAvTags(html)` produces the MARKER-FREE text to compile. This is the
     *     direct analogue of pylib's `strip_av_refs`: `[sound:x]` references must
     *     never reach the phone as literal text.
     *   - `extractAvTags(html, isQuestion).avTagsList` yields the ordered filenames
     *     ([soundFilenames]) that populate `front_audio`/`back_audio`.
     *
     * Why NOT use `extractAvTags(...).text` for the text too: verified on the real
     * backend, `.text` rewrites `[sound:x]` into an `[anki:play:q:N]` marker that it
     * does NOT then remove, so it would leak that marker into the compiled output.
     * `stripAvTags` removes the reference outright, so it stays the source of the
     * compiled text. Both are backend calls and MUST run on [EngineHolder.lane].
     *
     * The `unsupported/audio` marker node is still emitted here (a side-has-audio
     * signal), but Task 3 retired its `▢ [audio]` UI: `NodeComposables` now drops the
     * `audio` kind, and StudyScreen renders a real replay affordance driven by these
     * `front_audio`/`back_audio` filenames instead.
     */
    private fun compileSide(
        backend: Backend,
        html: String,
        side: String,
        css: String,
        isQuestion: Boolean,
    ): CompiledSide {
        val stripped = backend.stripAvTags(html)
        val nodes = compileHtml(stripped, side, css).toMutableList()
        val audio = soundFilenames(backend.extractAvTags(html, isQuestion).avTagsList)
        if (stripped != html) {
            nodes.add(UnsupportedNode("audio"))
        }
        return CompiledSide(nodes, audio)
    }

    /**
     * Applies a batch of answers in order, returning one [AnswerResult] per input.
     * Mirrors `anki_bridge.routes.answer._apply_one` minus the uuid dedup ledger
     * (see the class contract note):
     *   - undecodable `states`  → `stale` (can never be applied safely);
     *   - card not found        → `gone` ([BackendNotFoundException]);
     *   - state mismatch        → `stale` ([BackendInvalidInputException] — rslib's
     *     "card was modified" error);
     *   - otherwise             → `applied`.
     * An unexpected engine failure halts the batch: this item gets `"error"` and
     * items after it get no result entry (the caller retries them).
     */
    override suspend fun answer(answers: List<AnswerIn>): List<AnswerResult> =
        withContext(holder.lane) {
            val backend = holder.backend()
            val results = mutableListOf<AnswerResult>()
            for (ans in answers) {
                val status = try {
                    applyOne(backend, ans)
                } catch (t: Throwable) {
                    results.add(AnswerResult(ans.uuid, "error"))
                    break
                }
                results.add(AnswerResult(ans.uuid, status))
            }
            results
        }

    /**
     * Reverts the last ANSWER via rslib's OWN undo (`Backend.undo()` — the same op
     * AnkiDroid's toolbar Undo drives), NEVER a local reconstruction: rslib pops its
     * own undo stack, un-answers the card, and restores the due counts.
     *
     * This is a REVIEW-ONLY undo: it pops the stack only when the top op is an Answer
     * Card op (see [isAnswerUndoable]). Anything else — an empty stack, or a lingering
     * "Select Deck" config op from opening the session — is a safe no-op yielding
     * [UndoResult]`(undone = false)`, so undo can never revert the deck selection or
     * any non-answer op. After a successful undo we re-check [isAnswerUndoable] to
     * report whether a FURTHER answer remains undoable, so the caller can keep
     * offering multi-step undo (or hide the control once the answers are exhausted).
     */
    override suspend fun undo(): UndoResult = withContext(holder.lane) {
        val backend = holder.backend()
        // Guard at the engine boundary: only an answer is undoable here. If the top op
        // is not an Answer Card op, do NOT pop the stack — a no-op, not a deck-undo.
        if (!isAnswerUndoable(backend)) {
            return@withContext UndoResult(undone = false, undoableAnswer = false)
        }
        try {
            backend.undo()
        } catch (_: net.ankiweb.rsdroid.BackendException.BackendUndoEmptyException) {
            // Defensive: the stack emptied between the check and the call.
            return@withContext UndoResult(undone = false, undoableAnswer = false)
        }
        UndoResult(undone = true, undoableAnswer = isAnswerUndoable(backend))
    }

    /**
     * Buries [cardId] via rslib's `buryOrSuspendCards` in BURY_USER mode (the exact op
     * AnkiDroid's Bury Card drives) — the card leaves the queue until tomorrow and the
     * change is recorded for sync. Confined to [EngineHolder.lane]. The empty noteIds list
     * is deliberate: we bury the single card, not the whole note.
     */
    override suspend fun buryCard(cardId: Long): Unit = withContext(holder.lane) {
        holder.backend().buryOrSuspendCards(
            listOf(cardId),
            emptyList(),
            BuryOrSuspendCardsRequest.Mode.BURY_USER,
        )
    }

    /**
     * Suspends [cardId] via rslib's `buryOrSuspendCards` in SUSPEND mode (the exact op
     * AnkiDroid's Suspend Card drives) — the card leaves the queue until it is unsuspended
     * (on desktop) and the change is recorded for sync. Confined to [EngineHolder.lane].
     */
    override suspend fun suspendCard(cardId: Long): Unit = withContext(holder.lane) {
        holder.backend().buryOrSuspendCards(
            listOf(cardId),
            emptyList(),
            BuryOrSuspendCardsRequest.Mode.SUSPEND,
        )
    }

    /**
     * Toggles the [MARK_TAG] "marked" tag on [noteId]'s note (the exact op AnkiDroid's Mark
     * Note drives) and returns whether the note is NOW marked. Reads the current tags via
     * `getNote`, then `removeNoteTags`/`addNoteTags` for the single note; the change is
     * recorded for sync. Confined to [EngineHolder.lane].
     */
    override suspend fun toggleMark(noteId: Long): Boolean = withContext(holder.lane) {
        val backend = holder.backend()
        val wasMarked = isMarked(backend, noteId)
        if (wasMarked) {
            backend.removeNoteTags(listOf(noteId), MARK_TAG)
        } else {
            backend.addNoteTags(listOf(noteId), MARK_TAG)
        }
        !wasMarked
    }

    /** Applies one answer; MUST be called on [EngineHolder.lane]. */
    private fun applyOne(backend: Backend, ans: AnswerIn): String {
        val states = try {
            SchedulingStates.parseFrom(Base64.getDecoder().decode(ans.states))
        } catch (_: IllegalArgumentException) {
            return "stale" // undecodable base64
        } catch (_: InvalidProtocolBufferException) {
            return "stale" // decodable base64 but not a valid SchedulingStates proto
        }
        val rating = ratingOf(ans.rating)
        val newState = when (ans.rating) {
            "again" -> states.again
            "hard" -> states.hard
            "good" -> states.good
            "easy" -> states.easy
            else -> return "stale"
        }
        val proto = CardAnswer.newBuilder()
            .setCardId(ans.cardId)
            .setCurrentState(states.current)
            .setNewState(newState)
            .setRating(rating)
            .setAnsweredAtMillis(ans.answeredAt)
            .setMillisecondsTaken(ans.msTaken.toInt())
            .build()
        return try {
            backend.answerCard(proto)
            "applied"
        } catch (_: BackendNotFoundException) {
            "gone"
        } catch (_: BackendInvalidInputException) {
            // rslib rejected the answer because the card changed since these states
            // were issued (stale states).
            "stale"
        }
    }

    private fun ratingOf(rating: String): CardAnswer.Rating = when (rating) {
        "again" -> CardAnswer.Rating.AGAIN
        "hard" -> CardAnswer.Rating.HARD
        "good" -> CardAnswer.Rating.GOOD
        "easy" -> CardAnswer.Rating.EASY
        else -> CardAnswer.Rating.AGAIN
    }

    /**
     * Opens a study session: sync the collection up front (bringing down any reviews
     * done elsewhere), select the deck, and report its due counts. Mirrors the bridge's
     * `/v1/study/start` (`anki_bridge.routes.study.study_start`):
     *   - gate on [SyncController.needsAttention] FIRST — a latched FULL_* requirement
     *     must block study until it is resolved out-of-band. The bridge raises HTTP 503
     *     `needs_attention`; here we throw [BridgeError.NeedsAttention], which
     *     [com.dvdutch.recall.study.StudyMachine] already maps as a transport failure
     *     (it catches [BridgeError] from `studyStart`), keeping the frozen contract;
     *   - then [SyncController.sync] (non-fatal: studying proceeds even if it fails);
     *   - gate AGAIN — the sync itself may have just latched needsAttention on a FULL_*;
     *   - then `setCurrentDeck` + counts, carrying the sync outcome in the response.
     *
     * With no [sync] configured this degrades to the original stub behaviour: select the
     * deck, report counts, `"sync not configured"`.
     */
    override suspend fun studyStart(deckId: Long): StudyStartResponse {
        val controller = sync
        val syncInfo = if (controller == null) {
            SyncInfo(synced = false, detail = "sync not configured")
        } else {
            if (controller.needsAttention.value) throw BridgeError.NeedsAttention
            val info = controller.sync(media = true)
            if (controller.needsAttention.value) throw BridgeError.NeedsAttention
            info
        }
        return withContext(holder.lane) {
            val backend = holder.backend()
            backend.setCurrentDeck(deckId)
            val queued = backend.getQueuedCards(1, false)
            StudyStartResponse(
                counts = Counts(
                    new = queued.newCount,
                    learning = queued.learningCount,
                    review = queued.reviewCount,
                ),
                sync = syncInfo,
            )
        }
    }

    /**
     * Closes a study session: sync the reviews just performed up to the server, then
     * take a best-effort local backup. Mirrors the bridge's `/v1/study/finish`
     * (`anki_bridge.routes.study.study_finish`) plus an on-device backup step.
     *
     * The backup is best-effort and never fails the finish: `createBackup(folder, force
     * = false, waitForCompletion = false)` — arg meanings VERIFIED against AnkiDroid
     * libanki `Collection.createBackup` / rsdroid `Backend.createBackup(String, boolean,
     * boolean)`: `force=false` respects the user's minimum-interval so we do not spam
     * backups on every finish, and `waitForCompletion=false` returns immediately (the
     * backup runs on a backend thread) so closing the session is not blocked on disk I/O.
     * A null [backupFolder] (or no [sync]) skips the backup entirely.
     *
     * With no [sync] configured this returns the original `"sync not configured"` stub.
     */
    override suspend fun studyFinish(): SyncInfo {
        val controller = sync ?: return SyncInfo(synced = false, detail = "sync not configured")
        val info = controller.sync(media = true)
        val folder = backupFolder
        if (folder != null) {
            withContext(holder.lane) {
                try {
                    holder.backend().createBackup(folder, false, false)
                } catch (_: Throwable) {
                    // Backups are best-effort: a failure here must never fail the finish.
                }
            }
        }
        return info
    }
}
