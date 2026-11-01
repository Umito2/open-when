package com.openwhen.app.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun AppUpdateHost(viewModel: AppUpdateViewModel, state: AppUpdateState, allowPrompt: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // A successful self-update replaces this process. Returning here keeps cancellation retryable.
        viewModel.installerReturned()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ApkUpdateInstaller.canInstall(context)) viewModel.requestInstallation()
        else viewModel.installationFailed(permission = true)
    }
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.checkForUpdates()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.installations.collect { file ->
                try {
                    if (ApkUpdateInstaller.canInstall(context)) {
                        installer.launch(ApkUpdateInstaller.installIntent(context, file))
                        viewModel.installerLaunched()
                    } else viewModel.installationFailed(permission = true)
                } catch (_: Exception) {
                    viewModel.installationFailed()
                }
            }
        }
    }
    if (allowPrompt) AppUpdateDialog(
        state = state,
        onDownload = viewModel::download,
        onInstall = {
            try {
                if (ApkUpdateInstaller.canInstall(context)) viewModel.requestInstallation()
                else permission.launch(ApkUpdateInstaller.permissionIntent(context))
            } catch (_: Exception) {
                viewModel.installationFailed()
            }
        },
        onCheck = { viewModel.checkForUpdates(manual = true) },
        onDismiss = viewModel::dismiss,
        onCancelDownload = viewModel::cancelDownload,
    )
}
