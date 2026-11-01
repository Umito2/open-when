package com.openwhen.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.openwhen.app.notification.LetterNotificationCoordinator
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenWhenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEMO_MODE && FirebaseApp.initializeApp(this) != null) {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(appCheckProvider())
            LetterNotificationCoordinator.start(this)
        }
    }
}
