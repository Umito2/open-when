package com.openwhen.app.domain.repository

import com.openwhen.app.domain.model.CoupleSpace
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.domain.model.EnvelopeDraft
import com.openwhen.app.domain.model.MediaKind
import com.openwhen.app.domain.model.User
import kotlinx.coroutines.flow.Flow

interface EnvelopeRepository {
    val isDemo: Boolean
    val session: Flow<User?>
    suspend fun signInAnonymously(name: String)
    suspend fun signInEmail(name: String, email: String, password: String, createAccount: Boolean)
    suspend fun signInGoogle(idToken: String)
    fun observeSpace(spaceId: String): Flow<CoupleSpace?>
    fun observePartner(partnerId: String): Flow<User?>
    fun observeEnvelopes(spaceId: String): Flow<List<Envelope>>
    suspend fun createSpace(anniversaryDate: Long): CoupleSpace
    suspend fun joinSpace(inviteCode: String)
    suspend fun sendEnvelope(draft: EnvelopeDraft): String
    suspend fun openEnvelope(envelopeId: String): Envelope
    suspend fun react(envelopeId: String, reaction: String)
    suspend fun uploadMedia(localUri: String, kind: MediaKind): String
    suspend fun updateMood(mood: String)
    suspend fun signOut()
}
