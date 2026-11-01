package com.openwhen.app.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.openwhen.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class GitHubUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateRepository {
    private val directory get() = File(context.cacheDir, "updates")
    private val downloadMutex = Mutex()

    override suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        withTimeout(60_000) {
            val connection = connection(ReleasePolicy.LATEST_URL).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            try {
                currentCoroutineContext().ensureActive()
                if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withTimeout null
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Update check is temporarily unavailable")
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_JSON_BYTES) throw IOException("Release information is too large")
                        output.write(buffer, 0, count)
                    }
                }
                currentCoroutineContext().ensureActive()
                try {
                    ReleasePolicy.findUpdate(output.toString(Charsets.UTF_8.name()), BuildConfig.VERSION_NAME)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    throw IOException("Release information could not be verified")
                }
            } finally { connection.disconnect() }
        }
    }

    override suspend fun download(release: ReleaseInfo, onProgress: (Int) -> Unit): File = downloadMutex.withLock {
        withContext(Dispatchers.IO) { downloadVerified(release, onProgress) }
    }

    private suspend fun downloadVerified(release: ReleaseInfo, onProgress: (Int) -> Unit): File {
        // Hold the mutex until canceled network IO has unwound and removed its partial file.
        // An immediate retry must never share that file with an older download's cleanup.
        return withTimeout(10 * 60_000L) {
            requireTrusted(release)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot prepare update storage")
            val target = apkFile(release)
            if (target.isFile) {
                try {
                    verify(target, release)
                    onProgress(100)
                    return@withTimeout target
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (!target.delete()) throw IOException("Cannot replace the previous download")
                }
            }
            val partial = File(directory, "${target.name}.part")
            if (partial.exists() && !partial.delete()) throw IOException("Cannot restart update download")
            onProgress(0)
            try {
                val connection = openAsset(release.downloadUrl)
                try {
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength >= 0 && declaredLength != release.sizeBytes) throw IOException("Update size does not match the release")
                    var received = 0L
                    var progress = -1
                    connection.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > release.sizeBytes || received > ReleasePolicy.MAX_APK_BYTES) {
                                    throw IOException("Update download exceeded its expected size")
                                }
                                output.write(buffer, 0, count)
                                val nextProgress = ((received * 99) / release.sizeBytes).toInt()
                                if (nextProgress != progress) {
                                    progress = nextProgress
                                    onProgress(progress)
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    if (received != release.sizeBytes) throw IOException("Update download was incomplete")
                } finally { connection.disconnect() }
                verifyContents(partial, release)
                currentCoroutineContext().ensureActive()
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                onProgress(100)
                target
            } finally {
                // A canceled/failed download can never become an installable file.
                partial.delete()
            }
        }
    }

    override suspend fun verify(file: File, release: ReleaseInfo) = withContext(Dispatchers.IO) {
        requireTrusted(release)
        if (file.canonicalFile != apkFile(release).canonicalFile || file.canonicalFile.parentFile != directory.canonicalFile) {
            throw IOException("Update file is outside private update storage")
        }
        verifyContents(file, release)
    }

    override fun cachedFile(release: ReleaseInfo): File? {
        if (!ReleasePolicy.isTrusted(release)) return null
        return apkFile(release).takeIf { it.isFile && it.length() == release.sizeBytes }
    }

    private suspend fun verifyContents(file: File, release: ReleaseInfo) {
        if (!file.isFile || file.length() != release.sizeBytes) throw IOException("Update download was incomplete")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (!digest.digest().hex().equals(release.sha256, ignoreCase = true)) throw IOException("Update checksum does not match the release")
        currentCoroutineContext().ensureActive()
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("The update is not a valid Android package")
        @Suppress("DEPRECATION")
        val installed = manager.getPackageInfo(context.packageName, flags)
        val application = archive.applicationInfo ?: throw IOException("The update has no application metadata")
        if (archive.packageName != context.packageName || archive.versionName != release.versionName ||
            versionCode(archive) <= versionCode(installed) || application.minSdkVersion > Build.VERSION.SDK_INT ||
            application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            throw IOException("This update is not compatible with the installed app")
        }
        val existingSigners = certificates(installed)
        val updateSigners = certificates(archive)
        if (existingSigners.isEmpty() || updateSigners != existingSigners) throw IOException("The update signing certificate does not match this app")
        currentCoroutineContext().ensureActive()
    }

    private fun requireTrusted(release: ReleaseInfo) {
        if (!ReleasePolicy.isTrusted(release) || !ReleasePolicy.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
            throw IOException("This release is not a newer official update")
        }
    }

    private fun apkFile(release: ReleaseInfo) = File(directory, "OpenWhen-${release.versionName}.apk")

    private suspend fun openAsset(initialUrl: String): HttpsURLConnection {
        var next = initialUrl
        repeat(6) { attempt ->
            currentCoroutineContext().ensureActive()
            if (!ReleasePolicy.isAllowedAssetUrl(next)) throw IOException("Unexpected update download server")
            val opened = connection(next)
            var handingOff = false
            try {
                val code = opened.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    handingOff = true
                    return opened
                }
                if (code !in setOf(301, 302, 303, 307, 308) || attempt == 5) throw IOException("Update download is temporarily unavailable")
                val location = opened.getHeaderField("Location") ?: throw IOException("Missing update download location")
                next = URI(next).resolve(location).toString()
            } finally { if (!handingOff) opened.disconnect() }
        }
        throw IOException("Too many update download redirects")
    }

    private fun connection(url: String): HttpsURLConnection = (URI(url).toURL().openConnection() as HttpsURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = false
        useCaches = false
        setRequestProperty("User-Agent", "OpenWhen/${BuildConfig.VERSION_NAME} Android")
        setRequestProperty("Accept-Encoding", "identity")
    }

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    private fun certificates(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signers.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object { const val MAX_JSON_BYTES = 512 * 1024 }
}
