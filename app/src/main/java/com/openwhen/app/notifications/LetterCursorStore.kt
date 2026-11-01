package com.openwhen.app.notifications

import android.content.Context
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject

/** Only cursors are persisted; letter text, sender names and titles never enter this file. */
internal class LetterCursorStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("letter_notification_cursors", Context.MODE_PRIVATE)

    fun read(uid: String, spaceId: String): LetterPollingState {
        val encoded = preferences.getString(key(uid, spaceId), null) ?: return LetterPollingState()
        return runCatching {
            val data = JSONObject(encoded)
            val cursor = if (data.has("documentId")) LetterCursor(
                data.getLong("seconds"), data.getInt("nanoseconds"), data.getString("documentId"),
            ) else null
            LetterPollingState(data.getBoolean("initialized"), cursor)
        }.getOrDefault(LetterPollingState())
    }

    fun save(uid: String, spaceId: String, state: LetterPollingState) {
        val data = JSONObject().put("initialized", state.initialized)
        state.cursor?.let {
            data.put("seconds", it.seconds).put("nanoseconds", it.nanoseconds).put("documentId", it.documentId)
        }
        if (!preferences.edit().putString(key(uid, spaceId), data.toString()).commit()) {
            throw IOException("The notification checkpoint could not be saved.")
        }
    }

    private fun key(uid: String, spaceId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$uid\u0000$spaceId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
