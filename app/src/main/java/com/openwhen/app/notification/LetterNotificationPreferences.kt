package com.openwhen.app.notification

import android.content.Context

/** Local, per-account consent. Enabling alerts on one phone does not enable another phone. */
class LetterNotificationPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "letter_notification_preferences", Context.MODE_PRIVATE,
    )

    fun isEnabled(uid: String): Boolean = preferences.getBoolean("enabled:$uid", false)

    fun setEnabled(uid: String, enabled: Boolean) {
        preferences.edit().putBoolean("enabled:$uid", enabled).apply()
    }

    fun wasPermissionRequested(): Boolean = preferences.getBoolean("permission_requested", false)

    fun markPermissionRequested() {
        preferences.edit().putBoolean("permission_requested", true).apply()
    }

    internal var lastActiveUser: String?
        get() = preferences.getString("last_active_user", null)
        set(value) { preferences.edit().putString("last_active_user", value).apply() }
}
