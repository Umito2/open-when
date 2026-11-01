package com.openwhen.app.update

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.openwhen.app.BuildConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var modelCount = 0
    private val release = release("9.0.0")
    private val apk = File("verified-update.apk")

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun `opening offers a newer release and later does not nag on resume`() = runTest(dispatcher) {
        val repository = FakeRepository(release)
        val vm = model(repository)
        vm.checkForUpdates()
        runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
        assertTrue(vm.state.value.dialogVisible)
        vm.dismiss()
        vm.checkForUpdates()
        runCurrent()
        assertEquals(1, repository.checks)
        assertFalse(vm.state.value.dialogVisible)
        vm.checkForUpdates(manual = true)
        assertTrue(vm.state.value.dialogVisible)
    }

    @Test fun `offline automatic checks stay quiet and manual retry can recover`() = runTest(dispatcher) {
        val repository = FakeRepository(release).apply { checkFails = true }
        val vm = model(repository)
        vm.checkForUpdates()
        runCurrent()
        assertEquals(UpdateError.CHECK, vm.state.value.error)
        assertFalse(vm.state.value.dialogVisible)
        repository.checkFails = false
        vm.checkForUpdates(manual = true)
        runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
        assertTrue(vm.state.value.dialogVisible)
    }

    @Test fun `dismissing an unfinished manual check keeps its result dismissed`() = runTest(dispatcher) {
        val repository = FakeRepository(release).apply { checkGate = CompletableDeferred() }
        val vm = model(repository)
        vm.checkForUpdates(manual = true)
        runCurrent()
        vm.dismiss()
        repository.checkGate!!.complete(Unit)
        runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
        assertFalse(vm.state.value.dialogVisible)
    }

    @Test fun `download never installs automatically and explicit install verifies again`() = runTest(dispatcher) {
        val repository = FakeRepository(release)
        val vm = model(repository)
        val installs = mutableListOf<File>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.installations.collect { installs += it } }
        vm.checkForUpdates()
        runCurrent()
        vm.download()
        runCurrent()
        assertEquals(UpdatePhase.READY, vm.state.value.phase)
        assertEquals(100, vm.state.value.progress)
        assertTrue(installs.isEmpty())
        vm.requestInstallation()
        runCurrent()
        assertEquals(listOf(apk), installs)
        assertEquals(1, repository.verifications)
    }

    @Test fun `canceled downloads cannot hand off an incomplete APK`() = runTest(dispatcher) {
        val repository = FakeRepository(release).apply { downloadGate = CompletableDeferred() }
        val vm = model(repository)
        vm.checkForUpdates()
        runCurrent()
        vm.download()
        runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, vm.state.value.phase)
        vm.cancelDownload()
        repository.downloadGate!!.complete(Unit)
        runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, vm.state.value.phase)
        assertFalse(vm.state.value.dialogVisible)
        assertNull(repository.cached)
    }

    @Test fun `changed cached APK is rejected before installer handoff`() = runTest(dispatcher) {
        val repository = FakeRepository(release).apply { cached = apk; verificationFails = true }
        val vm = model(repository)
        val installs = mutableListOf<File>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.installations.collect { installs += it } }
        vm.checkForUpdates()
        runCurrent()
        vm.requestInstallation()
        runCurrent()
        assertEquals(UpdateError.VERIFY, vm.state.value.error)
        assertTrue(installs.isEmpty())
    }

    @Test fun `install permission denial preserves the release for retry`() = runTest(dispatcher) {
        val repository = FakeRepository(release).apply { cached = apk }
        val vm = model(repository)
        vm.checkForUpdates()
        runCurrent()
        vm.installationFailed(permission = true)
        assertEquals(UpdateError.PERMISSION, vm.state.value.error)
        assertEquals(release, vm.state.value.release)
        vm.requestInstallation()
        runCurrent()
        assertEquals(UpdatePhase.READY, vm.state.value.phase)
    }

    @Test fun `saved release survives settings recreation but is discarded after upgrading`() = runTest(dispatcher) {
        val saved = saved(release)
        val repository = FakeRepository(release).apply { cached = apk }
        val vm = model(repository, saved)
        if (!BuildConfig.DEMO_MODE) {
            vm.requestInstallation()
            runCurrent()
            assertEquals(UpdatePhase.READY, vm.state.value.phase)
        } else assertNull(vm.state.value.release)
        val installedVersion = BuildConfig.VERSION_NAME.removeSuffix("-demo")
        val afterUpgrade = model(FakeRepository(null), saved(release(installedVersion)))
        assertNull(afterUpgrade.state.value.release)
        afterUpgrade.checkForUpdates(manual = true)
        runCurrent()
        assertEquals(UpdatePhase.UP_TO_DATE, afterUpgrade.state.value.phase)
    }

    private fun model(repository: UpdateRepository, saved: SavedStateHandle = SavedStateHandle()) =
        AppUpdateViewModel(repository, saved).also { store.put("vm-${modelCount++}", it) }

    private fun release(version: String) = ReleaseInfo(
        version, "https://github.com/Umito2/open-when/releases/download/v$version/OpenWhen-$version.apk",
        "a".repeat(64), 42, "https://github.com/Umito2/open-when/releases/tag/v$version",
    )

    private fun saved(release: ReleaseInfo) = SavedStateHandle(mapOf(
        "update_version" to release.versionName, "update_download" to release.downloadUrl,
        "update_sha256" to release.sha256, "update_size" to release.sizeBytes, "update_page" to release.releaseUrl,
    ))

    private inner class FakeRepository(private val result: ReleaseInfo?) : UpdateRepository {
        var checks = 0
        var verifications = 0
        var checkFails = false
        var verificationFails = false
        var checkGate: CompletableDeferred<Unit>? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        var cached: File? = null
        override suspend fun latest(): ReleaseInfo? {
            checks++
            checkGate?.await()
            if (checkFails) throw IOException("Offline")
            return result
        }
        override suspend fun download(release: ReleaseInfo, onProgress: (Int) -> Unit): File {
            onProgress(50)
            downloadGate?.await()
            cached = apk
            onProgress(100)
            return apk
        }
        override suspend fun verify(file: File, release: ReleaseInfo) {
            verifications++
            if (verificationFails) throw IOException("APK changed")
        }
        override fun cachedFile(release: ReleaseInfo) = cached
    }
}
