package com.openwhen.app.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.openwhen.app.BuildConfig
import com.openwhen.app.MainActivity
import com.openwhen.app.R

/** Notifications contain no letter text, title, partner name or media. */
object LetterNotifications {
    const val ACTION_OPEN_INBOX = "com.openwhen.app.OPEN_INBOX"
    const val EXTRA_RECIPIENT_UID = "notification_recipient_uid"
    const val CHANNEL_ID = "new_love_letters"

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, context.getString(R.string.letter_alert_channel), NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.letter_alert_channel_description)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission") // canNotify checks permission; notify also handles a concurrent revocation.
    fun showNewLetters(context: Context, recipientUid: String, spaceId: String, count: Int, deliveryId: String): Boolean {
        if (!BuildConfig.SPARK_MODE || count <= 0 || recipientUid.isBlank() || spaceId.isBlank() || deliveryId.isBlank()) return false
        if (FirebaseAuth.getInstance().currentUser?.uid != recipientUid) return false
        if (!LetterNotificationPreferences(context).isEnabled(recipientUid)) return false
        ensureChannel(context)
        if (!canNotify(context)) return false

        val accountPrefix = "letters:$recipientUid:$spaceId:"
        val tag = "$accountPrefix$deliveryId"
        val inbox = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_INBOX
            data = Uri.Builder().scheme(context.packageName).authority("inbox")
                .appendPath(recipientUid).appendPath(spaceId).build()
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_RECIPIENT_UID, recipientUid)
        }
        val tap = PendingIntent.getActivity(
            context, tag.hashCode(), inbox, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_letter_notification)
            .setContentTitle(context.getString(R.string.letter_alert_title))
            .setContentText(context.getString(R.string.letter_alert_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.letter_alert_body)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_letter_notification)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.letter_alert_title))
                    .build(),
            )
            .build()
        // Recheck immediately before posting: a background read may finish after sign-out.
        if (FirebaseAuth.getInstance().currentUser?.uid != recipientUid ||
            !LetterNotificationPreferences(context).isEnabled(recipientUid)
        ) return false
        return try {
            context.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.tag?.startsWith(accountPrefix) == true && it.tag != tag }
                .forEach { NotificationManagerCompat.from(context).cancel(it.tag, it.id) }
            NotificationManagerCompat.from(context).notify(tag, 1, notification)
            if (FirebaseAuth.getInstance().currentUser?.uid != recipientUid ||
                !LetterNotificationPreferences(context).isEnabled(recipientUid)
            ) {
                NotificationManagerCompat.from(context).cancel(tag, 1)
                false
            } else true
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and the system call.
            false
        }
    }

    fun cancelAll(context: Context) = NotificationManagerCompat.from(context).cancelAll()
}
