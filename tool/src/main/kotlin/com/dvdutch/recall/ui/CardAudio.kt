package com.dvdutch.recall.ui

import com.dvdutch.recall.api.CardPayload
import com.dvdutch.recall.api.UnsupportedNode

/**
 * The ordered `[sound:]` media filenames that should be playing for the side of
 * [card] currently on screen: the front's audio while the question is shown, the
 * back's audio once it's revealed. This is the single source of truth for both
 * auto-play (which list to hand [com.dvdutch.recall.audio.CardAudioPlayer.play])
 * and the replay affordance (which list a tap replays), so the two never diverge.
 *
 * Pure and Compose-free so the front/back selection is unit-testable without the
 * Android runtime or a live study session.
 */
fun activeSideAudio(card: CardPayload, showBack: Boolean): List<String> =
    if (showBack) card.backAudio else card.frontAudio

/**
 * True when the side of [card] currently on screen has at least one playable audio
 * file — i.e. the replay affordance should be offered. TTS-only sides carry no
 * `[sound:]` filenames (Task 1 skipped TTS tags), so they read as "no audio" here;
 * that limitation is intentional and noted in the task report.
 */
fun sideHasAudio(card: CardPayload, showBack: Boolean): Boolean =
    activeSideAudio(card, showBack).isNotEmpty()

/**
 * The single-element play list for the inline replay glyph at track [track] within
 * [sideAudio] (the side's ordered `[sound:]` list). Returns exactly that one track's
 * filename so a tap plays THAT sound only — a word-audio glyph plays the word file, a
 * sentence-audio glyph the sentence file. An out-of-range index yields an empty list (a
 * safe no-op), so a defensive/positionless marker can never crash the tap. Pure and
 * Compose-free so the index→filename map is unit-tested without a live player.
 */
fun trackFilenames(sideAudio: List<String>, track: Int): List<String> =
    sideAudio.getOrNull(track)?.let { listOf(it) } ?: emptyList()

/**
 * Whether the currently-shown side needs the AGGREGATE bottom "REPLAY AUDIO" row. With
 * inline per-sound glyphs (AnkiDroid parity) every av tag normally gets an inline home, so
 * this is false and no bottom row appears. It is true ONLY when the engine appended the
 * defensive aggregate marker [UnsupportedNode]`("audio")` — i.e. some av tag was
 * positionless — so those sides keep the whole-side replay affordance as a fallback. Pure
 * and Compose-free so the gating is unit-tested.
 */
fun sideNeedsAggregateReplay(card: CardPayload, showBack: Boolean): Boolean =
    (if (showBack) card.back else card.front)
        .any { it is UnsupportedNode && it.kind == "audio" }
