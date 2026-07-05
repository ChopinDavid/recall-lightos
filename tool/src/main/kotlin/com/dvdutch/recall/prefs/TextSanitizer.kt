package com.dvdutch.recall.prefs

/**
 * Pure, boundary sanitizer for every Recall text field (sync endpoint / username /
 * password, on both FirstRun and Settings).
 *
 * Why this exists: the Light SDK's full-screen text editor ([LightTextInputEditor])
 * treats the keyboard's return glyph as a NEWLINE INSERT — "submit" is the separate
 * bottom control. A user who taps return expecting "done" silently embeds a `\n`
 * into their credential, which then breaks sync login. Untrimmed leading/trailing
 * whitespace breaks login the same way. The SDK editor exposes no single-line /
 * imeDone option, so we sanitize in OUR layer, on every save/submit path.
 *
 * Rules:
 *  - [sanitizeCredential]: strip ALL newline and control characters (anywhere),
 *    then trim leading/trailing whitespace. Interior spaces are PRESERVED — a
 *    password may legitimately contain them.
 *  - [sanitizeEndpoint]: as above, and additionally remove ALL interior whitespace
 *    (a URL never legitimately contains a space).
 *
 * These functions are the single choke-point; keeping them pure makes every rule
 * unit-testable on the JVM with no Compose/Android runtime.
 */
object TextSanitizer {

    /**
     * Sanitizes a credential-style value (username or password): removes every
     * newline and control character, then trims the ends. Interior spaces are kept.
     */
    fun sanitizeCredential(raw: CharSequence): String =
        raw.filterNot { it.isNewlineOrControl() }.trim().toString()

    /**
     * Sanitizes a sync-endpoint URL: strips newlines/control chars and ALL
     * whitespace (interior included), since a valid URL contains none.
     */
    fun sanitizeEndpoint(raw: CharSequence): String =
        raw.filterNot { it.isWhitespace() || it.isNewlineOrControl() }.toString()

    /**
     * True if [endpoint] is acceptable to persist: empty (not-yet-set) is allowed;
     * otherwise it must parse as an absolute http(s) URL. Call on an already
     * [sanitizeEndpoint]-cleaned value.
     */
    fun isValidEndpoint(endpoint: String): Boolean {
        if (endpoint.isEmpty()) return true
        val scheme = endpoint.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = endpoint.removePrefix("$scheme://").substringBefore('/').substringBefore(':')
        return host.isNotEmpty()
    }

    /**
     * True if [endpoint] is a non-empty http:// (not https) URL. Used to surface a
     * gentle, non-blocking warning row: self-hosted LAN sync servers legitimately
     * use plain http, so we never reject it — we just note the traffic is
     * unencrypted so the user can choose https when their server supports it. Call
     * on an already [sanitizeEndpoint]-cleaned value.
     */
    fun isInsecureEndpoint(endpoint: String): Boolean =
        endpoint.substringBefore("://", missingDelimiterValue = "").lowercase() == "http"

    /** A char that is a line break or any ISO control character (tab, etc.). */
    private fun Char.isNewlineOrControl(): Boolean =
        this == '\n' || this == '\r' || this.isISOControl()
}
