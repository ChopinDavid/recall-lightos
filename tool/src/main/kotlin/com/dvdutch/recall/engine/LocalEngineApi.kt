package com.dvdutch.recall.engine

import anki.scheduler.CardAnswer
import anki.scheduler.QueuedCards
import anki.scheduler.SchedulingStates
import com.dvdutch.recall.api.AnswerIn
import com.dvdutch.recall.api.AnswerResult
import com.dvdutch.recall.api.BridgeApi
import com.dvdutch.recall.api.CardPayload
import com.dvdutch.recall.api.Counts
import com.dvdutch.recall.api.Deck
import com.dvdutch.recall.api.QueueResponse
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.StudyStartResponse
import com.dvdutch.recall.api.SyncInfo
import com.dvdutch.recall.api.UnsupportedNode
import com.dvdutch.recall.compiler.compileHtml
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.withContext
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.exceptions.BackendInvalidInputException
import net.ankiweb.rsdroid.exceptions.BackendNotFoundException
import java.util.Base64

/**
 * On-device implementation of the decks / queue / answer surface, backed directly
 * by rslib through [EngineHolder]. It is the local twin of
 * [com.dvdutch.recall.api.BridgeClient]: same method names, same DTOs, same
 * `states`-as-opaque-base64 contract, so [com.dvdutch.recall.study.StudyMachine]
 * and the rest of the app cannot tell which backs a session.
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
class LocalEngineApi(private val holder: EngineHolder) : BridgeApi {

    private companion object {
        val LABEL_KEYS = listOf("again", "hard", "good", "easy")
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
        )
    }

    /** MUST be called on [EngineHolder.lane]. */
    private fun cardPayload(backend: Backend, entry: QueuedCards.QueuedCard): CardPayload {
        val card = entry.card
        val rendered = backend.renderExistingCard(card.id, false, true)
        val css = rendered.css
        val front = compileSide(backend, assembleCardSide(rendered.questionNodesList), "front", css)
        val back = compileSide(backend, assembleCardSide(rendered.answerNodesList), "back", css)
        val labels = backend.describeNextStates(entry.states)
        return CardPayload(
            cardId = card.id,
            noteId = card.noteId,
            front = front,
            back = back,
            states = Base64.getEncoder().encodeToString(entry.states.toByteArray()),
            nextDueLabels = LABEL_KEYS.zip(labels).toMap(),
        )
    }

    /**
     * Compiles one card side to render nodes, replacing Anki's audio references with
     * a single `audio` placeholder. Mirrors the bridge's
     * `anki_bridge.rendering.compile_side`: `stripAvTags` removes the `[sound:...]`
     * references (they must never reach the phone as literal text — it is the direct
     * analogue of pylib's `strip_av_refs`), and if the stripped text differs from the
     * input then a reference was present, so one `unsupported/audio` node is appended
     * so audio never silently vanishes.
     *
     * NOTE: `stripAvTags` — not `extractAvTags` — is the correct strip here.
     * `extractAvTags` rewrites `[sound:x]` into an `[anki:play:...]` marker that it
     * does NOT then remove, so its `.text` would leak that marker into the compiled
     * output; `stripAvTags` removes the reference outright. MUST be called on
     * [EngineHolder.lane].
     */
    private fun compileSide(
        backend: Backend,
        html: String,
        side: String,
        css: String,
    ): List<RenderNode> {
        val stripped = backend.stripAvTags(html)
        val nodes = compileHtml(stripped, side, css).toMutableList()
        if (stripped != html) {
            nodes.add(UnsupportedNode("audio"))
        }
        return nodes
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
     * Stub filled by Task 3 (sync). For now it selects the deck and reports its due
     * counts with a "sync not configured" result. Mirrors the bridge's
     * `/v1/study/start` shape.
     */
    override suspend fun studyStart(deckId: Long): StudyStartResponse = withContext(holder.lane) {
        val backend = holder.backend()
        backend.setCurrentDeck(deckId)
        val queued = backend.getQueuedCards(1, false)
        StudyStartResponse(
            counts = Counts(
                new = queued.newCount,
                learning = queued.learningCount,
                review = queued.reviewCount,
            ),
            sync = SyncInfo(synced = false, detail = "sync not configured"),
        )
    }

    /**
     * Stub filled by Task 3 (sync). Reports "sync not configured" until sync lands.
     */
    override suspend fun studyFinish(): SyncInfo =
        SyncInfo(synced = false, detail = "sync not configured")
}
