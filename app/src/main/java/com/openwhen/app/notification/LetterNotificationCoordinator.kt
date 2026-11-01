package com.openwhen.app.notification

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.openwhen.app.BuildConfig
import com.openwhen.app.notifications.LetterNotificationScheduler

/** Application-scoped account observer; workers always recheck the same account and consent. */
object LetterNotificationCoordinator {
    private var listener: FirebaseAuth.AuthStateListener? = null
    private var previousUid: String? = null

    fun start(context: Context) {
        if (!BuildConfig.SPARK_MODE || listener != null) return
        val application = context.applicationContext
        val preferences = LetterNotificationPreferences(application)
        previousUid = preferences.lastActiveUser
        LetterNotifications.ensureChannel(application)
        listener = FirebaseAuth.AuthStateListener { auth ->
            val uid = auth.currentUser?.uid
            if (uid != previousUid || uid == null) {
                previousUid?.let { LetterNotificationScheduler.cancelAccount(application, it) }
                LetterNotifications.cancelAll(application)
            }
            previousUid = uid
            preferences.lastActiveUser = uid
            refresh(application)
        }.also { FirebaseAuth.getInstance().addAuthStateListener(it) }
    }

    fun refresh(context: Context) {
        if (!BuildConfig.SPARK_MODE) return
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val enabled = uid != null && LetterNotificationPreferences(context).isEnabled(uid) &&
            LetterNotifications.canNotify(context)
        if (!enabled) LetterNotifications.cancelAll(context)
        LetterNotificationScheduler.reconcile(context.applicationContext, uid, enabled)
    }
}
