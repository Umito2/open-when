package com.openwhen.app.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.presentation.components.WaxSealEnvelopeCard
import com.openwhen.app.presentation.theme.OpenWhenTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** These tests exercise pointer cancellation and the seal handoff to the larger reveal. */
class WaxSealEnvelopeCardTest {
    @get:Rule val compose = createComposeRule()

    private val envelope = Envelope(
        envelopeId = "test-letter",
        senderId = "partner",
        receiverId = "me",
        titlePrompt = "Open when you miss me",
        letterText = "I am always a little closer than you think.",
    )

    private fun show(calls: AtomicInteger) {
        compose.setContent {
            OpenWhenTheme {
                Box(Modifier.width(180.dp)) {
                    WaxSealEnvelopeCard(envelope, isSender = false, onOpen = { calls.incrementAndGet() })
                }
            }
        }
        compose.mainClock.autoAdvance = false
    }

    private fun awaitOpening(calls: AtomicInteger) {
        // The deliberate hold uses a real delay; the wax handoff also needs animation frames.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.mainClock.advanceTimeBy(32)
            calls.get() == 1
        }
    }

    @Test fun releasingBeforeOneAndHalfSecondsKeepsTheLetterSealed() {
        val calls = AtomicInteger()
        show(calls)
        val seal = compose.onNodeWithTag("wax-seal-test-letter", useUnmergedTree = true)
        seal.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        seal.performTouchInput { advanceEventTime(600); up() }
        compose.mainClock.advanceTimeBy(2_500)
        compose.runOnIdle { assertEquals(0, calls.get()) }
    }

    @Test fun fullHoldAndSealHandoffOpenTheLetterExactlyOnce() {
        val calls = AtomicInteger()
        show(calls)
        val seal = compose.onNodeWithTag("wax-seal-test-letter", useUnmergedTree = true)
        seal.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(0, calls.get()) }
        awaitOpening(calls)
        compose.runOnIdle { assertEquals(1, calls.get()) }
        seal.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(2_500)
        compose.runOnIdle { assertEquals(1, calls.get()) }
    }

    @Test fun accessibilityActionCanOpenTheLetterWithoutAComplexGesture() {
        val calls = AtomicInteger()
        show(calls)
        val actions = compose.onNodeWithContentDescription(envelope.titlePrompt)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(1, actions.size)
            assertTrue(actions.single().action())
            assertEquals(0, calls.get())
        }
        awaitOpening(calls)
        compose.runOnIdle { assertEquals(1, calls.get()) }
    }

    @Test fun repeatedAccessibilityActionCannotStartASecondOpeningWhileTheFirstIsPending() {
        val calls = AtomicInteger()
        show(calls)
        val action = compose.onNodeWithContentDescription(envelope.titlePrompt)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        compose.runOnIdle {
            assertTrue(action.action())
            assertFalse(action.action())
            assertEquals(0, calls.get())
        }
        awaitOpening(calls)
        compose.runOnIdle { assertEquals(1, calls.get()) }
    }
}
