# Recall

[![CI](https://github.com/ChopinDavid/recall-lightos/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ChopinDavid/recall-lightos/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/ChopinDavid/recall-lightos/branch/main/graph/badge.svg)](https://codecov.io/gh/ChopinDavid/recall-lightos)

A review-only, [Anki](https://apps.ankiweb.net/)-compatible spaced-repetition client for **LightOS** (the Light Phone III), built on the [light-sdk](https://github.com/lightphone/light-sdk).

*Working title. Not affiliated with Anki/Ankitects — "Anki" is used only to describe compatibility.*

## Demo

https://github.com/user-attachments/assets/dab2ba62-8b0e-4165-9af4-c1d4fbb638e8

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

Syncs with **self-hosted sync servers** — see **[Syncing Recall with your own server](docs/sync-server.md)** for the ten-minute setup (server on your machine, then the app). Syncing with **AnkiWeb is not yet available**: it requires permission from Ankitects, which we have requested and are currently awaiting. Until then, a self-hosted server is the sync path.

Scheduling configuration — including FSRS parameters, desired retention, and optimization — is managed in Anki desktop; Recall applies whatever the synced collection specifies (FSRS and SM-2 both supported, via Anki's own backend).

## Status

**0.1.0** (tag `v0.1.0`): feature-complete for review-only studying and smoke-tested end-to-end on the minified release build. Runs as a daily driver on the Light Phone III emulator against a self-hosted sync server. Installation on physical LP3 hardware is pending Light's Tool Manager sideload rollout; distribution awaits Light's Tool Library. Not yet distributed.

Known limitations: content that requires a browser engine doesn't render (deck `<script>`s, full MathJax typesetting, CSS-layout-art decks); TTS-only audio is unsupported.

## Layout

- `tool/` — the Recall app (all app code lives here)
- everything else is the light-sdk fork it builds against

## License

Recall — everything under `tool/` — is **AGPL-3.0** (see [`tool/LICENSE`](tool/LICENSE)), like the Anki ecosystem it builds on. The rest of the repository is the [light-sdk](https://github.com/lightphone/light-sdk) fork it builds against, under Light's own **MIT** license (see [`LICENSE`](LICENSE)).
