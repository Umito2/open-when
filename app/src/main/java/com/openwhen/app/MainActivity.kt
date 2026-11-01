package com.openwhen.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.openwhen.app.presentation.OpenWhenApp
import com.openwhen.app.presentation.theme.OpenWhenTheme
import com.google.firebase.auth.FirebaseAuth
import com.openwhen.app.notification.LetterNotificationCoordinator
import com.openwhen.app.notification.LetterNotifications
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var inboxRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeNotificationIntent(intent)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            OpenWhenTheme(darkTheme = dark) { OpenWhenApp(inboxRequest = inboxRequest) }
        }
    }

    override fun onResume() {
        super.onResume()
        LetterNotificationCoordinator.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
    }

    private fun consumeNotificationIntent(intent: Intent?) {
        if (!BuildConfig.SPARK_MODE || intent?.action != LetterNotifications.ACTION_OPEN_INBOX) return
        val recipient = intent.getStringExtra(LetterNotifications.EXTRA_RECIPIENT_UID)
        if (recipient != null && recipient == FirebaseAuth.getInstance().currentUser?.uid) inboxRequest++
        // Consume the action so rotation/restoration cannot navigate away from a new draft.
        intent.action = null
        intent.removeExtra(LetterNotifications.EXTRA_RECIPIENT_UID)
    }
}
