package com.dvdutch.recall.ui

/**
 * Every word of the in-app sync-setup onboarding, in one Compose-free object so
 * it is unit-testable on the JVM (the house pattern — see [SettingsMessages]).
 *
 * Why this copy is held to a higher bar than the rest of the app's strings: the
 * Light Phone III has NO browser. [SyncSetupScreen] is therefore the ONLY setup
 * guidance a device user can reach — there is no "see the docs" escape hatch
 * from the phone. A wrong command here is a dead end, so the shell commands and
 * env vars below are kept verbatim-accurate against `docs/sync-server.md` and
 * asserted character-for-character in SyncSetupMessagesTest.
 *
 * Narrative order is deliberate and load-bearing (David's spec): WHY self-hosted
 * first, in plain words; then HOW, as three numbered steps; and only last, as a
 * lightened footnote, the AnkiWeb-approval limitation. Leading with the
 * limitation would tell a new user what they cannot do before telling them what
 * to do — explicitly ruled out.
 */
object SyncSetupMessages {

    /** The screen's top-bar title. */
    const val TITLE: String = "Sync setup"

    /** The underlined tappable row on the first-run screen — phrased as the user's question. */
    const val FIRST_RUN_ROW_LABEL: String = "\u201CHow do I set this up?\u201D"

    /** The equivalent row in Settings — named for what it is, since the user is not mid-setup. */
    const val SETTINGS_ROW_LABEL: String = "sync setup help"

    // ---- Part 1: WHY SELF-HOSTED ----

    /** Heading for the opening section. */
    const val WHY_HEADING: String = "How Recall syncs"

    /**
     * The opening. Plain words, no jargon, and no mention of AnkiWeb: this
     * paragraph's whole job is to make the self-hosted model feel ordinary and
     * legible before any commands appear.
     */
    const val WHY_BODY: String =
        "Recall syncs with a small server you run yourself, on your own computer. " +
            "Your cards live in Anki on that computer, and this phone studies them. " +
            "Setting the server up takes about ten minutes, once."

    // ---- Part 2: HOW ----

    /** Heading above the three numbered steps. */
    const val HOW_HEADING: String = "Setting it up"

    /** Step 1 title. */
    const val STEP1_TITLE: String = "On your computer"

    /**
     * Step 1 lead-in. The floor is 3.10 because that is what the anki package
     * itself declares (requires_python >=3.10); an older interpreter makes pip
     * silently fall back to anki 2.1.35 (2020) and fail on ankirspy — the exact
     * trap David hit road-testing these instructions on a stock Python 3.9.
     */
    /**
     * The script route replaced the manual walk as step 1: David road-tested
     * the manual funnel through seven failures, then we automated it
     * (github.com/ChopinDavid/recall-server). The phone shows the SHORT repo
     * address, never the long curl line: the user transcribes by eye to a
     * computer, and the computer's browser gives them copy-paste there.
     */
    const val STEP1_EASY_BODY: String =
        "Open this page in a browser:"
    const val STEP1_EASY_URL: String = "github.com/ChopinDavid/recall-server"
    const val STEP1_EASY_AFTER: String =
        "Copy the one-line setup command from that page into the Terminal " +
            "app and press “Enter”. It sets the server up, keeps it " +
            "running, and prints the address, username, and password to " +
            "enter in the Anki desktop app and here."

    /** Appendix heading for the by-hand alternative. */
    const val MANUAL_HEADING: String = "Prefer to set it up by hand?"

    const val STEP1_BODY: String =
        "You need Python 3.10 or newer on a computer your phone can reach over " +
            "your home network. Then, install Anki's sync server into its own " +
            "environment:"

    /**
     * A venv, not a bare pip install: bare pip is refused outright on modern
     * Homebrew/Linux Pythons (externally managed environments), and on machines
     * with several Pythons it can quietly pick an old one. The venv pins both.
     */
    /**
     * Rendered as TWO separately-labeled commands ("First:" / "Then:"), never as
     * adjacent mono lines: at 1080px each command wraps across lines, making a
     * wrap indistinguishable from a command boundary — David road-tested the
     * adjacent form and typed both commands as one (venv then treats the second
     * command's words as extra directories to create). Prose labels carry the
     * boundary and cannot be mistyped into a terminal.
     */
    /**
     * A venv permanently inherits the Python that creates it, so an old
     * interpreter must be caught BEFORE the venv exists — David's second
     * road-test failure: a freshly-installed 3.14 that his still-open
     * terminal didn't see yet built him a 3.9 venv.
     */
    const val STEP1_CHECK_LEAD: String = "Check your Python:"
    const val STEP1_CHECK_CMD: String = "python3 --version"
    const val STEP1_CHECK_NOTE: String = "It must say 3.10 or newer."

    const val STEP1_CMD1_LEAD: String = "Create the environment:"
    const val STEP1_CMD1: String = "python3 -m venv ~/anki-server"
    const val STEP1_CMD2_LEAD: String = "Install into it:"
    const val STEP1_CMD2: String = "~/anki-server/bin/pip install anki"

    /**
     * The old-Python failure is distinctive — teach the signature so nobody
     * has to decode a pip resolver error on their own.
     */
    const val STEP1_TROUBLESHOOT: String =
        "If pip installs anki 2.1.x or errors about ankirspy, your python3 is " +
            "older than 3.10. Install a newer Python and rerun the two commands " +
            "with it."

    /** Lead-in to the run command. */
    /** Tells a novice literally what to do with the block that follows. */
    const val STEP1_RUN_LEAD: String =
        "Then start the server. Type each command below and press \u201CEnter\u201D " +
            "at the end of it. An indented line is the same command " +
            "continuing, not a new one. The first four are settings; the last " +
            "one starts it:"

    /**
     * `export` per line, NOT the docs' backslash-continued one-liner: at 1080px
     * the continued form wraps into nonsense, and bare NAME=value lines typed
     * one per Enter don't survive to the next command — a novice following the
     * screen literally would start a server that ignores every setting
     * (David's road-test question exposed exactly this).
     */
    val STEP1_ENV_LINES: List<String> = listOf(
        "export SYNC_USER1=you:password",
        "export SYNC_BASE=~/anki-sync-data",
        "export SYNC_HOST=0.0.0.0",
        "export SYNC_PORT=8080",
    )

    /** The venv's python, so it runs regardless of what bare `python` points at. */
    const val STEP1_RUN_COMMAND: String = "~/anki-server/bin/python -m anki.syncserver"

    /** What the env vars mean, in one line each — enough to not need the docs. */
    const val STEP1_NOTE: String =
        "In the first line, replace you:password with a username and password " +
            "of your own choosing. They are what you'll enter on this phone. " +
            "When the server is running it prints a 'listening' line. Leave " +
            "that window open: closing it stops the server."

    /** Step 2 title. */
    const val STEP2_TITLE: String = "In Anki on your computer"

    /**
     * Step 2 body — the desktop-side seeding walk-through. localhost, not a LAN
     * address: Anki runs on the same computer as the server, so no address needs
     * finding here. (An earlier draft said "the address from step 1", which
     * pointed at nothing — David's road-test caught the dangling reference.
     * The LAN-address hunt belongs to step 3 only, where the phone needs it.)
     */
    /**
     * Matches what desktop Anki actually shows (David road-tested): the login
     * form labels the username field “email” (AnkiWeb heritage; a plain
     * username works), and a “save preferences and sync now?” prompt can
     * appear before any which-side question — hence “say yes” and the
     * conditional “if it asks”.
     */
    const val STEP2_BODY: String =
        "Open Preferences → Syncing, choose self-hosted sync server, and enter " +
            "http://localhost:8080/. Anki and the server are on the same " +
            "computer, so localhost is the address. Restart Anki, press Sync, " +
            "and log in with the username and password from step 1. Anki labels " +
            "the username field “email”; the username goes there. Say yes " +
            "when it offers to sync, and choose “Upload” if it asks which " +
            "side to keep."

    /** The media reassurance — a real question users have, answered where they'll ask it. */
    const val STEP2_MEDIA_NOTE: String =
        "Your media (images and audio) comes along with this upload."

    /** Step 3 title. */
    const val STEP3_TITLE: String = "Here on this phone"

    /**
     * Step 3 body. The example address is the same one docs/sync-server.md uses,
     * so the doc and the phone agree when a user reads both.
     */
    /**
     * One sentence. "Sync endpoint" (not "address") — the same term the
     * Welcome screen's field and the setup command's printed card use, so all
     * three surfaces name the value identically (David's consistency pass).
     */
    const val STEP3_BODY: String =
        "enter the sync endpoint, username, and password the setup command " +
            "printed. Then download your collection."

    /** Lead-in to the printed guide location. */
    const val GUIDE_LEAD: String = "The full guide, with more options, lives at:"

    /**
     * Printed as plain text, NOT a link: this phone has no browser, so the only
     * thing a user can do with it is read it and type it at a computer later.
     */
    const val GUIDE_URL: String = "github.com/ChopinDavid/recall-lightos → docs/sync-server.md"

    // ---- Part 3: FOOTNOTE ----

    /**
     * The AnkiWeb footnote. Last, and rendered lightened/Fine — it is context for
     * "why can't I just use my AnkiWeb login?", not an instruction. Stated as a
     * fact about where the request stands, without promising a date.
     */
    const val FOOTNOTE: String =
        "Signing in with your AnkiWeb username and password, syncing straight to " +
            "AnkiWeb, needs the Anki team's approval for third-party apps. We've " +
            "requested it and it's in progress. Until then, a self-hosted server is " +
            "the way Recall syncs."

    /** One numbered step's heading. */
    data class StepHeading(val number: Int, val title: String)

    /** The three step headings, in order. */
    fun stepHeadings(): List<StepHeading> = listOf(
        StepHeading(1, STEP1_TITLE),
        StepHeading(2, STEP2_TITLE),
        StepHeading(3, STEP3_TITLE),
    )

    /**
     * The narrative order the screen must render in. Exposed as data (rather than
     * living implicitly in the Composable's call order) so the ordering David
     * specified is asserted by a test rather than by a reviewer's eye.
     */
    fun sectionOrder(): List<String> = listOf("why", "how", "footnote")
}
