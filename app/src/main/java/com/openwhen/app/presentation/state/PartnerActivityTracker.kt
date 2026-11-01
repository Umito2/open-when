package com.openwhen.app.presentation.state

import com.openwhen.app.domain.model.Envelope

/** Establishes a baseline per couple so an initial Firestore snapshot never becomes an alert. */
class PartnerActivityTracker {
    private var scopeId: String? = null
    private var baseline: Map<String, Envelope>? = null

    fun update(spaceId: String, userId: String, envelopes: List<Envelope>, partnerName: String): List<String> {
        if (scopeId != "$spaceId/$userId") {
            scopeId = "$spaceId/$userId"
            baseline = null
        }
        val current = envelopes.associateBy { it.envelopeId }
        val previous = baseline
        baseline = current
        if (previous == null) return emptyList()
        return buildList {
            envelopes.filter { it.senderId == userId }.forEach { envelope ->
                val old = previous[envelope.envelopeId] ?: return@forEach
                if (envelope.openCount > old.openCount) {
                    add("💌 $partnerName just opened your '${envelope.titlePrompt}' envelope!")
                }
                if (!envelope.partnerReaction.isNullOrBlank() && envelope.partnerReaction != old.partnerReaction) {
                    add("$partnerName sent you a little love: ${envelope.partnerReaction}")
                }
            }
        }
    }

    fun reset() {
        scopeId = null
        baseline = null
    }
}
