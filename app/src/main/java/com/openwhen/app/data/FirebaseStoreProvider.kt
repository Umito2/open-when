package com.openwhen.app.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.openwhen.app.BuildConfig

/** Configures Firestore once, whether a background worker or the UI starts it first. */
object FirebaseStoreProvider {
    private val instance: FirebaseFirestore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FirebaseFirestore.getInstance().also { firestore ->
            if (BuildConfig.SPARK_MODE) {
                firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                    .build()
            }
        }
    }

    fun get(): FirebaseFirestore = instance
}
