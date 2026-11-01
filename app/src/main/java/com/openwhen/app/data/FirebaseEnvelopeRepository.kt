package com.openwhen.app.data

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.openwhen.app.domain.model.CoupleSpace
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.domain.model.EnvelopeDraft
import com.openwhen.app.domain.model.MediaKind
import com.openwhen.app.domain.model.UnlockRule
import com.openwhen.app.domain.model.UnconfirmedSendException
import com.openwhen.app.domain.model.User
import com.openwhen.app.domain.repository.EnvelopeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseEnvelopeRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val functions: FirebaseFunctions,
    @ApplicationContext private val context: Context,
) : EnvelopeRepository {
    override val isDemo = false
    private val pendingOpenRequests = ConcurrentHashMap<String, String>()
    override val session: Flow<User?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) flowOf(null) else observeUser(uid).onStart { emit(User(uid = uid)) }
    }

    override suspend fun signInAnonymously(name: String) {
        if (auth.currentUser == null) auth.signInAnonymously().await()
        provisionUser(name)
    }

    override suspend fun signInEmail(name: String, email: String, password: String, createAccount: Boolean) {
        if (createAccount) {
            val current = auth.currentUser
            if (current?.isAnonymous == true) current.linkWithCredential(EmailAuthProvider.getCredential(email.trim(), password)).await()
            else auth.createUserWithEmailAndPassword(email.trim(), password).await()
        } else auth.signInWithEmailAndPassword(email.trim(), password).await()
        provisionUser(name)
    }

    override suspend fun signInGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val current = auth.currentUser
        if (current?.isAnonymous == true) current.linkWithCredential(credential).await()
        else auth.signInWithCredential(credential).await()
        provisionUser(auth.currentUser?.displayName.orEmpty())
    }

    private suspend fun provisionUser(name: String) {
        val uid = checkNotNull(auth.currentUser?.uid) { "Sign in first." }
        val reference = firestore.collection("users").document(uid)
        firestore.runTransaction { transaction ->
            val existing = transaction.get(reference)
            if (!existing.exists()) transaction.set(reference, User(uid = uid, name = name.trim().ifBlank { "My love" }.take(60)))
            else if (name.isNotBlank()) transaction.update(reference, "name", name.trim().take(60))
        }.await()
    }

    private fun observeUser(uid: String): Flow<User?> = firestore.collection("users").document(uid).watch { it.toObject(User::class.java) }
    override fun observePartner(partnerId: String): Flow<User?> = observeUser(partnerId)
    override fun observeSpace(spaceId: String): Flow<CoupleSpace?> = firestore.collection("couple_spaces").document(spaceId).watch { it.toObject(CoupleSpace::class.java) }

    override fun observeEnvelopes(spaceId: String): Flow<List<Envelope>> = callbackFlow {
        val receivedServerSnapshot = AtomicBoolean(false)
        val watchdog = launch {
            delay(20_000)
            if (!receivedServerSnapshot.get()) close(IOException("We could not reach your shared space. Check your connection; we will retry."))
        }
        val registration = firestore.collection("couple_spaces").document(spaceId).collection("envelopes")
            .orderBy("createdAt", Query.Direction.DESCENDING).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) close(failure)
                else if (snapshot != null && !snapshot.metadata.isFromCache) {
                    receivedServerSnapshot.set(true)
                    watchdog.cancel()
                    trySend(snapshot.documents.map { mapToEnvelope(it.data.orEmpty()) })
                }
            }
        awaitClose { registration.remove(); watchdog.cancel() }
    }

    override suspend fun createSpace(anniversaryDate: Long): CoupleSpace = mapToSpace(call("createSpace", mapOf("anniversaryDate" to anniversaryDate)))
    override suspend fun joinSpace(inviteCode: String) { call("joinSpace", mapOf("inviteCode" to inviteCode.trim())) }

    override suspend fun sendEnvelope(draft: EnvelopeDraft): String {
        validateDraft(draft, requireFutureDate = false)
        val arguments = mapOf(
            "titlePrompt" to draft.titlePrompt.trim(), "letterText" to draft.letterText.trim(),
            "envelopeColorHex" to draft.envelopeColorHex, "sealIcon" to draft.sealIcon,
            "photoUrl" to draft.photoUrl, "photoCaption" to draft.photoCaption,
            "voiceNoteUrl" to draft.voiceNoteUrl, "voiceDurationSec" to draft.voiceDurationSec,
            "songUrl" to draft.songUrl, "unlockRule" to draft.unlockRule.name,
            "unlockTimestamp" to draft.unlockTimestamp, "requestId" to draft.clientRequestId,
        )
        val result = try { call("sendEnvelope", arguments) }
        catch (failure: FirebaseFunctionsException) {
            if (failure.code in setOf(
                FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                FirebaseFunctionsException.Code.INTERNAL, FirebaseFunctionsException.Code.UNKNOWN,
                FirebaseFunctionsException.Code.CANCELLED,
            )) throw UnconfirmedSendException("Your envelope may already be sealed. Retry to confirm it safely.", failure)
            throw failure
        } catch (failure: IOException) {
            throw UnconfirmedSendException("Your envelope may already be sealed. Retry to confirm it safely.", failure)
        }
        return result["envelopeId"] as? String ?: error("The envelope could not be sealed. Please try again.")
    }

    override suspend fun openEnvelope(envelopeId: String): Envelope {
        val requestId = pendingOpenRequests.getOrPut(envelopeId) { UUID.randomUUID().toString() }
        return try {
            mapToEnvelope(call("openEnvelope", mapOf("envelopeId" to envelopeId, "requestId" to requestId))).also {
                pendingOpenRequests.remove(envelopeId, requestId)
            }
        } catch (failure: FirebaseFunctionsException) {
            if (failure.code !in transientCodes) pendingOpenRequests.remove(envelopeId, requestId)
            throw failure
        }
    }

    override suspend fun react(envelopeId: String, reaction: String) {
        call("reactToEnvelope", mapOf("envelopeId" to envelopeId, "reaction" to reaction))
    }

    override suspend fun uploadMedia(localUri: String, kind: MediaKind): String {
        val uid = checkNotNull(auth.currentUser?.uid) { "Sign in first." }
        val user = firestore.collection("users").document(uid).get().await().toObject(User::class.java)
        val spaceId = checkNotNull(user?.coupleSpaceId) { "Connect with your partner first." }
        val uri = Uri.parse(localUri)
        val contentType = if (kind == MediaKind.VOICE) "audio/mp4" else context.contentResolver.getType(uri) ?: "image/jpeg"
        require(if (kind == MediaKind.VOICE) contentType.startsWith("audio/") else contentType.startsWith("image/")) { "Choose a valid attachment." }
        val reference = storage.reference.child("couple_media/$spaceId/$uid/${UUID.randomUUID()}")
        reference.putFile(uri, StorageMetadata.Builder().setContentType(contentType).build()).await()
        return reference.toString()
    }

    override suspend fun updateMood(mood: String) {
        val uid = checkNotNull(auth.currentUser?.uid)
        firestore.collection("users").document(uid).update("currentMood", mood.trim().take(60)).await()
    }

    override suspend fun signOut() { auth.signOut() }

    @Suppress("UNCHECKED_CAST")
    private suspend fun call(name: String, arguments: Map<String, Any?>): Map<String, Any?> =
        functions.getHttpsCallable(name).call(arguments).await().data as? Map<String, Any?>
            ?: error("The server returned an unexpected response.")

    private fun mapToSpace(data: Map<String, Any?>) = CoupleSpace(
        spaceId = data["spaceId"] as? String ?: "", inviteCode = data["inviteCode"] as? String ?: "",
        user1Id = data["user1Id"] as? String ?: "", user2Id = data["user2Id"] as? String,
        anniversaryDate = (data["anniversaryDate"] as? Number)?.toLong() ?: 0,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0,
    )

    private fun mapToEnvelope(data: Map<String, Any?>) = Envelope(
        envelopeId = data["envelopeId"] as? String ?: "", senderId = data["senderId"] as? String ?: "",
        receiverId = data["receiverId"] as? String ?: "", titlePrompt = data["titlePrompt"] as? String ?: "",
        envelopeColorHex = data["envelopeColorHex"] as? String ?: "#F2C6C2", sealIcon = data["sealIcon"] as? String ?: "Heart",
        letterText = data["letterText"] as? String ?: "", photoUrl = data["photoUrl"] as? String,
        photoCaption = data["photoCaption"] as? String, voiceNoteUrl = data["voiceNoteUrl"] as? String,
        voiceDurationSec = (data["voiceDurationSec"] as? Number)?.toInt() ?: 0,
        unlockRule = runCatching { UnlockRule.valueOf(data["unlockRule"] as String) }.getOrDefault(UnlockRule.ANYTIME),
        unlockTimestamp = (data["unlockTimestamp"] as? Number)?.toLong(), isOpened = data["isOpened"] as? Boolean ?: false,
        lastOpenedAt = (data["lastOpenedAt"] as? Number)?.toLong(), openCount = (data["openCount"] as? Number)?.toInt() ?: 0,
        partnerReaction = data["partnerReaction"] as? String, songUrl = data["songUrl"] as? String,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0,
    )

    private companion object {
        val transientCodes = setOf(
            FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            FirebaseFunctionsException.Code.INTERNAL, FirebaseFunctionsException.Code.UNKNOWN,
            FirebaseFunctionsException.Code.CANCELLED,
        )
    }
}

private fun <T> DocumentReference.watch(convert: (com.google.firebase.firestore.DocumentSnapshot) -> T): Flow<T> = callbackFlow {
    val registration = addSnapshotListener { snapshot, failure ->
        if (failure != null) close(failure)
        else if (snapshot != null) trySend(convert(snapshot))
    }
    awaitClose { registration.remove() }
}
