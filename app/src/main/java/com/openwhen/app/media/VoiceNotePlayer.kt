package com.openwhen.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
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

data class PlaybackState(
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val error: String? = null,
)

/** A screen-scoped asynchronous player. Call release when the screen leaves composition. */
class VoiceNotePlayer(private val context: Context? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var player: MediaPlayer? = null
    private var source: String? = null
    private var progressJob: Job? = null
    private var playWhenReady = false
    private var focusRequest: AudioFocusRequest? = null
    private val audioManager = context?.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(AudioAttributes.USAGE_MEDIA).build()

    fun toggle(url: String) {
        if (url.isBlank()) return
        if (source != url || player == null) {
            prepare(url)
            return
        }
        if (mutableState.value.isPreparing) return
        val current = player ?: return
        runCatching {
            if (current.isPlaying) pause() else {
                if (!acquireFocus()) return
                playWhenReady = true
                current.start()
                mutableState.value = mutableState.value.copy(isPlaying = true, error = null)
                trackProgress(current)
            }
        }.onFailure { fail("This voice note couldn't be played. Tap to try again.") }
    }

    private fun prepare(url: String) {
        reset()
        source = url
        playWhenReady = true
        mutableState.value = PlaybackState(isPreparing = true)
        try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(attributes)
            next.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                runCatching {
                    val shouldPlay = playWhenReady && acquireFocus()
                    mutableState.value = PlaybackState(
                        isPlaying = shouldPlay,
                        durationMs = ready.duration,
                        error = mutableState.value.error,
                    )
                    if (shouldPlay) {
                        ready.start()
                        trackProgress(ready)
                    }
                }.onFailure { fail("This voice note couldn't be played. Tap to try again.") }
            }
            next.setOnCompletionListener { completed ->
                if (player !== completed) return@setOnCompletionListener
                progressJob?.cancel()
                runCatching { completed.seekTo(0) }
                playWhenReady = false
                abandonFocus()
                mutableState.value = mutableState.value.copy(isPlaying = false, positionMs = 0)
            }
            next.setOnErrorListener { failed, _, _ ->
                if (player === failed) fail("This voice note couldn't be loaded. Check your connection and try again.")
                true
            }
            val uri = Uri.parse(url)
            if (context != null && uri.scheme in setOf("content", "android.resource", "file")) {
                next.setDataSource(context, uri)
            } else {
                next.setDataSource(url)
            }
            next.prepareAsync()
        } catch (_: Exception) {
            fail("This voice note couldn't be loaded. Tap to try again.")
        }
    }

    fun pause() {
        playWhenReady = false
        runCatching { if (player?.isPlaying == true) player?.pause() }
        progressJob?.cancel()
        abandonFocus()
        mutableState.value = mutableState.value.copy(isPlaying = false)
    }

    fun seekTo(positionMs: Int) {
        if (mutableState.value.isPreparing) return
        val target = positionMs.coerceIn(0, mutableState.value.durationMs)
        runCatching { player?.seekTo(target) }.onSuccess {
            mutableState.value = mutableState.value.copy(positionMs = target)
        }
    }

    private fun trackProgress(current: MediaPlayer) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val position = runCatching { current.currentPosition }.getOrDefault(0)
                mutableState.value = mutableState.value.copy(positionMs = position)
                delay(80)
            }
        }
    }

    private fun fail(message: String) {
        reset()
        mutableState.value = PlaybackState(error = message)
    }

    private fun reset() {
        playWhenReady = false
        progressJob?.cancel()
        progressJob = null
        runCatching { player?.release() }
        player = null
        source = null
        abandonFocus()
    }

    private fun acquireFocus(): Boolean {
        val manager = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
            }.build()
        focusRequest = request
        if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        abandonFocus()
        mutableState.value = mutableState.value.copy(error = "Audio is busy. Tap play when you're ready.")
        return false
    }

    private fun abandonFocus() {
        focusRequest?.let { request -> runCatching { audioManager?.abandonAudioFocusRequest(request) } }
        focusRequest = null
    }

    fun release() {
        reset()
        scope.cancel()
    }
}
