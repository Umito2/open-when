package com.openwhen.app.domain.model

data class CoupleSpace(
    val spaceId: String = "",
    val inviteCode: String = "",
    val user1Id: String = "",
    val user2Id: String? = null,
    val anniversaryDate: Long = 0,
    val createdAt: Long = 0,
)
