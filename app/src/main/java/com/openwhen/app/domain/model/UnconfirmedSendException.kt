package com.openwhen.app.domain.model

/** A transport failure whose server transaction may already have committed. Retry the same draft ID. */
class UnconfirmedSendException(message: String, cause: Throwable) : Exception(message, cause)
