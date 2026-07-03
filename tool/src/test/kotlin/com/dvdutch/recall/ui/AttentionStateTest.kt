package com.dvdutch.recall.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure tests for the needs-attention confirmation gate. Resolving a FULL_*
 * divergence is destructive (one side's history is overwritten), so — mirroring
 * the bridge CLI's safety — the operator must type the exact direction word
 * ("download" or "upload") before the [fullSync] fires. This is the pure matcher
 * for that confirmation.
 */
class AttentionStateTest {

    @Test
    fun `download confirmation only matches the exact word`() {
        assertTrue(AttentionConfirm.matches(AttentionDirection.Download, "download"))
        assertFalse(AttentionConfirm.matches(AttentionDirection.Download, "upload"))
        assertFalse(AttentionConfirm.matches(AttentionDirection.Download, "down"))
        assertFalse(AttentionConfirm.matches(AttentionDirection.Download, ""))
    }

    @Test
    fun `upload confirmation only matches the exact word`() {
        assertTrue(AttentionConfirm.matches(AttentionDirection.Upload, "upload"))
        assertFalse(AttentionConfirm.matches(AttentionDirection.Upload, "download"))
    }

    @Test
    fun `matching trims surrounding whitespace and is case-insensitive`() {
        assertTrue(AttentionConfirm.matches(AttentionDirection.Download, "  Download "))
        assertTrue(AttentionConfirm.matches(AttentionDirection.Upload, "UPLOAD"))
    }

    @Test
    fun `direction upload flag maps to fullSync argument`() {
        assertTrue(AttentionDirection.Upload.upload)
        assertFalse(AttentionDirection.Download.upload)
    }

    @Test
    fun `direction words are the exact confirmation strings`() {
        assertEquals("download", AttentionDirection.Download.word)
        assertEquals("upload", AttentionDirection.Upload.word)
    }

    @Test
    fun `parse resolves a typed word to its direction`() {
        assertEquals(AttentionDirection.Download, AttentionConfirm.parse("download"))
        assertEquals(AttentionDirection.Upload, AttentionConfirm.parse(" UPLOAD "))
        assertNull(AttentionConfirm.parse("sideways"))
    }
}
