package com.linguareader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/**
 * Main-thread audio ownership shared by narration and short voice previews.
 *
 * Another app taking the audio for good (music, a video) calls [onInterrupted]. With [onPausedBriefly]
 * set, a short interruption (navigation directions, a notification sound, a call) instead calls
 * [onPausedBriefly], keeps the focus request, and calls [onResumed] when the other app is done.
 */
class PlaybackAudioFocus(
    private val context: Context,
    private val handler: Handler,
    private val onPausedBriefly: (() -> Unit)? = null,
    private val onResumed: (() -> Unit)? = null,
    private val onInterrupted: () -> Unit
) {
    companion object {
        val attributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val manager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var listeningForDisconnect = false
    private var pausedBriefly = false
    val hasFocus: Boolean get() = request != null
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && hasFocus) interrupt()
        }
    }

    fun acquire(): Boolean {
        if (hasFocus) return true
        lateinit var candidate: AudioFocusRequest
        candidate = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                // Ignore an old request's queued callbacks after the user has pressed Play again.
                if (request === candidate) when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> interrupt()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        val brief = onPausedBriefly
                        if (brief == null) interrupt()
                        else if (!pausedBriefly) { pausedBriefly = true; brief() }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedBriefly) {
                        pausedBriefly = false
                        onResumed?.invoke()
                    }
                }
            }, handler)
            .build()
        request = candidate
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(noisyReceiver, filter)
        listeningForDisconnect = true
        if (manager.requestAudioFocus(candidate) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        release()
        return false
    }

    private fun interrupt() {
        release()
        onInterrupted()
    }

    fun release() {
        val previous = request
        request = null
        pausedBriefly = false
        if (listeningForDisconnect) {
            context.unregisterReceiver(noisyReceiver)
            listeningForDisconnect = false
        }
        if (previous != null) manager.abandonAudioFocusRequest(previous)
    }
}
