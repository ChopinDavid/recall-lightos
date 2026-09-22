package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * The in-app sync setup onboarding, reachable from BOTH [FirstRunScreen] (via
 * “How do I set this up?”) and [SettingsScreen] (via "sync setup help").
 *
 * This screen exists because the Light Phone III has no browser. A normal app
 * would link to docs; here there is nowhere to link TO from the device, so the
 * whole setup story has to fit on a screen the user can scroll. That constraint
 * shapes everything below: the steps are self-contained (no "see the manual"),
 * the commands are spelled out in full, and the repo path at the end is printed
 * as text to be read at a computer, not as a tappable link.
 *
 * It is a [SimpleLightScreen] — entirely static. No ViewModel, no engine, no
 * network, no new permissions: all copy is compile-time constants in
 * [SyncSetupMessages], which is where the content tests live.
 *
 * The three parts render in the order [SyncSetupMessages.sectionOrder] declares:
 * why self-hosted, then the steps, then the AnkiWeb footnote last.
 */
class SyncSetupScreen(sealedActivity: SealedLightActivity) :
    SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text(SyncSetupMessages.TITLE),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    // 1. WHY SELF-HOSTED — first, and never leading with a limitation.
                    SectionHeading(SyncSetupMessages.WHY_HEADING)
                    Body(SyncSetupMessages.WHY_BODY)

                    // 2. HOW — the three steps.
                    SectionHeading(SyncSetupMessages.HOW_HEADING, topGap = 1.5f)

                    val steps = SyncSetupMessages.stepHeadings()

                    StepHeading(steps[0])
                    Body(SyncSetupMessages.STEP1_BODY)
                    SyncSetupMessages.STEP1_INSTALL_LINES.forEach { CommandLine(it) }
                    Note(SyncSetupMessages.STEP1_TROUBLESHOOT)
                    Body(SyncSetupMessages.STEP1_RUN_LEAD)
                    // One env var per line: the backslash-continued shell form from the
                    // docs wraps into an unreadable mess at 1080px, and a user copying
                    // these by eye needs each name=value whole on its own line.
                    SyncSetupMessages.STEP1_ENV_LINES.forEach { CommandLine(it) }
                    CommandLine(SyncSetupMessages.STEP1_RUN_COMMAND)
                    Note(SyncSetupMessages.STEP1_NOTE)

                    StepHeading(steps[1])
                    Body(SyncSetupMessages.STEP2_BODY)
                    Note(SyncSetupMessages.STEP2_MEDIA_NOTE)

                    StepHeading(steps[2])
                    Body(SyncSetupMessages.STEP3_BODY)

                    // The guide location, printed as text — there is no browser here.
                    Body(SyncSetupMessages.GUIDE_LEAD, topGap = 1.5f)
                    CommandLine(SyncSetupMessages.GUIDE_URL)

                    // 3. FOOTNOTE — lightened Fine, last.
                    LightText(
                        text = SyncSetupMessages.FOOTNOTE,
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.padding(
                            top = 2f.gridUnitsAsDp(),
                            bottom = 2f.gridUnitsAsDp(),
                        ),
                    )
                }
            }
        }
    }
}

/** A part heading ("How Recall syncs", "Setting it up"). */
@Composable
private fun SectionHeading(text: String, topGap: Float = 0f) {
    LightText(
        text = text,
        variant = LightTextVariant.Heading,
        modifier = Modifier.padding(
            top = topGap.gridUnitsAsDp(),
            bottom = 0.5f.gridUnitsAsDp(),
        ),
    )
}

/** A numbered step heading, rendered "1. On your computer". */
@Composable
private fun StepHeading(heading: SyncSetupMessages.StepHeading) {
    LightText(
        text = "${heading.number}. ${heading.title}",
        variant = LightTextVariant.Subheading,
        modifier = Modifier.padding(
            top = 1.25f.gridUnitsAsDp(),
            bottom = 0.5f.gridUnitsAsDp(),
        ),
    )
}

/** Ordinary prose. */
@Composable
private fun Body(text: String, topGap: Float = 0f) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(
            top = topGap.gridUnitsAsDp(),
            bottom = 0.5f.gridUnitsAsDp(),
        ),
    )
}

/**
 * A command, env var, or path the user must transcribe exactly. Monospace via
 * LightText's own `monospace` flag — this is a static screen, so the render-node
 * TextRun mono path isn't involved. Monospace matters here for a practical
 * reason, not a decorative one: it disambiguates 0/O and 1/l/I in an address or
 * a password the user is copying by eye.
 */
@Composable
private fun CommandLine(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        monospace = true,
        modifier = Modifier.padding(
            start = 1f.gridUnitsAsDp(),
            top = 0.25f.gridUnitsAsDp(),
            bottom = 0.25f.gridUnitsAsDp(),
        ),
    )
}

/** A lightened aside under a step — context, not an instruction. */
@Composable
private fun Note(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Fine,
        lighten = true,
        modifier = Modifier.padding(
            top = 0.25f.gridUnitsAsDp(),
            bottom = 0.5f.gridUnitsAsDp(),
        ),
    )
}
