package com.openwhen.app.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.presentation.screens.LetterRevealScreen
import com.openwhen.app.presentation.state.EnvelopeOpeningDetails
import com.openwhen.app.presentation.state.OpenEnvelopeState
import com.openwhen.app.presentation.theme.OpenWhenTheme
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class)
class LetterRevealScreenTest {
    private var animationScale = 1f
    @get:Rule val compose = createComposeRule(effectContext = object : MotionDurationScale {
        override val scaleFactor: Float get() = animationScale
    })
    private val letter = Envelope(
        envelopeId = "letter", senderId = "partner", receiverId = "me",
        titlePrompt = "Open when you miss me", letterText = "My private words are only for you.",
    )
    private val opening = EnvelopeOpeningDetails("request", "letter", letter.titlePrompt, letter.envelopeColorHex, letter.sealIcon)

    @Test fun privateWordsStayOutOfSemanticsUntilTheEnvelopeUnfolds() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenWhenTheme {
                LetterRevealScreen(OpenEnvelopeState(envelope = letter, opening = opening), "me", "Your person", {}, {})
            }
        }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("letter-opening-scene").assertExists()
        compose.onNodeWithText(letter.letterText).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(letter.letterText).assertExists()
    }

    @Test fun slowContentWaitsSafelyAndReactionsDoNotReplayTheOpening() {
        val state = mutableStateOf(OpenEnvelopeState(isLoading = true, opening = opening))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenWhenTheme { LetterRevealScreen(state.value, "me", "Your person", {}, {}) }
        }
        compose.mainClock.advanceTimeBy(2_400)
        compose.onNodeWithTag("letter-opening-scene").assertExists()
        compose.onNodeWithText(letter.letterText).assertDoesNotExist()
        compose.runOnIdle { state.value = OpenEnvelopeState(envelope = letter, opening = opening) }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithText(letter.letterText).assertExists()
        compose.runOnIdle { state.value = state.value.copy(envelope = letter.copy(partnerReaction = "Sending a hug")) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(letter.letterText).assertExists()
        compose.onNodeWithTag("letter-opening-scene").assertDoesNotExist()
    }

    @Test fun failedOpeningShowsItsErrorWithoutWaitingForTheAnimation() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenWhenTheme {
                LetterRevealScreen(OpenEnvelopeState(error = "Please try again", opening = opening), "me", "Your person", {}, {})
            }
        }
        compose.onNodeWithText("Please try again").assertExists()
        compose.onNodeWithTag("letter-opening-scene").assertDoesNotExist()
        compose.onNodeWithText(letter.letterText).assertDoesNotExist()
    }

    @Test fun disabledMotionRevealsLoadedWordsWithoutAnArtificialDelay() {
        animationScale = 0f
        compose.mainClock.autoAdvance = false
        compose.setContent {
            OpenWhenTheme {
                LetterRevealScreen(OpenEnvelopeState(envelope = letter, opening = opening), "me", "Your person", {}, {})
            }
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText(letter.letterText).assertExists()
    }

    @Test fun restoredCompletionCannotSkipANewRequestsOpening() {
        val request = AtomicReference(opening)
        val restoration = StateRestorationTester(compose)
        compose.mainClock.autoAdvance = false
        restoration.setContent {
            OpenWhenTheme {
                LetterRevealScreen(OpenEnvelopeState(envelope = letter, opening = request.get()), "me", "Your person", {}, {})
            }
        }
        compose.mainClock.advanceTimeBy(2_800)
        compose.onNodeWithText(letter.letterText).assertExists()
        // The external owner supplies a fresh request when the saved composition is recreated.
        request.set(opening.copy(requestId = "new-request"))
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText(letter.letterText).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2_200)
        compose.onNodeWithText(letter.letterText).assertExists()
    }
}
