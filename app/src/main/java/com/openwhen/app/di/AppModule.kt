package com.openwhen.app.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.openwhen.app.BuildConfig
import com.openwhen.app.data.DemoEnvelopeRepository
import com.openwhen.app.data.FirebaseEnvelopeRepository
import com.openwhen.app.domain.repository.EnvelopeRepository
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun auth(): FirebaseAuth = FirebaseAuth.getInstance()
    @Provides @Singleton fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance()
    @Provides @Singleton fun storage(): FirebaseStorage = FirebaseStorage.getInstance()
    @Provides @Singleton fun functions(): FirebaseFunctions = FirebaseFunctions.getInstance("us-central1")

    @Provides @Singleton
    fun repository(demo: Lazy<DemoEnvelopeRepository>, firebase: Lazy<FirebaseEnvelopeRepository>): EnvelopeRepository =
        if (BuildConfig.DEMO_MODE) demo.get() else firebase.get()
}
