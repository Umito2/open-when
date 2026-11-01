package com.openwhen.app.notifications

/** Keeps Firestore's nanosecond precision and document-name tie breaker intact. */
data class LetterCursor(val seconds: Long, val nanoseconds: Int, val documentId: String) : Comparable<LetterCursor> {
    init {
        require(nanoseconds in 0..999_999_999)
        require(documentId.isNotBlank())
    }

    override fun compareTo(other: LetterCursor): Int {
        seconds.compareTo(other.seconds).takeIf { it != 0 }?.let { return it }
        nanoseconds.compareTo(other.nanoseconds).takeIf { it != 0 }?.let { return it }
        // Firestore orders strings by their UTF-8 bytes, rather than UTF-16 code units.
        val left = documentId.toByteArray(Charsets.UTF_8)
        val right = other.documentId.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(left.size, right.size)) {
            ((left[index].toInt() and 255) - (right[index].toInt() and 255))
                .takeIf { it != 0 }?.let { return it }
        }
        return left.size.compareTo(right.size)
    }
}

data class LetterPollingState(val initialized: Boolean = false, val cursor: LetterCursor? = null)
data class LetterPollingItem(val cursor: LetterCursor, val receiverId: String, val isOpened: Boolean)
data class LetterPageResult(val cursor: LetterCursor?, val newLetterCount: Int)

object LetterPollingPolicy {
    fun sessionAllows(expectedUid: String, actualUid: String?, enabled: Boolean, permission: Boolean): Boolean =
        expectedUid.isNotBlank() && expectedUid == actualUid && enabled && permission

    fun baseline(items: List<LetterPollingItem>): LetterPollingState =
        LetterPollingState(initialized = true, cursor = items.maxOfOrNull { it.cursor })

    fun advance(cursor: LetterCursor?, uid: String, items: List<LetterPollingItem>): LetterPageResult {
        val newer = items.distinctBy { it.cursor }.filter { cursor == null || it.cursor > cursor }
        return LetterPageResult(
            cursor = newer.maxOfOrNull { it.cursor } ?: cursor,
            newLetterCount = newer.count { it.receiverId == uid && !it.isOpened },
        )
    }
}
