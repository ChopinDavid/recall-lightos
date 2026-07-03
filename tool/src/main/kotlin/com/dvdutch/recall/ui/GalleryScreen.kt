package com.dvdutch.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.dvdutch.recall.api.BridgeJson
import com.dvdutch.recall.api.CardPayload
import com.dvdutch.recall.api.QueueResponse
import com.dvdutch.recall.prefs.RecallStorage
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
 * A developer "eyeball" harness that renders the queue fixture's cards through
 * [RenderNodeColumn], so the render-node views can be inspected on-device without
 * a live bridge. Reached from Settings (Task 5) via a dev row; kept out of the
 * study flow. Later this same screen can also point at live bridge content.
 *
 * The fixture is embedded as a raw string constant ([GALLERY_FIXTURE_JSON])
 * rather than loaded from assets: the fixture currently lives only under test
 * resources, and asset bundling is not guaranteed in the dev/sandbox build. The
 * embedded copy is dev-only and mirrors `tool/src/test/resources/fixtures/queue.json`.
 */
class GalleryScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val cards = remember { parseGalleryCards() }

        // A real session loader reading from the on-device collection.media dir, so the
        // gallery renders live media images (not just placeholders) once a collection
        // has been downloaded. Shares one ~16-entry LRU across all fixture cards.
        val storage = remember { RecallStorage(lightContext.filesDir) }
        val mediaLoader = remember(storage) { MediaLoader(storage) }

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
                    center = LightTopBarCenter.Text("Gallery"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    cards.forEachIndexed { index, card ->
                        GalleryCard(index = index, card = card, mediaLoader = mediaLoader)
                    }
                }
            }
        }
    }
}

/** Renders one fixture card: a labelled front block above a labelled back block. */
@Composable
private fun GalleryCard(index: Int, card: CardPayload, mediaLoader: MediaLoader?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = "Card ${index + 1} · front",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(bottom = 0.5f.gridUnitsAsDp()),
        )
        RenderNodeColumn(nodes = card.front, mediaLoader = mediaLoader)

        LightText(
            text = "back",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(
                top = 1f.gridUnitsAsDp(),
                bottom = 0.5f.gridUnitsAsDp(),
            ),
        )
        RenderNodeColumn(nodes = card.back, mediaLoader = mediaLoader)
    }
}

/** Parses the embedded fixture into card payloads; empty on any parse failure. */
private fun parseGalleryCards(): List<CardPayload> = runCatching {
    BridgeJson.decodeFromString(QueueResponse.serializer(), GALLERY_FIXTURE_JSON).cards
}.getOrDefault(emptyList())

/**
 * Verbatim copy of `tool/src/test/resources/fixtures/queue.json`. Dev-only; keep
 * in sync with the fixture if the wire shape changes.
 */
private const val GALLERY_FIXTURE_JSON: String =
    """{"cards":[{"card_id":1781932102064,"note_id":1781932102064,"front":[{"t":"text","runs":[{"s":"Genitive (роди́тельный)"}]}],"back":[{"t":"text","runs":[{"s":"Genitive (роди́тельный) "}]},{"t":"rule"},{"t":"text","runs":[{"s":" Possession / \"of\" — "},{"s":"кни́га бра́та","i":true},{"s":" (brother's book) · Absence & negation — "},{"s":"нет вре́мени","i":true},{"s":" (there's no time) · After quantities — "},{"s":"мно́го воды́","i":true},{"s":" (a lot of water) · After prepositions у, из, до, без, от"}]}],"states":"CgYKBAoCCCoSCAoGEgQIAhA8GgkKBxIFCAIQygIiCQoHEgUIARDYBCoLCgkaBwgDHQAAIEA=","next_due_labels":{"again":"<⁨1⁩m","hard":"<⁨6⁩m","good":"<⁨10⁩m","easy":"⁨3⁩d"}},{"card_id":1781932102066,"note_id":1781932102065,"front":[{"t":"text","runs":[{"s":"Dative (да́тельный)"}]}],"back":[{"t":"text","runs":[{"s":"Dative (да́тельный) "}]},{"t":"rule"},{"t":"text","runs":[{"s":" Indirect object — the \"to/for whom\" — "},{"s":"Я дал кни́гу дру́гу","i":true},{"s":" (I gave the book to a friend) · Recipient of giving/telling · After к and по · Impersonal feelings — "},{"s":"мне хо́лодно","i":true},{"s":" (I'm cold)"}]}],"states":"CgYKBAoCCCsSCAoGEgQIAhA8GgkKBxIFCAIQygIiCQoHEgUIARDYBCoLCgkaBwgEHQAAIEA=","next_due_labels":{"again":"<⁨1⁩m","hard":"<⁨6⁩m","good":"<⁨10⁩m","easy":"⁨4⁩d"}},{"card_id":1781932102068,"note_id":1781932102066,"front":[{"t":"text","runs":[{"s":"Accusative (вини́тельный)"}]}],"back":[{"t":"text","runs":[{"s":"Accusative (вини́тельный) "}]},{"t":"rule"},{"t":"text","runs":[{"s":" Direct object — the \"whom/what\" receiving the action — "},{"s":"Я ви́жу соба́ку","i":true},{"s":" (I see the dog) · Destination of motion after в/на (= into/onto) — "},{"s":"иду́ в шко́лу","i":true},{"s":" · Duration of time"}]}],"states":"CgYKBAoCCCwSCAoGEgQIAhA8GgkKBxIFCAIQygIiCQoHEgUIARDYBCoLCgkaBwgDHQAAIEA=","next_due_labels":{"again":"<⁨1⁩m","hard":"<⁨6⁩m","good":"<⁨10⁩m","easy":"⁨3⁩d"}}],"counts":{"new":19,"learning":1,"review":0}}"""
