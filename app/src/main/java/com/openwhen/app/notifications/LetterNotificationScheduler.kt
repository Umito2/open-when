package com.openwhen.app.notifications

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.openwhen.app.BuildConfig
import com.openwhen.app.data.FirebaseStoreProvider
import com.openwhen.app.notification.LetterNotifications
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout

object LetterNotificationScheduler {
    internal const val WORK_TAG = "open_when_letter_checks"
    internal const val UID_INPUT = "recipient_uid"
    internal val operationMutex = Mutex()

    /** Called while the preference is still off, so failed/offline setup never silently opts in. */
    suspend fun prepareBaseline(context: Context, uid: String): Boolean = try {
        withTimeout(55_000) {
            operationMutex.withLock {
                if (!BuildConfig.SPARK_MODE || FirebaseAuth.getInstance().currentUser?.uid != uid ||
                    !LetterNotifications.canNotify(context)) return@withLock false
                val firestore = FirebaseStoreProvider.get()
                val profile = withTimeout(25_000) {
                    firestore.collection("users").document(uid).get(Source.SERVER).await()
                }
                if (FirebaseAuth.getInstance().currentUser?.uid != uid || !LetterNotifications.canNotify(context)) return@withLock false
                val spaceId = profile.getString("coupleSpaceId")?.takeIf { it.isNotBlank() } ?: return@withLock false
                val snapshot = withTimeout(25_000) {
                    firestore.collection("couple_spaces").document(spaceId).collection("envelopes")
                        .whereEqualTo("receiverId", uid)
                        .orderBy("createdAt", Query.Direction.DESCENDING)
                        .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
                        .limit(1).get(Source.SERVER).await()
                }
                if (FirebaseAuth.getInstance().currentUser?.uid != uid || !LetterNotifications.canNotify(context)) return@withLock false
                val state = LetterPollingPolicy.baseline(snapshot.documents.mapNotNull { it.toPollingItem() })
                withContext(Dispatchers.IO) { LetterCursorStore(context).save(uid, spaceId, state) }
                FirebaseAuth.getInstance().currentUser?.uid == uid && LetterNotifications.canNotify(context)
            }
        }
    } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
        false
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        false
    }

    fun reconcile(context: Context, uid: String?, enabled: Boolean) {
        if (!BuildConfig.SPARK_MODE || uid.isNullOrBlank() || !enabled ||
            FirebaseAuth.getInstance().currentUser?.uid != uid || !LetterNotifications.canNotify(context)) {
            cancel(context)
            return
        }
        val manager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val input = workDataOf(UID_INPUT to uid)
        val periodic = PeriodicWorkRequestBuilder<LetterPollingWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(constraints).setInputData(input).addTag(WORK_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork(periodicName(uid), ExistingPeriodicWorkPolicy.KEEP, periodic)
        val checkNow = OneTimeWorkRequestBuilder<LetterPollingWorker>()
            .setConstraints(constraints).setInputData(input).addTag(WORK_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork(immediateName(uid), ExistingWorkPolicy.KEEP, checkNow)
    }

    fun cancelAccount(context: Context, uid: String) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(periodicName(uid))
        manager.cancelUniqueWork(immediateName(uid))
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(WORK_TAG)
    }

    private fun periodicName(uid: String) = "$WORK_TAG:periodic:$uid"
    private fun immediateName(uid: String) = "$WORK_TAG:now:$uid"
}
