package com.openwhen.app.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openwhen.app.domain.model.CoupleSpace
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.domain.model.EnvelopeDraft
import com.openwhen.app.domain.model.MediaKind
import com.openwhen.app.domain.model.User
import com.openwhen.app.domain.repository.EnvelopeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoEnvelopeRepository @Inject constructor(@ApplicationContext private val context: Context) : EnvelopeRepository {
    override val isDemo = true
    private val preferences = context.getSharedPreferences("open_when_demo", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mutex = Mutex()
    private val space = DemoSeedDataSource.space().let { seed ->
        val anniversary = preferences.getLong("anniversaryDate", seed.anniversaryDate)
        val created = preferences.getLong("spaceCreatedAt", seed.createdAt)
        preferences.edit().putLong("anniversaryDate", anniversary).putLong("spaceCreatedAt", created).apply()
        seed.copy(anniversaryDate = anniversary, createdAt = created)
    }
    private val mutableSession = MutableStateFlow<User?>(DemoSeedDataSource.user.copy(name = preferences.getString("name", "Noor") ?: "Noor"))
    override val session: Flow<User?> = mutableSession
    private val envelopes = MutableStateFlow(
        runCatching {
            preferences.getString("envelopes", null)?.let {
                gson.fromJson<List<Envelope>>(it, object : TypeToken<List<Envelope>>() {}.type)
            }
        }.getOrNull() ?: DemoSeedDataSource.envelopes(resourceRoot = "android.resource://${context.packageName}"),
    )

    override suspend fun signInAnonymously(name: String) {
        val safeName = name.trim().ifBlank { "Noor" }.take(60)
        preferences.edit().putString("name", safeName).apply()
        mutableSession.value = DemoSeedDataSource.user.copy(name = safeName)
    }

    override suspend fun signInEmail(name: String, email: String, password: String, createAccount: Boolean) {
        require(email.contains('@') && password.length >= 6) { "Enter an email and a password of at least six characters." }
        signInAnonymously(name)
    }

    override suspend fun signInGoogle(idToken: String) = signInAnonymously("Noor")
    override fun observeSpace(spaceId: String): Flow<CoupleSpace?> = mutableSession.map { if (it != null) space else null }
    override fun observePartner(partnerId: String): Flow<User?> = mutableSession.map { if (it != null) DemoSeedDataSource.partner else null }
    override fun observeEnvelopes(spaceId: String): Flow<List<Envelope>> = envelopes.map { items ->
        val uid = mutableSession.value?.uid
        items.map { if (it.senderId == uid) it else it.copy(letterText = "", photoUrl = null, photoCaption = null, voiceNoteUrl = null, songUrl = null) }
            .sortedByDescending(Envelope::createdAt)
    }

    override suspend fun createSpace(anniversaryDate: Long): CoupleSpace {
        checkNotNull(mutableSession.value) { "Sign in first." }
        return space
    }

    override suspend fun joinSpace(inviteCode: String) {
        require(inviteCode == space.inviteCode) { "For the demo, use the invitation code 143143." }
        if (mutableSession.value == null) signInAnonymously("Noor")
    }

    override suspend fun sendEnvelope(draft: EnvelopeDraft): String = mutex.withLock {
        val user = checkNotNull(mutableSession.value) { "Sign in first." }
        val id = "${user.uid}_${draft.clientRequestId}"
        if (envelopes.value.any { it.envelopeId == id }) return@withLock id
        validateDraft(draft)
        val envelope = Envelope(
            envelopeId = id, senderId = user.uid, receiverId = checkNotNull(user.partnerId),
            titlePrompt = draft.titlePrompt.trim(), letterText = draft.letterText.trim(),
            envelopeColorHex = draft.envelopeColorHex, sealIcon = draft.sealIcon,
            photoUrl = draft.photoUrl, photoCaption = draft.photoCaption, voiceNoteUrl = draft.voiceNoteUrl,
            voiceDurationSec = draft.voiceDurationSec, songUrl = draft.songUrl,
            unlockRule = draft.unlockRule, unlockTimestamp = draft.unlockTimestamp, createdAt = System.currentTimeMillis(),
        )
        persist(listOf(envelope) + envelopes.value)
        id
    }

    override suspend fun openEnvelope(envelopeId: String): Envelope = mutex.withLock {
        val uid = checkNotNull(mutableSession.value) { "Sign in first." }.uid
        val item = envelopes.value.firstOrNull { it.envelopeId == envelopeId } ?: error("This envelope is no longer available.")
        if (item.senderId == uid) return@withLock item
        check(item.canOpen(uid, System.currentTimeMillis())) { "This envelope is sealed until its opening time, or has already been opened once." }
        val opened = item.copy(isOpened = true, openCount = item.openCount + 1, lastOpenedAt = System.currentTimeMillis())
        persist(envelopes.value.map { if (it.envelopeId == envelopeId) opened else it })
        opened
    }

    override suspend fun react(envelopeId: String, reaction: String) = mutex.withLock {
        val uid = checkNotNull(mutableSession.value).uid
        val envelope = envelopes.value.firstOrNull { it.envelopeId == envelopeId } ?: error("Envelope not found.")
        check(envelope.receiverId == uid && envelope.isOpened) { "Open the envelope before leaving a reaction." }
        require(reaction.isNotBlank() && reaction.length <= 160)
        persist(envelopes.value.map { if (it.envelopeId == envelopeId) it.copy(partnerReaction = reaction) else it })
    }

    override suspend fun uploadMedia(localUri: String, kind: MediaKind): String = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "demo_media").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.${if (kind == MediaKind.VOICE) "m4a" else "jpg"}")
        try {
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(localUri))) { "Unable to read the selected attachment." }.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 20 * 1024 * 1024) { "Attachments must be smaller than 20 MB." }
                        output.write(buffer, 0, read)
                    }
                }
            }
            Uri.fromFile(target).toString()
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    override suspend fun updateMood(mood: String) {
        mutableSession.value = mutableSession.value?.copy(currentMood = mood.take(60))
    }

    override suspend fun signOut() { mutableSession.value = null }

    private fun persist(value: List<Envelope>) {
        preferences.edit().putString("envelopes", gson.toJson(value)).apply()
        envelopes.value = value
    }
}

internal fun validateDraft(draft: EnvelopeDraft, requireFutureDate: Boolean = true) {
    val earliestUnlock = if (requireFutureDate) System.currentTimeMillis() else 0L
    require(draft.titlePrompt.isNotBlank() && draft.titlePrompt.length <= 120) { "Give your envelope a title of up to 120 characters." }
    require(draft.letterText.isNotBlank() && draft.letterText.length <= 20_000) { "Write a letter of up to 20,000 characters." }
    require(Regex("^#[0-9A-Fa-f]{6}$").matches(draft.envelopeColorHex)) { "Choose a valid envelope color." }
    require(draft.sealIcon in listOf("Heart", "Star", "Moon", "Hug", "Coffee")) { "Choose a wax seal." }
    require(draft.unlockRule != com.openwhen.app.domain.model.UnlockRule.DATE_LOCKED ||
        (draft.unlockTimestamp ?: 0) > earliestUnlock) { "Choose a future opening date." }
    require(draft.voiceDurationSec in 0..300) { "Voice notes can be up to five minutes long." }
    require(draft.songUrl.isNullOrBlank() || runCatching { java.net.URI(draft.songUrl).let { it.scheme == "https" && !it.host.isNullOrBlank() } }.getOrDefault(false)) { "Song links must start with https://." }
}
