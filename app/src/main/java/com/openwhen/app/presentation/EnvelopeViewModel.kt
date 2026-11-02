package com.openwhen.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwhen.app.domain.model.EnvelopeDraft
import com.openwhen.app.domain.model.MediaKind
import com.openwhen.app.domain.model.UnlockRule
import com.openwhen.app.domain.model.UnconfirmedSendException
import com.openwhen.app.domain.repository.EnvelopeRepository
import com.openwhen.app.media.AudioRecorderHelper
import com.openwhen.app.presentation.state.CreateEnvelopeState
import com.openwhen.app.presentation.state.DashboardUiState
import com.openwhen.app.presentation.state.OpenEnvelopeState
import com.openwhen.app.presentation.state.EnvelopeOpeningDetails
import com.openwhen.app.presentation.state.PairingUiState
import com.openwhen.app.presentation.state.PartnerActivityTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class EnvelopeViewModel @Inject constructor(
    private val repository: EnvelopeRepository,
    private val recorder: AudioRecorderHelper,
) : ViewModel() {
    private val mutableDashboard = MutableStateFlow(DashboardUiState(isDemo = repository.isDemo))
    val dashboard = mutableDashboard.asStateFlow()
    private val mutableCreate = MutableStateFlow(CreateEnvelopeState())
    val create = mutableCreate.asStateFlow()
    private val mutableOpened = MutableStateFlow(OpenEnvelopeState())
    val opened = mutableOpened.asStateFlow()
    private val mutablePairing = MutableStateFlow(PairingUiState())
    val pairing = mutablePairing.asStateFlow()
    private val mutableAlerts = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val alerts = mutableAlerts.asSharedFlow()
    val recorderState = recorder.state
    private val activityTracker = PartnerActivityTracker()
    private var openJob: Job? = null
    private var sendJob: Job? = null
    private var draftRequestId = UUID.randomUUID().toString()
    private var stagedPhoto: Pair<String, String>? = null
    private var stagedVoice: Pair<String, String>? = null
    private var pendingEnvelopeDraft: EnvelopeDraft? = null

    init {
        observeSession()
        viewModelScope.launch {
            recorder.state.collect { recording ->
                recording.recordingUri?.let { uri ->
                    mutableCreate.update {
                        it.copy(voiceNoteUri = uri, voiceDurationSec = ((recording.durationMs + 999) / 1000).toInt().coerceAtMost(300))
                    }
                }
            }
        }
    }

    private fun observeSession() {
        viewModelScope.launch {
            repository.session.retryWhen { failure, attempt ->
                mutableDashboard.update { it.copy(isLoading = false, error = failure.userMessage()) }
                delay(retryDelay(attempt))
                true
            }.collectLatest { user ->
                val previousUser = mutableDashboard.value.user
                val accountChanged = previousUser?.uid != user?.uid
                val scopeChanged = accountChanged || previousUser?.coupleSpaceId != user?.coupleSpaceId
                if (scopeChanged) {
                    openJob?.cancel()
                    sendJob?.cancel()
                    mutableOpened.value = OpenEnvelopeState()
                    activityTracker.reset()
                }
                if (accountChanged) {
                    recorder.discard()
                    resetSendAttempt()
                    mutableCreate.value = CreateEnvelopeState()
                }
                mutableDashboard.update {
                    it.copy(
                        user = user,
                        isLoading = (user != null && user.name.isBlank()) || (scopeChanged && user?.coupleSpaceId != null),
                        partner = if (scopeChanged) null else it.partner,
                        space = if (scopeChanged) null else it.space,
                        envelopes = if (scopeChanged) emptyList() else it.envelopes,
                        error = null,
                    )
                }
                if (user == null) {
                    activityTracker.reset()
                    mutableDashboard.value = DashboardUiState(isLoading = false, isDemo = repository.isDemo)
                    mutableOpened.value = OpenEnvelopeState()
                    return@collectLatest
                }
                val spaceId = user.coupleSpaceId
                if (spaceId == null) {
                    activityTracker.reset()
                    mutableDashboard.update { it.copy(space = null, partner = null, envelopes = emptyList()) }
                    return@collectLatest
                }
                combine(
                    repository.observeSpace(spaceId),
                    user.partnerId?.let(repository::observePartner) ?: flowOf(null),
                    repository.observeEnvelopes(spaceId),
                ) { space, partner, envelopes -> Triple(space, partner, envelopes) }
                    .retryWhen { failure, attempt ->
                        mutableDashboard.update { it.copy(isLoading = false, error = failure.userMessage()) }
                        delay(retryDelay(attempt))
                        true
                    }
                    .collect { (space, partner, envelopes) ->
                        val sorted = envelopes.sortedByDescending { it.createdAt }
                        mutableDashboard.update { it.copy(isLoading = false, space = space, partner = partner, envelopes = sorted, error = null) }
                        val openedId = mutableOpened.value.envelope?.envelopeId
                        sorted.firstOrNull { it.envelopeId == openedId }?.let { metadata ->
                            mutableOpened.update { current ->
                                val letter = current.envelope
                                if (letter == null || letter.envelopeId != metadata.envelopeId) current
                                else current.copy(envelope = letter.copy(
                                    isOpened = metadata.isOpened,
                                    lastOpenedAt = metadata.lastOpenedAt,
                                    openCount = metadata.openCount,
                                    partnerReaction = metadata.partnerReaction,
                                ))
                            }
                        }
                        activityTracker.update(spaceId, user.uid, sorted, partner?.name?.ifBlank { "Your partner" } ?: "Your partner")
                            .forEach { mutableAlerts.tryEmit(it) }
                    }
            }
        }
    }

    fun signInAnonymously(name: String) = pairingOperation {
        require(name.trim().isNotEmpty()) { "Tell us the name your partner calls you." }
        repository.signInAnonymously(name.trim())
    }

    fun signInEmail(name: String, email: String, password: String, createAccount: Boolean) = pairingOperation {
        require(email.trim().contains('@')) { "Enter a valid email address." }
        require(password.length >= 6) { "Use a password with at least six characters." }
        if (createAccount) require(name.isNotBlank()) { "Add your name so your partner knows it's you." }
        repository.signInEmail(name.trim(), email.trim(), password, createAccount)
    }

    fun signInGoogle(idToken: String) = pairingOperation { repository.signInGoogle(idToken) }

    fun createSpace(anniversaryDate: Long) = pairingOperation {
        require(anniversaryDate <= System.currentTimeMillis()) { "Your anniversary can't be in the future." }
        repository.createSpace(anniversaryDate)
    }

    fun joinSpace(code: String) = pairingOperation {
        val normalized = code.trim().map { character -> character.digitToIntOrNull()?.digitToChar() ?: character }.joinToString("")
        require(normalized.matches(Regex("[0-9]{6}"))) { "Enter the six-digit invite code from your partner." }
        repository.joinSpace(normalized)
    }

    private fun pairingOperation(action: suspend () -> Unit) {
        if (mutablePairing.value.isBusy) return
        mutablePairing.value = PairingUiState(isBusy = true)
        viewModelScope.launch {
            try {
                action()
                mutablePairing.value = PairingUiState()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutablePairing.value = PairingUiState(error = failure.userMessage())
            }
        }
    }

    fun selectTab(sent: Boolean) { mutableDashboard.update { it.copy(showSent = sent) } }

    fun setMood(mood: String) {
        val userId = mutableDashboard.value.user?.uid ?: return
        viewModelScope.launch {
            try {
                repository.updateMood(mood)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (mutableDashboard.value.user?.uid == userId) mutableAlerts.emit(failure.userMessage())
            }
        }
    }

    /** The confirmed seal starts one fetch; the unfold animation only controls presentation. */
    fun openEnvelope(envelopeId: String) {
        if (mutableOpened.value.isLoading) return
        openJob?.cancel()
        val metadata = mutableDashboard.value.envelopes.firstOrNull { it.envelopeId == envelopeId }
        val opening = EnvelopeOpeningDetails(
            requestId = UUID.randomUUID().toString(), envelopeId = envelopeId,
            titlePrompt = metadata?.titlePrompt.orEmpty(),
            envelopeColorHex = metadata?.envelopeColorHex ?: "#EBC4C4",
            sealIcon = metadata?.sealIcon ?: "Heart",
        )
        mutableOpened.value = OpenEnvelopeState(isLoading = true, opening = opening)
        openJob = viewModelScope.launch {
            try {
                val envelope = repository.openEnvelope(envelopeId)
                mutableOpened.update { current ->
                    if (current.opening?.requestId == opening.requestId) OpenEnvelopeState(envelope = envelope, opening = opening)
                    else current
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutableOpened.update { current ->
                    if (current.opening?.requestId == opening.requestId) OpenEnvelopeState(error = failure.userMessage(), opening = opening)
                    else current
                }
                mutableAlerts.emit(failure.userMessage())
            }
        }
    }

    fun dismissLetter() {
        openJob?.cancel()
        mutableOpened.value = OpenEnvelopeState()
    }

    fun react(reaction: String) {
        val envelope = mutableOpened.value.envelope ?: return
        if (mutableOpened.value.isReacting || envelope.receiverId != mutableDashboard.value.user?.uid) return
        mutableOpened.update { it.copy(isReacting = true, error = null) }
        viewModelScope.launch {
            try {
                repository.react(envelope.envelopeId, reaction)
                mutableOpened.update { current ->
                    if (current.envelope?.envelopeId == envelope.envelopeId) {
                        current.copy(envelope = current.envelope.copy(partnerReaction = reaction), isReacting = false)
                    } else current
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutableOpened.update {
                    if (it.envelope?.envelopeId == envelope.envelopeId) it.copy(isReacting = false, error = failure.userMessage()) else it
                }
            }
        }
    }

    fun startNewEnvelope() {
        if (mutableCreate.value.isSaving) return
        if (mutableCreate.value.isAwaitingConfirmation) {
            mutableAlerts.tryEmit("Your previous letter is waiting for confirmation. Retry sealing it first.")
            return
        }
        recorder.discard()
        resetSendAttempt()
        mutableCreate.value = CreateEnvelopeState()
    }

    fun updateDraft(transform: (CreateEnvelopeState) -> CreateEnvelopeState) {
        if (mutableCreate.value.isSaving || mutableCreate.value.isAwaitingConfirmation) return
        mutableCreate.update {
            val next = transform(it).copy(error = null, wasSent = false)
            if (next.photoUri != it.photoUri) stagedPhoto = null
            if (next.voiceNoteUri != it.voiceNoteUri) stagedVoice = null
            next
        }
    }

    fun startRecording() {
        if (mutableCreate.value.isSaving || mutableCreate.value.isAwaitingConfirmation) return
        mutableCreate.update { it.copy(voiceNoteUri = null, voiceDurationSec = 0) }
        recorder.startRecording()
    }

    fun stopRecording(cancel: Boolean = false) {
        if (cancel) {
            recorder.discard()
            mutableCreate.update { it.copy(voiceNoteUri = null, voiceDurationSec = 0) }
        } else {
            recorder.stopRecording()?.let { voice ->
                mutableCreate.update { it.copy(voiceNoteUri = voice.uri, voiceDurationSec = voice.durationSec) }
            }
        }
    }

    fun removeVoiceNote() {
        if (mutableCreate.value.isSaving || mutableCreate.value.isAwaitingConfirmation) return
        recorder.discard()
        mutableCreate.update { it.copy(voiceNoteUri = null, voiceDurationSec = 0) }
    }

    fun onAppBackgrounded() {
        if (recorderState.value.isRecording) stopRecording()
    }

    fun sendEnvelope() {
        val draft = mutableCreate.value
        if (draft.isSaving) return
        val validation = if (pendingEnvelopeDraft != null) null else when {
            mutableDashboard.value.user?.partnerId == null -> "Pair with your partner before sealing a letter."
            draft.titlePrompt.isBlank() -> "Give your envelope a moment to be opened."
            draft.titlePrompt.length > 120 -> "Keep your envelope title under 120 characters."
            draft.letterText.isBlank() -> "Write a few words from your heart first."
            draft.letterText.length > 20_000 -> "Keep your letter under 20,000 characters."
            draft.photoCaption.length > 240 -> "Keep your photo caption under 240 characters."
            recorderState.value.isRecording -> "Finish your voice note before sealing the envelope."
            draft.unlockRule == UnlockRule.DATE_LOCKED && (draft.unlockTimestamp ?: 0L) <= System.currentTimeMillis() -> "Choose a future date and time for this letter."
            draft.songUrl.isNotBlank() && !draft.songUrl.trim().startsWith("https://") -> "Use a secure https:// link for your song."
            else -> null
        }
        if (validation != null) {
            mutableCreate.update { it.copy(error = validation) }
            return
        }
        mutableCreate.update { it.copy(isSaving = true, error = null) }
        sendJob = viewModelScope.launch {
            try {
                val pending = pendingEnvelopeDraft ?: run {
                    val photo = draft.photoUri?.let { uri ->
                        stagedPhoto?.takeIf { it.first == uri }?.second
                            ?: repository.uploadMedia(uri, MediaKind.PHOTO).also { stagedPhoto = uri to it }
                    }
                    val voice = draft.voiceNoteUri?.let { uri ->
                        stagedVoice?.takeIf { it.first == uri }?.second
                            ?: repository.uploadMedia(uri, MediaKind.VOICE).also { stagedVoice = uri to it }
                    }
                    EnvelopeDraft(
                        titlePrompt = draft.titlePrompt.trim(),
                        letterText = draft.letterText.trim(),
                        envelopeColorHex = draft.envelopeColorHex,
                        sealIcon = draft.sealIcon,
                        photoUrl = photo,
                        photoCaption = draft.photoCaption.trim().takeIf { it.isNotEmpty() },
                        voiceNoteUrl = voice,
                        voiceDurationSec = draft.voiceDurationSec,
                        songUrl = draft.songUrl.trim().takeIf { it.isNotEmpty() },
                        unlockRule = draft.unlockRule,
                        unlockTimestamp = draft.unlockTimestamp.takeIf { draft.unlockRule == UnlockRule.DATE_LOCKED },
                        clientRequestId = draftRequestId,
                    ).also { pendingEnvelopeDraft = it }
                }
                repository.sendEnvelope(pending)
                recorder.detachCompletedRecording()
                resetSendAttempt()
                mutableCreate.update { it.copy(isSaving = false, isAwaitingConfirmation = false, wasSent = true) }
                mutableDashboard.update { it.copy(showSent = true) }
                mutableAlerts.emit("Sealed with love. Your envelope is waiting for your partner. 💌")
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                val unconfirmed = failure is UnconfirmedSendException && pendingEnvelopeDraft != null
                if (!unconfirmed) pendingEnvelopeDraft = null
                mutableCreate.update {
                    it.copy(
                        isSaving = false,
                        isAwaitingConfirmation = unconfirmed,
                        error = if (unconfirmed) "We couldn't confirm your seal. Tap Retry sealing to confirm the same letter." else failure.userMessage(),
                    )
                }
            }
        }
    }

    fun clearError() {
        mutableDashboard.update { it.copy(error = null) }
        mutablePairing.update { it.copy(error = null) }
        mutableCreate.update { it.copy(error = null) }
        mutableOpened.update { it.copy(error = null) }
    }

    fun signOut() = pairingOperation {
        openJob?.cancel()
        sendJob?.cancel()
        recorder.discard()
        resetSendAttempt()
        mutableCreate.value = CreateEnvelopeState()
        repository.signOut()
    }

    private fun resetSendAttempt() {
        draftRequestId = UUID.randomUUID().toString()
        pendingEnvelopeDraft = null
        stagedPhoto = null
        stagedVoice = null
    }

    override fun onCleared() {
        recorder.release()
        super.onCleared()
    }
}

private fun Throwable.userMessage(): String = localizedMessage?.takeIf { it.isNotBlank() }
    ?: "Something went wrong. Please try again."

private fun retryDelay(attempt: Long): Long = 1_000L shl attempt.coerceAtMost(5).toInt()
