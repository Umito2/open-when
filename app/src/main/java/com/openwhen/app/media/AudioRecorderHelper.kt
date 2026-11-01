package com.openwhen.app.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.math.ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecordingState(
    val isRecording: Boolean = false,
    val durationMs: Long = 0,
    val amplitude: Float = 0f,
    val recordingUri: String? = null,
    val error: String? = null,
)

data class RecordedVoice(val uri: String, val durationSec: Int)

/** Records into the application's private cache. The screen owns microphone permission. */
class AudioRecorderHelper @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = mutableState.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var meterJob: Job? = null

    fun startRecording() {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = mutableState.value.copy(error = "Allow microphone access to record your voice.")
            return
        }
        discard()
        val directory = File(context.cacheDir, "voice-notes").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.m4a")
        try {
            @Suppress("DEPRECATION")
            val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            recorder = next
            file = target
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(96_000)
            next.setAudioSamplingRate(44_100)
            next.setOutputFile(target.absolutePath)
            next.prepare()
            next.start()
            startedAt = SystemClock.elapsedRealtime()
            mutableState.value = RecordingState(isRecording = true)
            meterJob = scope.launch {
                while (true) {
                    if (SystemClock.elapsedRealtime() - startedAt >= 300_000L) {
                        stopRecording()
                        return@launch
                    }
                    val peak = runCatching { next.maxAmplitude }.getOrDefault(0)
                    val amplitude = (ln(1f + peak) / ln(32768f)).coerceIn(0f, 1f)
                    mutableState.value = mutableState.value.copy(
                        durationMs = SystemClock.elapsedRealtime() - startedAt,
                        amplitude = amplitude,
                    )
                    delay(90)
                }
            }
        } catch (failure: Exception) {
            releaseRecorder()
            target.delete()
            file = null
            mutableState.value = RecordingState(error = failure.localizedMessage ?: "Couldn't start recording. Try again.")
        }
    }

    fun stopRecording(): RecordedVoice? {
        val active = recorder ?: return null
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val target = file ?: return null
        return try {
            active.stop()
            releaseRecorder()
            if (elapsed < 500 || target.length() == 0L) {
                target.delete()
                file = null
                mutableState.value = RecordingState(error = "Hold for a little longer to record a voice note.")
                null
            } else {
                val result = RecordedVoice(target.toURI().toString(), ((elapsed + 999) / 1000).toInt().coerceAtMost(300))
                mutableState.value = RecordingState(durationMs = elapsed.coerceAtMost(300_000L), recordingUri = result.uri)
                result
            }
        } catch (_: RuntimeException) {
            releaseRecorder()
            target.delete()
            file = null
            mutableState.value = RecordingState(error = "The recording was interrupted. Please try again.")
            null
        }
    }

    fun discard() {
        if (recorder != null) runCatching { recorder?.stop() }
        releaseRecorder()
        file?.delete()
        file = null
        mutableState.value = RecordingState()
    }

    /** The repository has uploaded or copied this recording; remove the private staging file. */
    fun detachCompletedRecording() {
        if (recorder != null) return
        file?.delete()
        file = null
        mutableState.value = RecordingState()
    }

    private fun releaseRecorder() {
        meterJob?.cancel()
        meterJob = null
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    fun release() {
        discard()
        scope.cancel()
    }
}
