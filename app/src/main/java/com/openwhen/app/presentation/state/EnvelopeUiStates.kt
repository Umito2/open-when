package com.openwhen.app.presentation.state

import com.openwhen.app.domain.model.CoupleSpace
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.domain.model.UnlockRule
import com.openwhen.app.domain.model.User
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class DashboardUiState(
    val isLoading: Boolean = true,
    val user: User? = null,
    val partner: User? = null,
    val space: CoupleSpace? = null,
    val envelopes: List<Envelope> = emptyList(),
    val showSent: Boolean = false,
    val isDemo: Boolean = false,
    val error: String? = null,
) {
    val inbox: List<Envelope> get() = envelopes.filter { it.receiverId == user?.uid }
    val sent: List<Envelope> get() = envelopes.filter { it.senderId == user?.uid }
    val visibleEnvelopes: List<Envelope> get() = if (showSent) sent else inbox
    val daysTogether: Int
        get() = space?.anniversaryDate?.let { anniversary ->
            val start = Instant.ofEpochMilli(anniversary).atZone(ZoneId.systemDefault()).toLocalDate()
            ChronoUnit.DAYS.between(start, LocalDate.now()).coerceAtLeast(0).toInt()
        } ?: 0
}

data class CreateEnvelopeState(
    val titlePrompt: String = "Open when you miss me",
    val letterText: String = "",
    val envelopeColorHex: String = "#EBC4C4",
    val sealIcon: String = "Heart",
    val photoUri: String? = null,
    val photoCaption: String = "",
    val voiceNoteUri: String? = null,
    val voiceDurationSec: Int = 0,
    val songUrl: String = "",
    val unlockRule: UnlockRule = UnlockRule.ANYTIME,
    val unlockTimestamp: Long? = null,
    val isSaving: Boolean = false,
    val isAwaitingConfirmation: Boolean = false,
    val error: String? = null,
    val wasSent: Boolean = false,
)

data class OpenEnvelopeState(
    val envelope: Envelope? = null,
    val isLoading: Boolean = false,
    val isReacting: Boolean = false,
    val error: String? = null,
    val opening: EnvelopeOpeningDetails? = null,
)

/** Visual metadata only; the opening scene never receives sealed letter content. */
data class EnvelopeOpeningDetails(
    val requestId: String,
    val envelopeId: String,
    val titlePrompt: String,
    val envelopeColorHex: String,
    val sealIcon: String,
)

data class PairingUiState(val isBusy: Boolean = false, val error: String? = null)
