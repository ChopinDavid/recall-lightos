package com.dvdutch.recall.engine

import anki.card_rendering.AVTag

/**
 * Extracts the ordered list of playable media filenames from a card side's
 * [AVTag]s (as returned by `Backend.extractAvTags(...).avTagsList`).
 *
 * Each `[sound:x]`/`[sound:x.mp3]` reference becomes a `SOUND_OR_VIDEO` tag whose
 * `.soundOrVideo` is the bare filename (resolved via `RecallStorage.mediaFile`
 * later, exactly like image `src`). Order is preserved so playback follows the
 * card's authored sequence. TTS tags (and any future non-sound cases) are skipped:
 * on-device TTS is out of scope for Recall's audio path.
 */
fun soundFilenames(tags: List<AVTag>): List<String> =
    tags.filter { it.hasSoundOrVideo() }.map { it.soundOrVideo }
