package com.openwhen.app.update

import java.io.File

/** Public release metadata only; no GitHub token or account is needed. */
data class ReleaseInfo(
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseUrl: String,
)

interface UpdateRepository {
    suspend fun latest(): ReleaseInfo?
    suspend fun download(release: ReleaseInfo, onProgress: (Int) -> Unit): File
    suspend fun verify(file: File, release: ReleaseInfo)
    fun cachedFile(release: ReleaseInfo): File?
}
