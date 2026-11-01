package com.openwhen.app.update

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openwhen.app.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR, UP_TO_DATE }
enum class UpdateError { CHECK, DOWNLOAD, VERIFY, INSTALL, PERMISSION }

data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: ReleaseInfo? = null,
    val progress: Int = 0,
    val dialogVisible: Boolean = false,
    val error: UpdateError? = null,
)

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val restoredRelease = restoreRelease()
    private val mutableState = MutableStateFlow(
        AppUpdateState(
            phase = if (restoredRelease == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
            release = restoredRelease,
            dialogVisible = restoredRelease != null,
        ),
    )
    val state = mutableState.asStateFlow()
    private val installRequests = Channel<File>(Channel.BUFFERED)
    val installations = installRequests.receiveAsFlow()
    private var operation: Job? = null
    private var lastCheckNanos: Long? = null
    private var dismissedVersion: String? = null

    /** Reopening a settings/installer activity must not trigger another network check or prompt. */
    fun checkForUpdates(manual: Boolean = false) {
        if (operation?.isActive == true) return
        if (mutableState.value.release != null) {
            if (manual) showDialog()
            return
        }
        val now = System.nanoTime()
        if (!manual && lastCheckNanos?.let { now - it < CHECK_INTERVAL_NANOS } == true) return
        lastCheckNanos = now
        operation = viewModelScope.launch {
            mutableState.update { it.copy(phase = UpdatePhase.CHECKING, dialogVisible = manual, error = null) }
            try {
                val release = repository.latest()
                rememberRelease(release)
                mutableState.value = if (release == null) {
                    AppUpdateState(phase = UpdatePhase.UP_TO_DATE, dialogVisible = manual && mutableState.value.dialogVisible)
                } else {
                    AppUpdateState(
                        phase = UpdatePhase.AVAILABLE, release = release,
                        dialogVisible = if (manual) mutableState.value.dialogVisible else dismissedVersion != release.versionName,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.ERROR, dialogVisible = manual && it.dialogVisible, error = UpdateError.CHECK) }
            }
        }
    }

    fun showDialog() { mutableState.update { it.copy(dialogVisible = true) } }

    fun dismiss() {
        dismissedVersion = mutableState.value.release?.versionName
        mutableState.update { it.copy(dialogVisible = false) }
    }

    fun download() {
        val release = mutableState.value.release ?: return
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0, error = null, dialogVisible = true) }
            try {
                val cached = repository.cachedFile(release)
                val cachedValid = if (cached != null) {
                    try {
                        repository.verify(cached, release)
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { false }
                } else false
                if (!cachedValid) repository.download(release) { progress ->
                    mutableState.update {
                        if (it.phase == UpdatePhase.DOWNLOADING && it.release == release) it.copy(progress = progress.coerceIn(0, 100)) else it
                    }
                }
                mutableState.update { it.copy(phase = UpdatePhase.READY, progress = 100, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.ERROR, error = UpdateError.DOWNLOAD) }
            }
        }
    }

    fun cancelDownload() {
        operation?.cancel()
        mutableState.update { it.copy(phase = UpdatePhase.AVAILABLE, progress = 0, dialogVisible = false, error = null) }
    }

    /** Every installer handoff rechecks the private APK, including after settings/process recreation. */
    fun requestInstallation() {
        val release = mutableState.value.release ?: return
        if (operation?.isActive == true) return
        operation = viewModelScope.launch {
            mutableState.update { it.copy(phase = UpdatePhase.VERIFYING, error = null, dialogVisible = true) }
            try {
                val file = repository.cachedFile(release) ?: error("Update download was removed")
                repository.verify(file, release)
                mutableState.update { it.copy(phase = UpdatePhase.READY, error = null) }
                installRequests.send(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.ERROR, error = UpdateError.VERIFY) }
            }
        }
    }

    fun installerLaunched() { mutableState.update { it.copy(dialogVisible = false) } }
    fun installerReturned() { mutableState.update { it.copy(dialogVisible = true) } }
    fun installationFailed(permission: Boolean = false) {
        mutableState.update {
            it.copy(phase = UpdatePhase.ERROR, error = if (permission) UpdateError.PERMISSION else UpdateError.INSTALL, dialogVisible = true)
        }
    }

    private fun rememberRelease(release: ReleaseInfo?) {
        savedState["update_version"] = release?.versionName
        savedState["update_download"] = release?.downloadUrl
        savedState["update_sha256"] = release?.sha256
        savedState["update_size"] = release?.sizeBytes
        savedState["update_page"] = release?.releaseUrl
    }

    private fun restoreRelease(): ReleaseInfo? = runCatching {
        ReleaseInfo(
            savedState.get<String>("update_version") ?: return null,
            savedState.get<String>("update_download") ?: return null,
            savedState.get<String>("update_sha256") ?: return null,
            savedState.get<Long>("update_size") ?: return null,
            savedState.get<String>("update_page") ?: return null,
        )
    }.getOrNull()?.takeIf { ReleasePolicy.isTrusted(it) && ReleasePolicy.isNewer(it.versionName, BuildConfig.VERSION_NAME) }

    private companion object { const val CHECK_INTERVAL_NANOS = 15L * 60 * 1_000_000_000 }
}
