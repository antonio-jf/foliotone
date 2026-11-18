package com.linguareader

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Gapless playback for device voices that generate a whole sentence before returning any audio
 * (SherpaTTS/Piper and similar).
 *
 * When TextToSpeech plays an engine itself, Android lets synthesis run only about half a second ahead
 * of playback, so such engines leave a pause before every sentence and page. Here the engine instead
 * renders upcoming text to WAV files (synthesizeToFile, no such limit) on one thread, up to
 * [MAX_AHEAD] pieces ahead and across pages, while another thread streams the files into a single
 * AudioTrack. [Events] arrive on the main thread: a piece starting to play (for page and progress
 * updates), the end of the book, or a failure, after which the caller falls back to normal speech.
 *
 * The caller routes TextToSpeech callbacks for utterance ids starting with [ID_PREFIX] to
 * [synthesisFinished].
 */
class RenderAhead(
    context: Context,
    private val tts: TextToSpeech,
    private val chunks: Iterator<Chunk>,
    private val token: Int,
    private val events: Events
) {
    /**
     * One piece of speech: usually one sentence, starting at character [start] of a page. [pauseMs] of
     * silence follows it. A sentence that runs onto the next page carries that page in [nextSection]/
     * [nextPage]; the page turn is reported [splitAt] characters into [text].
     */
    data class Chunk(val section: Int, val page: Int, val start: Int, val text: String,
                     val pauseMs: Int = 140, val nextSection: Int = -1, val nextPage: Int = -1, val splitAt: Int = 0)

    interface Events {
        fun onChunkStarted(chunk: Chunk)
        fun onFinished()
        fun onFailed(message: String)
    }

    companion object {
        const val ID_PREFIX = "render:"
        private const val MAX_AHEAD = 24
        private const val SYNTHESIS_TIMEOUT_S = 90L
    }

    private class Rendered(val chunk: Chunk, val file: File)
    private object End
    private class Failure(val message: String)
    private class Wav(val rate: Int, val channels: Int, val pcm: ByteArray)

    private val main = Handler(Looper.getMainLooper())
    private val folder = File(context.cacheDir, "render").apply { mkdirs() }
    private val ready = LinkedBlockingQueue<Any>(MAX_AHEAD)
    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    private val outcome = ConcurrentHashMap<String, Boolean>()
    /** Frame (in the current track) at which each written piece starts playing. */
    private val boundaries = ConcurrentLinkedQueue<Pair<Long, Chunk>>()
    @Volatile private var cancelled = false
    @Volatile private var paused = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var framesWritten = 0L

    private val renderThread = Thread({ render() }, "FolioTone-render")
    private val playThread = Thread({ play() }, "FolioTone-play")
    private val monitor = object : Runnable {
        override fun run() {
            if (cancelled) return
            val head = track?.let { runCatching { it.playbackHeadPosition.toLong() and 0xffffffffL }.getOrNull() }
            if (head != null) {
                while (true) {
                    val next = boundaries.peek() ?: break
                    if (next.first > head) break
                    boundaries.poll()
                    events.onChunkStarted(next.second)
                }
            }
            main.postDelayed(this, 120)
        }
    }

    fun start(): RenderAhead {
        folder.listFiles()?.forEach { it.delete() }
        renderThread.start()
        playThread.start()
        main.post(monitor)
        return this
    }

    fun pause() {
        paused = true
        runCatching { track?.pause() }
    }

    fun resume() {
        paused = false
        runCatching { track?.play() }
    }

    fun stop() {
        if (cancelled) return
        cancelled = true
        main.removeCallbacks(monitor)
        pending.values.forEach { it.countDown() }
        // Pausing and flushing returns a blocked write; the play thread releases the track.
        runCatching { track?.pause(); track?.flush() }
        renderThread.interrupt()
        playThread.interrupt()
    }

    /** TextToSpeech finished (or failed or stopped) rendering one of our utterances. */
    fun synthesisFinished(id: String, success: Boolean) {
        outcome[id] = success
        pending.remove(id)?.countDown()
    }

    private fun fail(message: String) {
        if (cancelled) return
        ready.clear()
        ready.offer(Failure(message))
    }

    private fun render() {
        var index = 0
        try {
            while (!cancelled && chunks.hasNext()) {
                val chunk = chunks.next()
                val id = "$ID_PREFIX$token:${index++}"
                val file = File(folder, "$token-$index.wav")
                val done = CountDownLatch(1)
                pending[id] = done
                val started = tts.synthesizeToFile(chunk.text, Bundle(), file, id)
                if (started != TextToSpeech.SUCCESS) { fail("the voice refused to render text"); return }
                if (!done.await(SYNTHESIS_TIMEOUT_S, TimeUnit.SECONDS)) { fail("the voice took too long"); return }
                if (cancelled) return
                if (outcome.remove(id) != true || !file.isFile || file.length() <= 44) {
                    file.delete()
                    fail("the voice could not render a sentence")
                    return
                }
                ready.put(Rendered(chunk, file))
            }
            if (!cancelled) ready.put(End)
        } catch (e: InterruptedException) {
        } catch (error: Exception) {
            fail(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun play() {
        try {
            while (!cancelled) {
                when (val item = ready.take()) {
                    is End -> {
                        drain()
                        if (!cancelled) main.post { if (!cancelled) events.onFinished() }
                        return
                    }
                    is Failure -> {
                        main.post { if (!cancelled) events.onFailed(item.message) }
                        return
                    }
                    is Rendered -> {
                        val wav = readWav(item.file)
                        item.file.delete()
                        if (wav == null) {
                            main.post { if (!cancelled) events.onFailed("unreadable audio from the voice") }
                            return
                        }
                        val output = trackFor(wav) ?: run {
                            main.post { if (!cancelled) events.onFailed("could not open the audio output") }
                            return
                        }
                        val chunk = item.chunk
                        val startFrame = framesWritten
                        boundaries.add(startFrame to chunk)
                        if (chunk.nextPage >= 0 && chunk.splitAt in 1 until chunk.text.length) {
                            // Turn the page roughly where the sentence crosses it.
                            val frames = wav.pcm.size.toLong() / (2 * wav.channels)
                            boundaries.add(startFrame + frames * chunk.splitAt / chunk.text.length to
                                Chunk(chunk.nextSection, chunk.nextPage, 0, ""))
                        }
                        write(output, wav.pcm, wav.channels)
                        // Silence in whole frames: an odd byte count is rejected by AudioTrack.
                        val pauseFrames = wav.rate.toLong() * chunk.pauseMs.coerceIn(0, 2_000) / 1000
                        write(output, ByteArray((pauseFrames * wav.channels * 2).toInt()), wav.channels)
                    }
                }
            }
        } catch (e: InterruptedException) {
        } catch (error: Exception) {
            // Never leave the reader waiting on a dead player: report it so it falls back to normal speech.
            val message = error.message ?: error.javaClass.simpleName
            main.post { if (!cancelled) events.onFailed("playback stopped: $message") }
        } finally {
            track?.let { runCatching { it.pause(); it.flush(); it.release() } }
            track = null
            folder.listFiles()?.filter { it.name.startsWith("$token-") }?.forEach { it.delete() }
        }
    }

    private fun write(output: AudioTrack, bytes: ByteArray, channels: Int) {
        val frame = 2 * channels
        val size = bytes.size - bytes.size % frame
        var offset = 0
        while (offset < size && !cancelled) {
            val written = output.write(bytes, offset, minOf(8192, size - offset))
            if (written < 0) throw IllegalStateException("audio write failed ($written)")
            if (written == 0) { Thread.sleep(20); continue }
            offset += written
            framesWritten += written / frame
        }
    }

    /** Waits until everything written has been heard. */
    private fun drain() {
        val output = track ?: return
        while (!cancelled) {
            val head = runCatching { output.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(framesWritten)
            if (head >= framesWritten) break
            Thread.sleep(50)
        }
    }

    /** The output track for this format; a format change (rare) drains and replaces it. */
    private fun trackFor(wav: Wav): AudioTrack? {
        val current = track
        if (current != null && current.sampleRate == wav.rate && current.channelCount == wav.channels) return current
        if (current != null) {
            drain()
            runCatching { current.release() }
            boundaries.clear()
            framesWritten = 0
        }
        val mask = if (wav.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(wav.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(PlaybackAudioFocus.attributes)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(wav.rate)
                    .setChannelMask(mask)
                    .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, wav.rate * wav.channels * 2 / 2))
                .build()
                .also {
                    if (!paused) it.play()
                    track = it
                }
        }.getOrNull()
    }

    /** 16-bit PCM WAV, mono or stereo. Tolerates a missing or wrong data size. */
    private fun readWav(file: File): Wav? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (position + 8 <= bytes.size) {
            val name = String(bytes, position, 4)
            val size = buffer.getInt(position + 4)
            val body = position + 8
            when (name) {
                "fmt " -> {
                    if (buffer.getShort(body).toInt() != 1) return null
                    channels = buffer.getShort(body + 2).toInt()
                    rate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (rate <= 0 || channels !in 1..2 || bits != 16) return null
                    val end = if (size <= 0 || body + size > bytes.size) bytes.size else body + size
                    return Wav(rate, channels, bytes.copyOfRange(body, end - (end - body) % (2 * channels)))
                }
            }
            if (size < 0) return null
            position = body + size + (size and 1)
        }
        null
    }.getOrNull()
}
