package com.openwhen.app.presentation

import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.presentation.state.PartnerActivityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerActivityTrackerTest {
    private val sent = Envelope(envelopeId = "letter", senderId = "me", receiverId = "partner", titlePrompt = "Open when you miss me")

    @Test fun `initial history and duplicate snapshots do not alert`() {
        val tracker = PartnerActivityTracker()
        val historical = sent.copy(isOpened = true, openCount = 3, lastOpenedAt = 100L, partnerReaction = "❤️")
        assertTrue(tracker.update("space", "me", listOf(historical), "Noor").isEmpty())
        assertTrue(tracker.update("space", "me", listOf(historical), "Noor").isEmpty())
    }

    @Test fun `each new open alerts once and reaction alerts independently`() {
        val tracker = PartnerActivityTracker()
        tracker.update("space", "me", listOf(sent), "Noor")
        val opened = sent.copy(isOpened = true, openCount = 1, lastOpenedAt = 100L)
        assertEquals(1, tracker.update("space", "me", listOf(opened), "Noor").size)
        assertTrue(tracker.update("space", "me", listOf(opened), "Noor").isEmpty())
        assertEquals(1, tracker.update("space", "me", listOf(opened.copy(partnerReaction = "❤️")), "Noor").size)
    }

    @Test fun `inbox opens and switching couples never replay alerts`() {
        val tracker = PartnerActivityTracker()
        val inbox = sent.copy(senderId = "partner", receiverId = "me")
        tracker.update("space", "me", listOf(inbox), "Noor")
        assertTrue(tracker.update("space", "me", listOf(inbox.copy(openCount = 1, lastOpenedAt = 100L)), "Noor").isEmpty())
        assertTrue(tracker.update("another-space", "me", listOf(sent.copy(openCount = 20, lastOpenedAt = 500L)), "Noor").isEmpty())
    }

    @Test fun `rapid repeated opens with equal timestamps still alert`() {
        val tracker = PartnerActivityTracker()
        val first = sent.copy(openCount = 1, lastOpenedAt = 100L)
        tracker.update("space", "me", listOf(first), "Noor")
        assertEquals(1, tracker.update("space", "me", listOf(first.copy(openCount = 2)), "Noor").size)
    }
}
