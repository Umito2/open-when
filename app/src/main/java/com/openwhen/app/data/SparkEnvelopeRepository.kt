package com.openwhen.app.data

import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.openwhen.app.domain.model.CoupleSpace
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.domain.model.EnvelopeDraft
import com.openwhen.app.domain.model.MediaKind
import com.openwhen.app.domain.model.UnlockRule
import com.openwhen.app.domain.model.UnconfirmedSendException
import com.openwhen.app.domain.model.User
import com.openwhen.app.domain.repository.EnvelopeRepository
import java.io.IOException
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Text letters backed by Firebase Authentication and Firestore's no-cost Spark plan. */
@OptIn(ExperimentalCoroutinesApi::class)
class SparkEnvelopeRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : EnvelopeRepository {
    override val isDemo = false
    private val random = SecureRandom()
    private val pendingOpenRequests = ConcurrentHashMap<String, String>()

    override val session: Flow<User?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged().flatMapLatest { uid ->
        pendingOpenRequests.clear()
        if (uid == null) flowOf(null)
        else observeUser(uid).onStart { emit(User(uid = uid)) }
    }

    override suspend fun signInAnonymously(name: String) {
        if (auth.currentUser == null) auth.signInAnonymously().awaitSpark()
        provisionUser(name)
    }

    override suspend fun signInEmail(name: String, email: String, password: String, createAccount: Boolean) {
        if (createAccount) {
            val current = auth.currentUser
            if (current?.isAnonymous == true) {
                current.linkWithCredential(EmailAuthProvider.getCredential(email.trim(), password)).awaitSpark()
            } else auth.createUserWithEmailAndPassword(email.trim(), password).awaitSpark()
        } else auth.signInWithEmailAndPassword(email.trim(), password).awaitSpark()
        provisionUser(name)
    }

    override suspend fun signInGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val current = auth.currentUser
        if (current?.isAnonymous == true) current.linkWithCredential(credential).awaitSpark()
        else auth.signInWithCredential(credential).awaitSpark()
        provisionUser(auth.currentUser?.displayName.orEmpty())
    }

    private suspend fun provisionUser(name: String) {
        val uid = currentUid()
        val reference = userReference(uid)
        firestore.runTransaction { transaction ->
            if (!transaction.get(reference).exists()) {
                transaction.set(reference, User(uid = uid, name = name.trim().ifBlank { "My love" }.take(60)))
            } else if (name.isNotBlank()) transaction.update(reference, "name", name.trim().take(60))
        }.awaitSpark()
    }

    private fun observeUser(uid: String): Flow<User?> = userReference(uid).watchSpark { it.toObject(User::class.java) }
    override fun observePartner(partnerId: String): Flow<User?> = observeUser(partnerId)
    override fun observeSpace(spaceId: String): Flow<CoupleSpace?> = spaceReference(spaceId).watchSpark {
        if (it.exists()) mapToSpace(it.data.orEmpty()) else null
    }

    override fun observeEnvelopes(spaceId: String): Flow<List<Envelope>> = callbackFlow {
        val receivedServerSnapshot = AtomicBoolean(false)
        val watchdog = launch {
            delay(20_000)
            if (!receivedServerSnapshot.get()) close(IOException("We could not reach your shared space. Check your connection; we will retry."))
        }
        val registration = spaceReference(spaceId).collection("envelopes")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) close(failure)
                else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                    receivedServerSnapshot.set(true)
                    watchdog.cancel()
                    trySend(snapshot.documents.map { mapToEnvelope(it.data.orEmpty()) })
                }
            }
        awaitClose { registration.remove(); watchdog.cancel() }
    }

    override suspend fun createSpace(anniversaryDate: Long): CoupleSpace {
        val uid = currentUid()
        repeat(10) {
            val code = (random.nextInt(900_000) + 100_000).toString()
            val proposedSpace = spaceReference(UUID.randomUUID().toString())
            val result = firestore.runTransaction { transaction ->
                val user = transaction.get(userReference(uid)).toObject(User::class.java)
                    ?: error("Please sign in again to finish setting up your profile.")
                val existingId = user.coupleSpaceId
                if (existingId != null) {
                    val existing = transaction.get(spaceReference(existingId))
                    val space = mapToSpace(existing.data.orEmpty())
                    if (space.user1Id == uid && space.user2Id == null) {
                        val invite = firestore.collection("_invites").document(space.inviteCode)
                        val invitation = transaction.get(invite)
                        check(invitation.exists()) { "This invitation is unavailable. Please try again." }
                        transaction.update(invite, "expiresAt", invitationExpiry())
                    }
                    existingId
                } else {
                    val invite = firestore.collection("_invites").document(code)
                    if (transaction.get(invite).exists()) null
                    else {
                        transaction.set(proposedSpace, mapOf(
                            "spaceId" to proposedSpace.id, "inviteCode" to code,
                            "user1Id" to uid, "user2Id" to null,
                            "anniversaryDate" to anniversaryDate, "createdAt" to FieldValue.serverTimestamp(),
                        ))
                        transaction.set(invite, mapOf(
                            "spaceId" to proposedSpace.id, "ownerUid" to uid,
                            "createdAt" to FieldValue.serverTimestamp(), "expiresAt" to invitationExpiry(),
                        ))
                        transaction.update(userReference(uid), mapOf("coupleSpaceId" to proposedSpace.id, "partnerId" to null))
                        proposedSpace.id
                    }
                }
            }.awaitSpark()
            if (result != null) return mapToSpace(spaceReference(result).get(Source.SERVER).awaitSpark().data.orEmpty())
        }
        error("We could not create an invitation just now. Please try again.")
    }

    override suspend fun joinSpace(inviteCode: String) {
        val uid = currentUid()
        val code = inviteCode.trim()
        require(code.matches(Regex("[0-9]{6}"))) { "Enter your partner's six-digit invitation." }
        firestore.runTransaction { transaction ->
            val user = transaction.get(userReference(uid)).toObject(User::class.java)
                ?: error("Please sign in again to finish setting up your profile.")
            // A successful retry after a lost network response must not claim another invitation.
            if (user.partnerId != null) return@runTransaction Unit
            check(user.coupleSpaceId == null) { "You already have a space. Share your own invitation with your partner." }
            val invite = firestore.collection("_invites").document(code)
            val snapshot = transaction.get(invite)
            check(snapshot.exists()) { "This invitation is unavailable. Check the code with your partner." }
            val owner = checkNotNull(snapshot.getString("ownerUid")) { "This invitation is unavailable." }
            val spaceId = checkNotNull(snapshot.getString("spaceId")) { "This invitation is unavailable." }
            check(owner != uid) { "This is your invitation. Send it to your partner." }
            val expiresAt = snapshot.getTimestamp("expiresAt")?.toDate()?.time ?: 0L
            check(expiresAt > System.currentTimeMillis()) { "This invitation expired. Ask your partner to refresh it." }
            // Only the invitation and our own profile are read before membership exists.
            transaction.update(spaceReference(spaceId), "user2Id", uid)
            transaction.update(userReference(owner), "partnerId", uid)
            transaction.update(userReference(uid), mapOf("partnerId" to owner, "coupleSpaceId" to spaceId))
            transaction.delete(invite)
        }.awaitSpark()
    }

    override suspend fun sendEnvelope(draft: EnvelopeDraft): String {
        validateDraft(draft, requireFutureDate = false)
        require(draft.unlockRule != UnlockRule.ONE_TIME_ONLY) { "Choose anytime or a special date for this text letter." }
        require(draft.photoUrl == null && draft.voiceNoteUrl == null) { "This edition sends text letters and song links." }
        val uid = currentUid()
        val envelopeId = "${uid}_${draft.clientRequestId}"
        var canReconcileSend = true
        while (true) {
            try {
                firestore.runTransaction { transaction ->
                    val user = transaction.get(userReference(uid)).toObject(User::class.java)
                        ?: error("Sign in first.")
                    val spaceId = checkNotNull(user.coupleSpaceId) { "Connect with your partner first." }
                    val receiver = checkNotNull(user.partnerId) { "Invite your partner before sealing a letter." }
                    val envelope = spaceReference(spaceId).collection("envelopes").document(envelopeId)
                    val content = envelope.collection("content").document("letter")
                    val existing = transaction.get(envelope)
                    if (existing.exists()) {
                        check(existing.getString("senderId") == uid) { "This letter could not be confirmed." }
                        val existingContent = transaction.get(content)
                        check(existing.getString("titlePrompt") == draft.titlePrompt.trim() &&
                            existing.getString("envelopeColorHex") == draft.envelopeColorHex &&
                            existing.getString("sealIcon") == draft.sealIcon &&
                            existing.getString("unlockRule") == draft.unlockRule.name &&
                            existing.getLong("unlockTimestamp") == draft.unlockTimestamp.takeIf { draft.unlockRule == UnlockRule.DATE_LOCKED } &&
                            existingContent.getString("letterText") == draft.letterText.trim() &&
                            existingContent.getString("songUrl") == draft.songUrl?.takeIf { it.isNotBlank() }) {
                            "This letter was already sealed with different details."
                        }
                    } else {
                        transaction.set(envelope, mapOf(
                            "envelopeId" to envelopeId, "senderId" to uid, "receiverId" to receiver,
                            "titlePrompt" to draft.titlePrompt.trim(), "envelopeColorHex" to draft.envelopeColorHex,
                            "sealIcon" to draft.sealIcon, "unlockRule" to draft.unlockRule.name,
                            "unlockTimestamp" to if (draft.unlockRule == UnlockRule.DATE_LOCKED) draft.unlockTimestamp else null,
                            "isOpened" to false, "lastOpenedAt" to null, "openCount" to 0,
                            "partnerReaction" to null, "lastOpenRequestId" to null,
                            "createdAt" to FieldValue.serverTimestamp(),
                        ))
                        transaction.set(content, mapOf("letterText" to draft.letterText.trim(), "songUrl" to draft.songUrl?.takeIf { it.isNotBlank() }))
                    }
                }.awaitSpark()
                return envelopeId
            } catch (failure: FirebaseFirestoreException) {
                // A timed-out send can still commit. Re-read its deterministic document once before
                // treating a duplicate-create rejection as a permanent failure.
                if (failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && canReconcileSend) {
                    canReconcileSend = false
                    continue
                }
                if (failure.code in transientCodes) {
                    throw UnconfirmedSendException("Your envelope may already be sealed. Retry to confirm it safely.", failure)
                }
                throw failure
            } catch (failure: IOException) {
                throw UnconfirmedSendException("Your envelope may already be sealed. Retry to confirm it safely.", failure)
            }
        }
    }

    override suspend fun openEnvelope(envelopeId: String): Envelope {
        val uid = currentUid()
        val key = "$uid/$envelopeId"
        val requestId = pendingOpenRequests.getOrPut(key) { UUID.randomUUID().toString() }
        var canReconcileReceipt = true
        while (true) {
            try {
                val result = firestore.runTransaction { transaction ->
                    val user = transaction.get(userReference(uid)).toObject(User::class.java) ?: error("Sign in first.")
                    val spaceId = checkNotNull(user.coupleSpaceId) { "Connect with your partner first." }
                    val reference = spaceReference(spaceId).collection("envelopes").document(envelopeId)
                    val metadata = transaction.get(reference)
                    check(metadata.exists()) { "This envelope is unavailable." }
                    val envelope = mapToEnvelope(metadata.data.orEmpty())
                    val isSender = envelope.senderId == uid
                    check(isSender || envelope.receiverId == uid) { "This envelope belongs to another couple." }
                    // Security rules independently use server time before releasing date-locked content.
                    check(isSender || !envelope.isLocked(System.currentTimeMillis())) { "This letter is still waiting for its special date." }
                    val content = transaction.get(reference.collection("content").document("letter"))
                    check(content.exists()) { "This letter is unavailable. Please try again." }
                    val alreadyRecorded = metadata.getString("lastOpenRequestId") == requestId
                    val opened = if (!isSender && !alreadyRecorded) {
                        transaction.update(reference, mapOf(
                            "isOpened" to true, "openCount" to FieldValue.increment(1L),
                            "lastOpenedAt" to FieldValue.serverTimestamp(), "lastOpenRequestId" to requestId,
                        ))
                        envelope.copy(isOpened = true, openCount = envelope.openCount + 1, lastOpenedAt = System.currentTimeMillis())
                    } else envelope
                    opened.copy(letterText = content.getString("letterText").orEmpty(), songUrl = content.getString("songUrl"))
                }.awaitSpark()
                pendingOpenRequests.remove(key, requestId)
                return result
            } catch (failure: FirebaseFirestoreException) {
                // A previous timed-out task can finish concurrently with its retry. Rules can reject
                // the duplicate write before Firestore retries; re-read once to recognize its receipt.
                if (failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED && canReconcileReceipt) {
                    canReconcileReceipt = false
                    continue
                }
                if (failure.code !in transientCodes) pendingOpenRequests.remove(key, requestId)
                throw failure
            }
        }
    }

    override suspend fun react(envelopeId: String, reaction: String) {
        require(reaction.isNotBlank() && reaction.length <= 160) { "Choose a reaction of up to 160 characters." }
        val uid = currentUid()
        firestore.runTransaction { transaction ->
            val user = transaction.get(userReference(uid)).toObject(User::class.java) ?: error("Sign in first.")
            val spaceId = checkNotNull(user.coupleSpaceId) { "Connect with your partner first." }
            val reference = spaceReference(spaceId).collection("envelopes").document(envelopeId)
            val envelope = transaction.get(reference)
            check(envelope.getString("receiverId") == uid && envelope.getBoolean("isOpened") == true) { "Open the letter before leaving a reaction." }
            transaction.update(reference, "partnerReaction", reaction.trim())
        }.awaitSpark()
    }

    override suspend fun uploadMedia(localUri: String, kind: MediaKind): String =
        error("This edition sends text letters and song links. Photo and voice uploads are unavailable.")

    override suspend fun updateMood(mood: String) {
        userReference(currentUid()).update("currentMood", mood.trim().take(60)).awaitSpark()
    }

    override suspend fun signOut() {
        pendingOpenRequests.clear()
        auth.signOut()
    }

    private fun currentUid(): String = checkNotNull(auth.currentUser?.uid) { "Sign in first." }
    private fun userReference(uid: String) = firestore.collection("users").document(uid)
    private fun spaceReference(spaceId: String) = firestore.collection("couple_spaces").document(spaceId)
    private fun invitationExpiry() = Timestamp(Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000L))

    private fun mapToSpace(data: Map<String, Any?>) = CoupleSpace(
        spaceId = data["spaceId"] as? String ?: "", inviteCode = data["inviteCode"] as? String ?: "",
        user1Id = data["user1Id"] as? String ?: "", user2Id = data["user2Id"] as? String,
        anniversaryDate = millis(data["anniversaryDate"]) ?: 0, createdAt = millis(data["createdAt"]) ?: 0,
    )

    private fun mapToEnvelope(data: Map<String, Any?>) = Envelope(
        envelopeId = data["envelopeId"] as? String ?: "", senderId = data["senderId"] as? String ?: "",
        receiverId = data["receiverId"] as? String ?: "", titlePrompt = data["titlePrompt"] as? String ?: "",
        envelopeColorHex = data["envelopeColorHex"] as? String ?: "#F2C6C2", sealIcon = data["sealIcon"] as? String ?: "Heart",
        unlockRule = runCatching { UnlockRule.valueOf(data["unlockRule"] as String) }.getOrDefault(UnlockRule.ANYTIME),
        unlockTimestamp = millis(data["unlockTimestamp"]), isOpened = data["isOpened"] as? Boolean ?: false,
        lastOpenedAt = millis(data["lastOpenedAt"]), openCount = (data["openCount"] as? Number)?.toInt() ?: 0,
        partnerReaction = data["partnerReaction"] as? String, createdAt = millis(data["createdAt"]) ?: 0,
    )

    private fun millis(value: Any?): Long? = when (value) {
        is Timestamp -> value.toDate().time
        is Number -> value.toLong()
        else -> null
    }

    private companion object {
        val transientCodes = setOf(
            FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            FirebaseFirestoreException.Code.INTERNAL, FirebaseFirestoreException.Code.UNKNOWN,
            FirebaseFirestoreException.Code.CANCELLED, FirebaseFirestoreException.Code.ABORTED,
        )
    }
}

private fun <T> DocumentReference.watchSpark(convert: (DocumentSnapshot) -> T): Flow<T> = callbackFlow {
    val receivedServerSnapshot = AtomicBoolean(false)
    val watchdog = launch {
        delay(20_000)
        if (!receivedServerSnapshot.get()) close(IOException("We could not connect. Check your internet connection; we will retry."))
    }
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
        if (failure != null) close(failure)
        else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
            receivedServerSnapshot.set(true)
            watchdog.cancel()
            trySend(convert(snapshot))
        }
    }
    awaitClose { registration.remove(); watchdog.cancel() }
}

private class SparkTaskResult<T>(val value: T)

private suspend fun <T> Task<T>.awaitSpark(): T {
    val result = withTimeoutOrNull(30_000) { SparkTaskResult(await()) }
        ?: throw IOException("The connection took too long. Check your internet connection and try again.")
    return result.value
}
