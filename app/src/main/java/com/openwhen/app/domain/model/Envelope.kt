package com.openwhen.app.domain.model

import java.util.UUID

enum class UnlockRule { ANYTIME, DATE_LOCKED, ONE_TIME_ONLY }
enum class MediaKind { PHOTO, VOICE }

data class Envelope(
    val envelopeId: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val titlePrompt: String = "",
    val envelopeColorHex: String = "#F2C6C2",
    val sealIcon: String = "Heart",
    val letterText: String = "",
    val photoUrl: String? = null,
    val photoCaption: String? = null,
    val voiceNoteUrl: String? = null,
    val voiceDurationSec: Int = 0,
    val unlockRule: UnlockRule = UnlockRule.ANYTIME,
    val unlockTimestamp: Long? = null,
    val isOpened: Boolean = false,
    val lastOpenedAt: Long? = null,
    val openCount: Int = 0,
    val partnerReaction: String? = null,
    val songUrl: String? = null,
    val createdAt: Long = 0,
) {
    fun isLocked(now: Long): Boolean =
        unlockRule == UnlockRule.DATE_LOCKED && (unlockTimestamp ?: Long.MAX_VALUE) > now

    fun canOpen(userId: String, now: Long): Boolean = senderId == userId ||
        (receiverId == userId && !isLocked(now) && !(unlockRule == UnlockRule.ONE_TIME_ONLY && isOpened))
}

data class EnvelopeDraft(
    val titlePrompt: String,
    val letterText: String,
    val envelopeColorHex: String = "#F2C6C2",
    val sealIcon: String = "Heart",
    val photoUrl: String? = null,
    val photoCaption: String? = null,
    val voiceNoteUrl: String? = null,
    val voiceDurationSec: Int = 0,
    val songUrl: String? = null,
    val unlockRule: UnlockRule = UnlockRule.ANYTIME,
    val unlockTimestamp: Long? = null,
    val clientRequestId: String = UUID.randomUUID().toString(),
)
