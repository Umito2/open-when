package com.openwhen.app.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.openwhen.app.BuildConfig
import com.openwhen.app.R

@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    if (!state.dialogVisible) return
    val downloading = state.phase == UpdatePhase.DOWNLOADING
    val verifying = state.phase == UpdatePhase.VERIFYING
    val close = if (downloading || verifying) onCancelDownload else onDismiss
    val title = when (state.phase) {
        UpdatePhase.CHECKING -> R.string.app_update_checking
        UpdatePhase.DOWNLOADING -> R.string.app_update_downloading
        UpdatePhase.VERIFYING -> R.string.app_update_verifying
        UpdatePhase.READY -> R.string.app_update_ready
        UpdatePhase.ERROR -> R.string.app_update_error_title
        UpdatePhase.UP_TO_DATE -> R.string.app_update_current_title
        else -> R.string.app_update_available_title
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.release?.let { release ->
                    Text(stringResource(R.string.app_update_versions, BuildConfig.VERSION_NAME, release.versionName))
                }
                when (state.phase) {
                    UpdatePhase.CHECKING -> {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.app_update_checking_body))
                    }
                    UpdatePhase.DOWNLOADING -> {
                        val progress = state.progress.coerceIn(0, 100)
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.app_update_progress, progress))
                        Text(stringResource(R.string.app_update_download_body))
                    }
                    UpdatePhase.VERIFYING -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.app_update_verifying_body))
                    }
                    UpdatePhase.READY -> Text(stringResource(R.string.app_update_ready_body))
                    UpdatePhase.ERROR -> Text(stringResource(when (state.error) {
                        UpdateError.DOWNLOAD -> R.string.app_update_download_error
                        UpdateError.VERIFY -> R.string.app_update_verify_error
                        UpdateError.INSTALL -> R.string.app_update_install_error
                        UpdateError.PERMISSION -> R.string.app_update_permission_error
                        else -> R.string.app_update_check_error
                    }))
                    UpdatePhase.UP_TO_DATE -> Text(stringResource(R.string.app_update_current_body, BuildConfig.VERSION_NAME))
                    else -> {
                        Text(stringResource(R.string.app_update_available_body))
                        state.release?.let { release ->
                            val size = Formatter.formatShortFileSize(LocalContext.current, release.sizeBytes)
                            Text(stringResource(R.string.app_update_download_size, size))
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.app_update_download_action))
                }
                UpdatePhase.READY -> TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.app_update_install_action))
                }
                UpdatePhase.ERROR -> {
                    val retry = when (state.error) {
                        UpdateError.INSTALL, UpdateError.PERMISSION -> onInstall
                        UpdateError.DOWNLOAD, UpdateError.VERIFY -> onDownload
                        else -> onCheck
                    }
                    TextButton(onClick = retry) { Text(stringResource(R.string.app_update_retry)) }
                }
                UpdatePhase.UP_TO_DATE -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.app_update_done))
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (state.phase != UpdatePhase.UP_TO_DATE) {
                TextButton(onClick = close) {
                    Text(stringResource(when {
                        downloading -> R.string.app_update_cancel_download
                        verifying -> R.string.app_update_cancel
                        else -> R.string.app_update_later
                    }))
                }
            }
        },
    )
}

@Composable
fun AppUpdateSettingsCard(state: AppUpdateState, onCheck: () -> Unit, onShow: () -> Unit) {
    val hasUpdate = state.release != null && state.phase in setOf(
        UpdatePhase.AVAILABLE, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.READY, UpdatePhase.ERROR,
    )
    val status = when (state.phase) {
        UpdatePhase.CHECKING -> stringResource(R.string.app_update_checking)
        UpdatePhase.DOWNLOADING -> stringResource(R.string.app_update_progress, state.progress.coerceIn(0, 100))
        UpdatePhase.VERIFYING -> stringResource(R.string.app_update_verifying)
        UpdatePhase.READY -> stringResource(R.string.app_update_ready)
        UpdatePhase.AVAILABLE -> stringResource(R.string.app_update_available_title)
        UpdatePhase.UP_TO_DATE -> stringResource(R.string.app_update_current_title)
        UpdatePhase.ERROR -> stringResource(R.string.app_update_error_title)
        else -> stringResource(R.string.app_update_settings_body)
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_update_settings_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.app_update_installed_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.phase == UpdatePhase.CHECKING) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(
                onClick = if (hasUpdate) onShow else onCheck,
                enabled = state.phase != UpdatePhase.CHECKING,
            ) {
                Text(stringResource(if (hasUpdate) R.string.app_update_view_action else R.string.app_update_check_action))
            }
        }
    }
}
