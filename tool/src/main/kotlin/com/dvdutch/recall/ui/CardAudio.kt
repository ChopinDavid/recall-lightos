package com.dvdutch.recall.ui

import com.dvdutch.recall.api.CardPayload

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
