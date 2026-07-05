# Recall

[![CI](https://github.com/ChopinDavid/recall-lightos/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ChopinDavid/recall-lightos/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/ChopinDavid/recall-lightos/branch/main/graph/badge.svg)](https://codecov.io/gh/ChopinDavid/recall-lightos)

A review-only, [Anki](https://apps.ankiweb.net/)-compatible spaced-repetition client for **LightOS** (the Light Phone III), built on the [light-sdk](https://github.com/lightphone/light-sdk).

*Working title. Not affiliated with Anki/Ankitects — "Anki" is used only to describe compatibility.*

## Demo

https://github.com/ChopinDavid/recall-lightos/raw/assets/demo-v2.mov

## What it does

Study your due Anki cards on the Light Phone. Deck creation, editing, and browsing stay on desktop/AnkiDroid — Recall is deliberately minimal, in line with Light's ethos. No editor, no browser, no statistics: just your reviews.

- **Text, cloze, images, audio** — auto-play on show/reveal with a replay control
- **Image occlusion** — native Compose mask rendering (no WebView), including Image Occlusion Enhanced decks with fractional coordinates; the tested mask is highlighted
- **Math** — MathJax subset rendered as Unicode (x², H₂O, α, ≤, ∑); complex 2D math degrades to readable text
- **Review integrity** — undo last grade, bury/suspend/mark, live due counts, sessions run until the deck is done
- **Offline-first** — the collection lives on the phone; sync happens at session start/finish

## How it's built

Everything scheduling- and sync-related is **upstream Anki code, never reimplemented**:

- Anki's official Rust backend runs on-device via the AnkiDroid project's published bindings ([`anki-android-backend`](https://github.com/david-allison/anki-android-backend)), which Light has merged to the SDK dependency allowlist ([light-sdk#44](https://github.com/lightphone/light-sdk/pull/44)).
- Sync is rslib's own sync client — byte-for-byte the same code path AnkiDroid uses. The only write the client ever performs is `answerCard` with the scheduling states the backend itself issued.
- Card HTML is compiled to native Compose by a small renderer, parity-tested against a Python reference over 22,000+ real card sides.

Currently syncs with **self-hosted sync servers** ([Anki sync server docs](https://docs.ankiweb.net/sync-server.html)). AnkiWeb access requires permission from Ankitects and has been requested.

## Status

Working on the Light Phone III emulator; real-device deployment is pending Light's third-party tool infrastructure. Not yet distributed.

Known limitations: content that requires a browser engine doesn't render (deck `<script>`s, full MathJax typesetting, CSS-layout-art decks); TTS-only audio is unsupported.

## Layout

- `tool/` — the Recall app (all app code lives here)
- everything else is the light-sdk fork it builds against

## License

AGPL-3.0, like the Anki ecosystem it builds on.
