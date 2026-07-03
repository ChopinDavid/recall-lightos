package com.dvdutch.recall.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [HomeMode.attentionRoute] — the pure routing decision extracted from
 * [RecallHomeViewModel] so it is JVM-testable without the Android LightViewModel runtime.
 *
 * The regression this guards: a FULL_* divergence used to live only on an in-memory
 * StateFlow that died with each fresh controller a ViewModel built, so Home always read
 * false and AttentionScreen was unreachable. The durable pref now feeds `needsAttention`
 * here, and a set flag must route to attention.
 */
class RecallHomeRouteTest {

    @Test
    fun `configured and needs-attention routes to attention`() {
        assertTrue(HomeMode.attentionRoute(configured = true, needsAttention = true))
    }

    @Test
    fun `configured but no divergence does not route to attention`() {
        assertFalse(HomeMode.attentionRoute(configured = true, needsAttention = false))
    }

    @Test
    fun `an unconfigured controller never routes to attention`() {
        assertFalse(HomeMode.attentionRoute(configured = false, needsAttention = true))
    }
}
