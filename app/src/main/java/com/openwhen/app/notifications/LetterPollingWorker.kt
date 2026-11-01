package com.openwhen.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.openwhen.app.BuildConfig
import com.openwhen.app.data.FirebaseStoreProvider
import com.openwhen.app.notification.LetterNotificationPreferences
import com.openwhen.app.notification.LetterNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** Android schedules these checks; fifteen minutes is a minimum interval, not a delivery promise. */
class LetterPollingWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val preferences = LetterNotificationPreferences(context)
    private val checkpoints = LetterCursorStore(context)

    override suspend fun doWork(): Result {
        if (!BuildConfig.SPARK_MODE) return Result.success()
        val uid = inputData.getString(LetterNotificationScheduler.UID_INPUT) ?: return Result.success()
        return try {
            withTimeout(55_000) {
                LetterNotificationScheduler.operationMutex.withLock { checkLetters(uid) }
            }
        } catch (_: TimeoutCancellationException) {
            retryBounded()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: FirebaseFirestoreException) {
            if (failure.code in setOf(FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED)) {
                Result.success()
            } else retryBounded()
        } catch (_: Exception) {
            retryBounded()
        }
    }

    private fun active(uid: String): Boolean = !isStopped && LetterPollingPolicy.sessionAllows(
        uid, FirebaseAuth.getInstance().currentUser?.uid, preferences.isEnabled(uid),
        LetterNotifications.canNotify(applicationContext),
    )

    private suspend fun checkLetters(uid: String): Result {
        if (!active(uid)) return Result.success()
        val firestore = FirebaseStoreProvider.get()
        val profile = withTimeout(25_000) {
            firestore.collection("users").document(uid).get(Source.SERVER).await()
        }
        if (!active(uid)) return Result.success()
        val spaceId = profile.getString("coupleSpaceId")?.takeIf { it.isNotBlank() } ?: return Result.success()
        val collection = firestore.collection("couple_spaces").document(spaceId).collection("envelopes")
        val stored = checkpoints.read(uid, spaceId)
        if (!stored.initialized) {
            // Recovery after cleared/corrupt preferences suppresses old inbox history.
            val baseline = withTimeout(25_000) {
                collection.whereEqualTo("receiverId", uid)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
                    .limit(1).get(Source.SERVER).await()
            }
            if (!active(uid)) return Result.success()
            checkpoints.save(uid, spaceId, LetterPollingPolicy.baseline(baseline.documents.mapNotNull { it.toPollingItem() }))
            return Result.success()
        }
        var cursor = stored.cursor
        var newLetters = 0
        var moreRemaining = false
        repeat(MAX_PAGES) { page ->
            if (!active(uid)) return Result.success()
            var query = collection.whereEqualTo("receiverId", uid)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.ASCENDING)
            cursor?.let { query = query.startAfter(Timestamp(it.seconds, it.nanoseconds), it.documentId) }
            val snapshot = withTimeout(25_000) { query.limit(PAGE_SIZE).get(Source.SERVER).await() }
            if (!active(uid)) return Result.success()
            val advance = LetterPollingPolicy.advance(cursor, uid, snapshot.documents.mapNotNull { it.toPollingItem() })
            cursor = advance.cursor
            newLetters += advance.newLetterCount
            moreRemaining = snapshot.size() == PAGE_SIZE.toInt()
            if (!moreRemaining || page == MAX_PAGES - 1) {
                if (!active(uid)) return Result.success()
                // A failed/disabled post leaves its checkpoint intact for the next eligible check.
                if (newLetters > 0 && !LetterNotifications.showNewLetters(
                        applicationContext, uid, spaceId, newLetters, checkNotNull(cursor).documentId,
                    )) {
                    return Result.success()
                }
                if (!active(uid)) {
                    LetterNotifications.cancelAll(applicationContext)
                    return Result.success()
                }
                checkpoints.save(uid, spaceId, LetterPollingState(initialized = true, cursor = cursor))
                return if (moreRemaining) retryBounded() else Result.success()
            }
        }
        return Result.success()
    }

    private fun retryBounded(): Result = if (runAttemptCount < 3) Result.retry() else Result.success()

    private companion object {
        const val PAGE_SIZE = 50L
        const val MAX_PAGES = 3
    }
}

internal fun DocumentSnapshot.toPollingItem(): LetterPollingItem? {
    val time = getTimestamp("createdAt") ?: return null
    val receiver = getString("receiverId") ?: return null
    return LetterPollingItem(LetterCursor(time.seconds, time.nanoseconds, id), receiver, getBoolean("isOpened") == true)
}
