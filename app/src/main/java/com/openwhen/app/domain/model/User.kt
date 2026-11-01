package com.openwhen.app.domain.model

data class User(
    val uid: String = "",
    val name: String = "",
    val partnerId: String? = null,
    val coupleSpaceId: String? = null,
    val fcmToken: String? = null,
    val currentMood: String = "",
)
