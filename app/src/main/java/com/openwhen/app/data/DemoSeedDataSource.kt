package com.openwhen.app.data

import com.openwhen.app.domain.model.CoupleSpace
import com.openwhen.app.domain.model.Envelope
import com.openwhen.app.domain.model.UnlockRule
import com.openwhen.app.domain.model.User
import java.time.LocalDate
import java.time.ZoneId

object DemoSeedDataSource {
    const val SELF_ID = "demo-noor"
    const val PARTNER_ID = "demo-adam"
    val user = User(SELF_ID, "Noor", PARTNER_ID, "demo-space", currentMood = "Feeling loved")
    val partner = User(PARTNER_ID, "Adam", SELF_ID, "demo-space", currentMood = "Thinking of you")

    fun space(now: Long = System.currentTimeMillis()) = CoupleSpace(
        "demo-space", "143143", SELF_ID, PARTNER_ID,
        LocalDate.now().minusDays(365).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), now,
    )

    fun envelopes(now: Long = System.currentTimeMillis(), resourceRoot: String = "android.resource://com.openwhen.app.demo.debug"): List<Envelope> = listOf(
        Envelope(
            envelopeId = "demo-miss", senderId = PARTNER_ID, receiverId = SELF_ID,
            titlePrompt = "Open when you miss me", envelopeColorHex = "#F0C8C3", sealIcon = "Heart",
            letterText = "My love,\n\nIf I could fold the distance between us into something small, I would tuck it inside this envelope. Until then, put your hand over your heart. Mine is keeping the same gentle rhythm, somewhere under the same sky.\n\nI miss the ordinary things most: your sleepy hello, your hand finding mine, the way every room feels warmer when you walk in.\n\nWe will have those little moments again. For now, take one slow breath with me. You are loved in every version of this day.\n\nAlways,\nAdam",
            photoUrl = "$resourceRoot/drawable/demo_memory", photoCaption = "Our favorite place is together.",
            voiceNoteUrl = "$resourceRoot/raw/demo_voice", voiceDurationSec = 9,
            songUrl = "https://open.spotify.com/search/our%20song", createdAt = now - 6 * 86_400_000L,
        ),
        Envelope(
            envelopeId = "demo-stress", senderId = PARTNER_ID, receiverId = SELF_ID,
            titlePrompt = "Open when the world feels heavy", envelopeColorHex = "#D7DFD2", sealIcon = "Hug",
            letterText = "Hey, brave heart.\n\nYou do not have to solve the whole day right now. Unclench your jaw. Let your shoulders fall. Drink a little water.\n\nI see how much you carry, including the things nobody thinks to thank you for. Your worth has never depended on how much you finish. There is room to rest here, with me.\n\nChoose just the next small thing. I will be cheering for you through it, and through the quiet afterward.\n\nOne enormous hug,\nAdam",
            createdAt = now - 5 * 86_400_000L,
        ),
        Envelope(
            envelopeId = "demo-sleep", senderId = PARTNER_ID, receiverId = SELF_ID,
            titlePrompt = "Open before you sleep", envelopeColorHex = "#D8D1E8", sealIcon = "Moon",
            letterText = "My favorite person,\n\nLet the unfinished things wait outside tonight. You have done enough for one day. Imagine us on the sofa, a blanket over our feet, no clocks and nowhere else to be.\n\nIf your thoughts get noisy, count the small things we are still going to do: Sunday breakfasts, spontaneous walks, terrible movies we love anyway. There is so much ordinary happiness waiting for us.\n\nSleep softly. I am sending a kiss to your forehead.\n\nSee you in the morning,\nAdam",
            unlockRule = UnlockRule.ONE_TIME_ONLY, createdAt = now - 4 * 86_400_000L,
        ),
        Envelope(
            envelopeId = "demo-arabic", senderId = PARTNER_ID, receiverId = SELF_ID,
            titlePrompt = "افتحيها لما تزعلي مني", envelopeColorHex = "#F1DDBA", sealIcon = "Coffee",
            letterText = "يا حبيبتي،\n\nحتى وإحنا مختلفين، إنتِ لسه أقرب حد لقلبي. مش لازم نعرف نحل كل حاجة في نفس اللحظة، بس نقدر نفضل حنينين على بعض.\n\nأنا عايز أسمعك بجد، من غير ما أقاطعك أو أدافع عن نفسي. إحساسك يهمني، ووجودك في حياتي أغلى من إني أكسب أي نقاش.\n\nخدي وقتك، ولما تكوني مستعدة نعمل قهوتين ونبدأ من جديد. أنا وإنتِ في نفس الفريق، دايمًا.\n\nبحبك،\nآدم",
            photoUrl = "$resourceRoot/drawable/demo_memory", photoCaption = "معاكي، كل مكان يبقى بيت.", createdAt = now - 3 * 86_400_000L,
        ),
        Envelope(
            envelopeId = "demo-date", senderId = PARTNER_ID, receiverId = SELF_ID,
            titlePrompt = "Open on our next little milestone", envelopeColorHex = "#EBC7D6", sealIcon = "Star",
            letterText = "To us,\n\nAnother little milestone, and I would still choose you at the beginning of every road. Thank you for all the kindness you put into this life we are making.\n\nHere is to more laughter in the kitchen, more honest conversations, and more days that end with us.\n\nHappy us-day, my love.",
            unlockRule = UnlockRule.DATE_LOCKED, unlockTimestamp = now + 2 * 86_400_000L,
            createdAt = now - 2 * 86_400_000L,
        ),
        Envelope(
            envelopeId = "demo-sent", senderId = SELF_ID, receiverId = PARTNER_ID,
            titlePrompt = "Open when you need a little courage", envelopeColorHex = "#CEDDE3", sealIcon = "Star",
            letterText = "Adam,\n\nI wish you could see yourself through my eyes for a minute. You would see someone thoughtful, funny, and quietly brave, even on the days you feel uncertain.\n\nYou do not need to become someone else to deserve good things. Take your time. Try the thing. Let yourself begin again.\n\nI believe in you, and I am so glad I get to love you.\n\nYours,\nNoor",
            isOpened = true, lastOpenedAt = now - 3_600_000, openCount = 1,
            partnerReaction = "Made me smile ❤️", createdAt = now - 86_400_000L,
        ),
    )
}
