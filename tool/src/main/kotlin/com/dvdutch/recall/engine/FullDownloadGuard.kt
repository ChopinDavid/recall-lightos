package com.dvdutch.recall.engine

/**
 * The pure decision at the heart of the empty-server download guard.
 *
 * ## Why this exists
 * A full download (`fullUploadOrDownload(upload=false)`) replaces the ENTIRE local
 * collection with the server's copy. Three times a client full-downloaded an EMPTY
 * server state and silently wiped a populated phone to "no decks" — root cause was an
 * orphaned/reset test server, but the failure MODE is general: any user pointed at a
 * wrong, fresh, or reset self-hosted endpoint would eat their phone collection the same
 * way. rsdroid exposes NO cheap pre-check for the server's collection size before the
 * transfer (the sync protos carry only `required`/USN — the rslib `SyncMeta`
 * `empty`/`collection_bytes` fields are not surfaced), so the guard is enforced by
 * downloading to a backed-up collection and validating the RESULT, then rolling back if
 * this decision trips. See [SyncController.fullDownload].
 *
 * ## The decision
 * Trip only when a SUBSTANTIAL local collection is about to be replaced by an EMPTY one.
 * The condition is deliberately narrow to avoid false positives:
 *   - first-run (local empty) never trips — there is nothing to lose;
 *   - a non-empty server never trips — a real, if small, download is legitimate;
 *   - only local-had-cards → downloaded-empty trips, because THAT is the silent wipe.
 */
object FullDownloadGuard {

    /**
     * True when downloading would replace a populated phone ([localCount] cards) with an
     * EMPTY server collection ([downloadedCount] == 0). Any populated local collection
     * counts: the incident's failure mode is general, not specific to large collections.
     */
    fun tripsOn(localCount: Int, downloadedCount: Int): Boolean =
        localCount > 0 && downloadedCount == 0
}
