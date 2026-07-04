package com.dvdutch.recall.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dvdutch.recall.api.RenderNode
import com.dvdutch.recall.api.TextNode
import com.dvdutch.recall.api.TextRun
import com.dvdutch.recall.study.TypeAnswerReveal
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Maps a [TypeAnswerReveal] to the single [RenderNode] the back's set-off type-answer
 * block displays:
 *   - [TypeAnswerReveal.Diff]     → the backend-computed diff node, verbatim (its
 *     mono/strike/underline runs already carry the right/wrong/missed classification);
 *   - [TypeAnswerReveal.Expected] → a plain "answer: X" line (a mono run, so it reads as
 *     the answer without masquerading as a diff). Never a fabricated all-wrong diff.
 *
 * Pure so the mapping is unit-tested on the JVM.
 */
fun typeAnswerRevealNode(reveal: TypeAnswerReveal): RenderNode = when (reveal) {
    is TypeAnswerReveal.Diff -> reveal.node
    is TypeAnswerReveal.Expected ->
        TextNode(listOf(TextRun("answer: ${reveal.answer}", mono = true)))
}

/**
 * The "TYPE ANSWER" affordance shown on a type-answer card's front (above REVEAL), styled
 * like the REPLAY AUDIO row. A tap opens the full-screen SDK text editor. If the user has
 * already typed something this card, the row reflects it so the state is visible before reveal.
 */
@Composable
fun TypeAnswerRow(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.25f.gridUnitsAsDp()),
    ) {
        LightText(
            text = "⌨ TYPE ANSWER",
            variant = LightTextVariant.Fine,
            lighten = true,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The set-off type-answer result block shown near the top of the back. It renders the
 * [typeAnswerRevealNode] with a light "your answer" caption, clearly separated from the
 * card content below. The marker's original inline position is lost by design (stripped at
 * the engine), so the block sits at the top rather than where `{{type:Field}}` was.
 */
@Composable
fun TypeAnswerRevealBlock(reveal: TypeAnswerReveal, mediaLoader: MediaLoader?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 0.5f.gridUnitsAsDp()),
    ) {
        LightText(
            text = if (reveal is TypeAnswerReveal.Diff) "your answer" else "expected answer",
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        RenderNodeView(node = typeAnswerRevealNode(reveal), mediaLoader = mediaLoader)
    }
}
