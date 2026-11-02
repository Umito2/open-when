package com.openwhen.app.presentation

import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import com.openwhen.app.domain.model.CoupleSpace
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.domain.model.EnvelopeDraft
import com.openwhen.app.domain.model.MediaKind
import com.openwhen.app.domain.model.UnconfirmedSendException
import com.openwhen.app.domain.model.User
import com.openwhen.app.domain.repository.EnvelopeRepository
import com.openwhen.app.media.AudioRecorderHelper
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnvelopeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun `uncertain seal retries the same payload without uploading twice`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        runCurrent()
        vm.updateDraft { it.copy(letterText = "You are my home.", photoUri = "content://photo", photoCaption = "Our first trip") }
        repository.failNextSend = true
        vm.sendEnvelope()
        runCurrent()

        assertTrue(vm.create.value.isAwaitingConfirmation)
        vm.updateDraft { it.copy(letterText = "An accidental edit during retry") }
        vm.sendEnvelope()
        runCurrent()

        assertEquals(1, repository.uploadCount)
        assertEquals(2, repository.sentAttempts.size)
        assertEquals(repository.sentAttempts[0], repository.sentAttempts[1])
        assertEquals("You are my home.", repository.sentAttempts[1].letterText)
        assertEquals("https://example.test/upload/1", repository.sentAttempts[1].photoUrl)
        assertEquals("Our first trip", repository.sentAttempts[1].photoCaption)
        assertTrue(vm.create.value.wasSent)
        assertFalse(vm.create.value.isAwaitingConfirmation)
    }

    @Test fun `switching accounts during photo preparation cancels sending and clears the attachment`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { uploadGate = CompletableDeferred() }
        val vm = viewModel(repository)
        runCurrent()
        vm.updateDraft { it.copy(letterText = "A memory just for you", photoUri = "content://private-photo", photoCaption = "Our trip") }
        vm.sendEnvelope()
        runCurrent()
        assertTrue(vm.create.value.isSaving)
        assertEquals(1, repository.uploadCount)

        repository.currentUser.value = User(uid = "other-user", partnerId = "other-partner", coupleSpaceId = "another-space")
        runCurrent()
        repository.uploadGate!!.complete(Unit)
        runCurrent()

        assertTrue(repository.sentAttempts.isEmpty())
        assertEquals(null, vm.create.value.photoUri)
        assertEquals("", vm.create.value.photoCaption)
        assertFalse(vm.create.value.isSaving)
    }

    @Test fun `switching accounts clears private content before new snapshots arrive`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        runCurrent()
        assertEquals(1, vm.dashboard.value.envelopes.size)
        vm.updateDraft { it.copy(letterText = "Private draft") }

        repository.currentUser.value = User(uid = "other-user", partnerId = "other-partner", coupleSpaceId = "unloaded-space")
        runCurrent()

        assertEquals("other-user", vm.dashboard.value.user?.uid)
        assertTrue(vm.dashboard.value.envelopes.isEmpty())
        assertEquals(null, vm.dashboard.value.partner)
        assertEquals(null, vm.dashboard.value.space)
        assertEquals("", vm.create.value.letterText)
        assertTrue(vm.dashboard.value.isLoading)
    }

    @Test fun `sender preview receives live receipts and reactions while preserving private content`() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = viewModel(repository)
        runCurrent()
        vm.openEnvelope("private")
        runCurrent()
        val original = requireNotNull(vm.opened.value.envelope)
        val opening = requireNotNull(vm.opened.value.opening)

        repository.envelopeSnapshots.value = listOf(original.copy(
            isOpened = true,
            lastOpenedAt = 123_456L,
            openCount = 2,
            partnerReaction = "Sending a hug 🫂",
        ))
        runCurrent()

        val refreshed = requireNotNull(vm.opened.value.envelope)
        assertTrue(refreshed.isOpened)
        assertEquals(123_456L, refreshed.lastOpenedAt)
        assertEquals(2, refreshed.openCount)
        assertEquals("Sending a hug 🫂", refreshed.partnerReaction)
        assertEquals(original.letterText, refreshed.letterText)
        assertEquals(original.photoUrl, refreshed.photoUrl)
        assertEquals(original.photoCaption, refreshed.photoCaption)
        assertEquals(original.voiceNoteUrl, refreshed.voiceNoteUrl)
        assertEquals(original.voiceDurationSec, refreshed.voiceDurationSec)
        assertEquals(original.songUrl, refreshed.songUrl)
        assertEquals("", vm.dashboard.value.envelopes.single().letterText)
        assertEquals(opening, vm.opened.value.opening)
        assertEquals(1, repository.openCalls)
    }

    @Test fun `opening keeps one visual request through loading and result then uses a new request after dismissal`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { openGate = CompletableDeferred() }
        val vm = viewModel(repository)
        runCurrent()
        vm.openEnvelope("private")
        runCurrent()
        val opening = requireNotNull(vm.opened.value.opening)
        assertTrue(vm.opened.value.isLoading)
        assertEquals("private", opening.envelopeId)
        assertNull(vm.opened.value.envelope)
        repository.openGate!!.complete(Unit)
        runCurrent()
        assertEquals(opening, vm.opened.value.opening)
        assertNotNull(vm.opened.value.envelope)
        vm.dismissLetter()
        assertNull(vm.opened.value.opening)
        repository.openFails = true
        vm.openEnvelope("private")
        runCurrent()
        assertNotEquals(opening.requestId, vm.opened.value.opening?.requestId)
        assertNotNull(vm.opened.value.error)
        assertNull(vm.opened.value.envelope)
        assertFalse(vm.opened.value.isLoading)
    }

    @Test fun `account changes cancel an unfolding letter and ignore its late result`() = runTest(dispatcher) {
        val repository = FakeRepository().apply { openGate = CompletableDeferred() }
        val vm = viewModel(repository)
        runCurrent()
        vm.openEnvelope("private")
        runCurrent()
        assertNotNull(vm.opened.value.opening)
        repository.currentUser.value = User(uid = "other-user", coupleSpaceId = "other-space")
        runCurrent()
        repository.openGate!!.complete(Unit)
        runCurrent()
        assertNull(vm.opened.value.opening)
        assertNull(vm.opened.value.envelope)
        assertFalse(vm.opened.value.isLoading)
    }

    private fun viewModel(repository: EnvelopeRepository): EnvelopeViewModel {
        // This unit suite never starts the recorder; Android's mock context is sufficient.
        return EnvelopeViewModel(repository, AudioRecorderHelper(ContextWrapper(null))).also { store.put("vm", it) }
    }

    private class FakeRepository : EnvelopeRepository {
        override val isDemo = true
        val currentUser = MutableStateFlow<User?>(User(uid = "me", partnerId = "partner", coupleSpaceId = "space"))
        override val session: Flow<User?> = currentUser
        val sentAttempts = mutableListOf<EnvelopeDraft>()
        val envelopeSnapshots = MutableStateFlow(listOf(Envelope(
            envelopeId = "private", senderId = "me", receiverId = "partner",
            letterText = "A private letter", photoUrl = "https://example.test/photo",
            photoCaption = "Our little memory", voiceNoteUrl = "https://example.test/voice.m4a",
            voiceDurationSec = 9, songUrl = "https://example.test/song",
        )))
        var failNextSend = false
        var uploadCount = 0
        var uploadGate: CompletableDeferred<Unit>? = null
        var openGate: CompletableDeferred<Unit>? = null
        var openFails = false
        var openCalls = 0
        override fun observeSpace(spaceId: String): Flow<CoupleSpace?> = if (spaceId == "space") {
            flowOf(CoupleSpace(spaceId = "space", user1Id = "me", user2Id = "partner"))
        } else MutableSharedFlow()
        override fun observePartner(partnerId: String): Flow<User?> = flowOf(User(uid = partnerId, name = "Noor"))
        override fun observeEnvelopes(spaceId: String): Flow<List<Envelope>> = if (spaceId == "space") {
            envelopeSnapshots.map { envelopes ->
                envelopes.map { it.copy(letterText = "", photoUrl = null, photoCaption = null, voiceNoteUrl = null, songUrl = null) }
            }
        } else MutableSharedFlow()
        override suspend fun signInAnonymously(name: String) = Unit
        override suspend fun signInEmail(name: String, email: String, password: String, createAccount: Boolean) = Unit
        override suspend fun signInGoogle(idToken: String) = Unit
        override suspend fun createSpace(anniversaryDate: Long) = CoupleSpace()
        override suspend fun joinSpace(inviteCode: String) = Unit
        override suspend fun sendEnvelope(draft: EnvelopeDraft): String {
            sentAttempts += draft
            if (failNextSend) {
                failNextSend = false
                throw UnconfirmedSendException("Connection lost", IOException())
            }
            return "sealed"
        }
        override suspend fun openEnvelope(envelopeId: String): Envelope {
            openCalls++
            openGate?.await()
            if (openFails) throw IOException("Please try again")
            return envelopeSnapshots.value.first { it.envelopeId == envelopeId }
        }
        override suspend fun react(envelopeId: String, reaction: String) = Unit
        override suspend fun uploadMedia(localUri: String, kind: MediaKind): String {
            uploadCount++
            uploadGate?.await()
            return "https://example.test/upload/$uploadCount"
        }
        override suspend fun updateMood(mood: String) = Unit
        override suspend fun signOut() { currentUser.value = null }
    }
}
