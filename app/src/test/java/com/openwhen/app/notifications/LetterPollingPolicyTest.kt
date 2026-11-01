package com.openwhen.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterPollingPolicyTest {
    private fun item(seconds: Long, nanos: Int, id: String, receiver: String = "me", opened: Boolean = false) =
        LetterPollingItem(LetterCursor(seconds, nanos, id), receiver, opened)

    @Test fun `baseline remembers latest existing letter without replaying history`() {
        val old = listOf(item(100, 0, "a"), item(200, 0, "b"))
        val baseline = LetterPollingPolicy.baseline(old)
        assertTrue(baseline.initialized)
        assertEquals(old.last().cursor, baseline.cursor)
        assertEquals(0, LetterPollingPolicy.advance(baseline.cursor, "me", old).newLetterCount)
    }

    @Test fun `empty baseline still allows first future letter`() {
        val baseline = LetterPollingPolicy.baseline(emptyList())
        assertTrue(baseline.initialized)
        val next = LetterPollingPolicy.advance(baseline.cursor, "me", listOf(item(100, 0, "a")))
        assertEquals(1, next.newLetterCount)
    }

    @Test fun `sub millisecond timestamps and equal timestamp IDs are never skipped`() {
        val first = item(100, 1, "a")
        val second = item(100, 2, "a")
        val third = item(100, 2, "b")
        val next = LetterPollingPolicy.advance(first.cursor, "me", listOf(second, third))
        assertEquals(2, next.newLetterCount)
        assertEquals(third.cursor, next.cursor)
    }

    @Test fun `opened and other recipient letters do not notify but advance checkpoint`() {
        val opened = item(100, 0, "a", opened = true)
        val stranger = item(101, 0, "b", receiver = "another-account")
        val page = LetterPollingPolicy.advance(null, "me", listOf(opened, stranger))
        assertEquals(0, page.newLetterCount)
        assertEquals(stranger.cursor, page.cursor)
    }

    @Test fun `overlapping retries and repeated documents only count once`() {
        val one = item(100, 0, "a")
        val two = item(100, 0, "b")
        val first = LetterPollingPolicy.advance(null, "me", listOf(one, one, two))
        assertEquals(2, first.newLetterCount)
        val retry = LetterPollingPolicy.advance(first.cursor, "me", listOf(one, two))
        assertEquals(0, retry.newLetterCount)
        assertEquals(first.cursor, retry.cursor)
    }

    @Test fun `account change signout optout and revoked permission all stop delivery`() {
        assertTrue(LetterPollingPolicy.sessionAllows("me", "me", true, true))
        assertFalse(LetterPollingPolicy.sessionAllows("me", "another-account", true, true))
        assertFalse(LetterPollingPolicy.sessionAllows("me", null, true, true))
        assertFalse(LetterPollingPolicy.sessionAllows("me", "me", false, true))
        assertFalse(LetterPollingPolicy.sessionAllows("me", "me", true, false))
    }

    @Test fun `document IDs use UTF8 ordering like Firestore`() {
        val bmp = LetterCursor(100, 0, "\uE000")
        val supplementary = LetterCursor(100, 0, "\uD800\uDC00")
        assertTrue(bmp < supplementary)
    }
}
