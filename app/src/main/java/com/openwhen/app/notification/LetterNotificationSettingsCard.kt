package com.openwhen.app.notification

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.google.firebase.auth.FirebaseAuth
import com.openwhen.app.R
import com.openwhen.app.notifications.LetterNotificationScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LetterNotificationSettingsCard(uid: String) {
    val context = LocalContext.current
    val application = context.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preferences = remember(application) { LetterNotificationPreferences(application) }
    val scope = rememberCoroutineScope()
    var enabled by remember(uid) { mutableStateOf(preferences.isEnabled(uid)) }
    var allowed by remember { mutableStateOf(LetterNotifications.canNotify(application)) }
    var requested by remember { mutableStateOf(preferences.wasPermissionRequested()) }
    var busy by remember(uid) { mutableStateOf(false) }
    var connectionError by remember(uid) { mutableStateOf(false) }
    var pendingSettingsEnable by rememberSaveable(uid) { mutableStateOf(false) }
    var permissionRecipient by rememberSaveable { mutableStateOf<String?>(null) }
    val label = stringResource(R.string.letter_alert_settings_title)

    val enableAlerts: () -> Unit = {
        if (!busy && FirebaseAuth.getInstance().currentUser?.uid == uid) {
            busy = true
            connectionError = false
            scope.launch {
                try {
                    val ready = LetterNotificationScheduler.prepareBaseline(application, uid)
                    if (FirebaseAuth.getInstance().currentUser?.uid == uid) {
                        allowed = LetterNotifications.canNotify(application)
                        if (ready && allowed) {
                            preferences.setEnabled(uid, true)
                            enabled = true
                            LetterNotificationCoordinator.refresh(application)
                        } else if (!ready) connectionError = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    connectionError = true
                } finally {
                    busy = false
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted && LetterNotifications.canNotify(application)
        if (permissionRecipient == uid && FirebaseAuth.getInstance().currentUser?.uid == uid && allowed) enableAlerts()
        permissionRecipient = null
    }

    val openSettings: () -> Unit = {
        pendingSettingsEnable = !enabled
        val settings = if (!NotificationManagerCompat.from(context).areNotificationsEnabled())
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, LetterNotifications.CHANNEL_ID)
        context.startActivity(settings)
    }

    DisposableEffect(lifecycle, uid) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = LetterNotifications.canNotify(application)
                enabled = preferences.isEnabled(uid)
                LetterNotificationCoordinator.refresh(application)
                if (pendingSettingsEnable && allowed) {
                    pendingSettingsEnable = false
                    enableAlerts()
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (enabled && allowed) R.string.letter_alert_enabled else R.string.letter_alert_disabled),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Switch(
                    checked = enabled,
                    enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = label },
                    onCheckedChange = { checked ->
                        connectionError = false
                        if (!checked) {
                            pendingSettingsEnable = false
                            preferences.setEnabled(uid, false)
                            enabled = false
                            LetterNotificationCoordinator.refresh(application)
                        } else if (LetterNotifications.canNotify(application)) {
                            enableAlerts()
                        } else if (Build.VERSION.SDK_INT >= 33 && !preferences.wasPermissionRequested() &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            preferences.markPermissionRequested()
                            requested = true
                            permissionRecipient = uid
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else openSettings()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.letter_alert_frequency), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!allowed && (requested || enabled)) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.letter_alert_blocked), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = openSettings) { Text(stringResource(R.string.letter_alert_open_settings)) }
            }
            if (connectionError) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.letter_alert_connection_error), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
