# Syncing Recall with your own server

Recall keeps your whole collection on the phone, so studying works offline.
Syncing keeps that collection in step with the rest of your Anki life
(desktop, AnkiDroid). In 0.1, Recall syncs with **self-hosted Anki sync
servers only** — AnkiWeb is not yet available as a sync target.

The server is Anki's own: the same sync-server code that ships inside Anki
desktop, run by you, on a machine your phone can reach. Everything below
takes about ten minutes.

## 1. Run the sync server on your computer

You need Python 3.9+ on any always-on-ish machine on your network (a
desktop, a home server, a Raspberry Pi).

```bash
pip install anki

SYNC_USER1=you:your-password \
SYNC_BASE=~/anki-sync-data \
SYNC_HOST=0.0.0.0 \
SYNC_PORT=8080 \
python -m anki.syncserver
```

- `SYNC_USER1` is `username:password` — you'll enter these in Recall.
- `SYNC_BASE` is where the server stores collections. Back it up like you'd
  back up any Anki data.
- `SYNC_HOST=0.0.0.0` makes the server reachable from other devices on your
  network (the default binds localhost only).
- More options (multiple users, TLS): the official manual,
  <https://docs.ankiweb.net/sync-server.html>.

Find your machine's LAN address (e.g. `192.168.1.20`) — on macOS:
`ipconfig getifaddr en0`. Your endpoint is then `http://192.168.1.20:8080/`.

## 2. Seed the server from Anki desktop

Do the first upload from desktop so the server holds your full collection
**including media** (Recall downloads media but does not upload it in 0.1):

1. In Anki desktop: **Preferences → Syncing → Self-hosted sync server**, and
   enter the endpoint from step 1.
2. Restart Anki, press **Sync**, log in with your `SYNC_USER1` credentials,
   and choose **Upload to server** when asked.

## 3. Point Recall at it

1. Open Recall on the phone. On first run (or via the **gear → Settings**),
   enter:
   - **Endpoint:** `http://192.168.1.20:8080/` (your address from step 1)
   - **Username / password:** your `SYNC_USER1` values
2. Recall downloads the collection. Study.

After that, Recall syncs automatically when you start or finish a study
session, and any time you tap **SYNC** on the home screen. If the phone and
server ever diverge in a way that can't be merged, Recall shows a
choose-a-side screen and never picks for you.

## Notes

- **Plain HTTP is fine on a trusted network** (Recall permits `http://`
  endpoints, like AnkiDroid). If you want encryption — e.g. syncing over the
  internet — put a reverse proxy with TLS in front of the server and use an
  `https://` endpoint, or run it over a VPN/tailnet.
- **The phone and server must be reachable from each other** when you sync.
  Away from home with no tunnel, Recall simply keeps working offline and
  syncs next time you're back.
- **Reviews flow up, content flows down.** Recall only ever writes review
  answers (via Anki's own backend). Add, edit, and organize cards on
  desktop; they arrive on the phone at the next sync.
