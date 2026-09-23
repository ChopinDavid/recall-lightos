package com.dvdutch.recall.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SyncSetupMessages] — every word of the in-app sync-setup
 * onboarding. This copy carries unusual weight: the Light Phone III has NO
 * browser, so [SyncSetupScreen] is the ONLY setup guidance a device user can
 * reach. If a command string here is wrong, the user has no way to look up the
 * right one from the phone.
 *
 * Hence these are content tests, not smoke tests: the shell commands are
 * asserted verbatim against `docs/sync-server.md`, and the narrative order
 * (why → how → footnote) is asserted structurally, because leading with the
 * AnkiWeb limitation instead of the thing to do was the failure mode David
 * explicitly ruled out.
 */
class SyncSetupMessagesTest {

    // ---- Part 1: WHY SELF-HOSTED (must come first) ----

    @Test
    fun `the why section leads with what recall does, in plain words`() {
        val why = SyncSetupMessages.WHY_BODY
        assertTrue(why.contains("server you run yourself"), "names the self-hosted server: $why")
        assertTrue(why.contains("your own computer"), "names the user's own computer: $why")
        assertTrue(why.contains("Anki"), "names Anki as where the cards live: $why")
        assertTrue(why.contains("this phone studies them"), "names the phone's role: $why")
    }

    @Test
    fun `the why section does not lead with the ankiweb limitation`() {
        // The footnote is where AnkiWeb belongs; the opening must be about what to do.
        assertTrue(
            !SyncSetupMessages.WHY_BODY.contains("AnkiWeb"),
            "AnkiWeb must not appear in the opening section: ${SyncSetupMessages.WHY_BODY}",
        )
    }

    @Test
    fun `step one leads with the setup script via a short typeable url`() {
        assertEquals("github.com/ChopinDavid/recall-server", SyncSetupMessages.STEP1_EASY_URL)
        assertTrue(SyncSetupMessages.STEP1_EASY_AFTER.contains("prints the address"))
    }

    @Test
    fun `the manual walk survives as an appendix`() {
        assertTrue(SyncSetupMessages.MANUAL_HEADING.contains("by hand"))
    }

    // ---- Part 2: HOW — the steps, verbatim-accurate against docs/sync-server.md ----

    @Test
    fun `step one requires python 3_10 — the floor the anki package itself declares`() {
        assertTrue(SyncSetupMessages.STEP1_BODY.contains("Python 3.10 or newer"))
    }

    @Test
    fun `step one verifies the python version before the venv exists`() {
        assertEquals("python3 --version", SyncSetupMessages.STEP1_CHECK_CMD)
        assertTrue(SyncSetupMessages.STEP1_CHECK_NOTE.contains("3.10 or newer"))
    }

    @Test
    fun `step one installs into a venv as two separately-labeled commands`() {
        assertEquals("Create the environment:", SyncSetupMessages.STEP1_CMD1_LEAD)
        assertEquals("python3 -m venv ~/anki-server", SyncSetupMessages.STEP1_CMD1)
        assertEquals("Install into it:", SyncSetupMessages.STEP1_CMD2_LEAD)
        assertEquals("~/anki-server/bin/pip install anki", SyncSetupMessages.STEP1_CMD2)
    }

    @Test
    fun `step one teaches the old-python failure signature`() {
        assertTrue(SyncSetupMessages.STEP1_TROUBLESHOOT.contains("ankirspy"))
        assertTrue(SyncSetupMessages.STEP1_TROUBLESHOOT.contains("3.10"))
    }

    @Test
    fun `step one gives the syncserver run command verbatim`() {
        assertEquals("~/anki-server/bin/python -m anki.syncserver", SyncSetupMessages.STEP1_RUN_COMMAND)
    }

    @Test
    fun `step one gives the four environment variables verbatim`() {
        assertEquals(
            listOf(
                "export SYNC_USER1=you:password",
                "export SYNC_BASE=~/anki-sync-data",
                "export SYNC_HOST=0.0.0.0",
                "export SYNC_PORT=8080",
            ),
            SyncSetupMessages.STEP1_ENV_LINES,
        )
    }

    @Test
    fun `step two matches desktop anki's real dialogs`() {
        val body = SyncSetupMessages.STEP2_BODY
        assertTrue(body.contains("“email”"), "warns about the email-labeled field: $body")
        assertTrue(body.contains("if it asks"), "which-side prompt is conditional: $body")
    }

    @Test
    fun `step two walks the anki desktop preferences path`() {
        val body = SyncSetupMessages.STEP2_BODY
        assertTrue(body.contains("Preferences"), "names Preferences: $body")
        assertTrue(body.contains("Syncing"), "names Syncing: $body")
        assertTrue(body.contains("self-hosted sync server"), "names the setting: $body")
        assertTrue(body.contains("Restart"), "says to restart: $body")
        assertTrue(body.contains("Sync"), "says to press Sync: $body")
        assertTrue(body.contains("Upload"), "says to choose Upload: $body")
    }

    @Test
    fun `step two notes that media comes along from desktop`() {
        assertTrue(
            SyncSetupMessages.STEP2_MEDIA_NOTE.contains("media"),
            "media note must mention media: ${SyncSetupMessages.STEP2_MEDIA_NOTE}",
        )
    }

    @Test
    fun `step three names the sync endpoint, matching the Welcome field and the card`() {
        val body = SyncSetupMessages.STEP3_BODY
        assertTrue(body.contains("sync endpoint"), "uses the Welcome screen's term: $body")
        assertTrue(body.contains("username"), "names username: $body")
        assertTrue(body.contains("password"), "names password: $body")
    }

    @Test
    fun `the full guide is printed as a readable path, not a tappable link`() {
        // The phone has no browser: this is text to read at a computer.
        assertEquals(
            "github.com/ChopinDavid/recall-lightos → docs/sync-server.md",
            SyncSetupMessages.GUIDE_URL,
        )
    }

    @Test
    fun `the closing line frames the guide as something to read at a computer`() {
        assertTrue(
            SyncSetupMessages.GUIDE_LEAD.contains("full guide"),
            "closing line names the full guide: ${SyncSetupMessages.GUIDE_LEAD}",
        )
    }

    // ---- Part 3: FOOTNOTE — AnkiWeb approval, last and lightened ----

    @Test
    fun `the footnote explains the ankiweb approval is in progress`() {
        val footnote = SyncSetupMessages.FOOTNOTE
        assertTrue(footnote.contains("AnkiWeb"), "names AnkiWeb: $footnote")
        assertTrue(footnote.contains("approval"), "names approval: $footnote")
        assertTrue(footnote.contains("in progress"), "says the request is in progress: $footnote")
        assertTrue(footnote.contains("third-party"), "names third-party apps: $footnote")
    }

    @Test
    fun `the footnote points back at self-hosted as the sync path for now`() {
        assertTrue(
            SyncSetupMessages.FOOTNOTE.contains("self-hosted"),
            "footnote must name self-hosted as today's path: ${SyncSetupMessages.FOOTNOTE}",
        )
    }

    // ---- Narrative order: why → how → footnote ----

    @Test
    fun `the three sections appear in the spec's narrative order`() {
        val order = SyncSetupMessages.sectionOrder()
        assertEquals(listOf("why", "how", "footnote"), order)
    }

    @Test
    fun `all three sections are present and non-blank`() {
        val all = listOf(
            SyncSetupMessages.WHY_BODY,
            SyncSetupMessages.STEP1_BODY,
            SyncSetupMessages.STEP2_BODY,
            SyncSetupMessages.STEP3_BODY,
            SyncSetupMessages.FOOTNOTE,
        )
        all.forEach { assertTrue(it.isNotBlank(), "section copy must not be blank") }
    }

    // ---- The entry-point rows on FirstRun and Settings ----

    @Test
    fun `the first-run row asks the setup question in the user's words`() {
        assertEquals("\u201CHow do I set this up?\u201D", SyncSetupMessages.FIRST_RUN_ROW_LABEL)
    }

    @Test
    fun `the settings row names the help by what it is`() {
        assertEquals("sync setup help", SyncSetupMessages.SETTINGS_ROW_LABEL)
    }

    @Test
    fun `the screen title names sync setup`() {
        assertEquals("Sync setup", SyncSetupMessages.TITLE)
    }

    // ---- Step headings are numbered 1..3 in order ----

    @Test
    fun `the step headings are numbered one through three`() {
        assertEquals(
            listOf(1, 2, 3),
            SyncSetupMessages.stepHeadings().map { it.number },
        )
    }

    @Test
    fun `each step heading has a short title`() {
        SyncSetupMessages.stepHeadings().forEach {
            assertTrue(it.title.isNotBlank(), "step ${it.number} needs a title")
        }
    }
}
