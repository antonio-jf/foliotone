package com.linguareader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaDescription
import android.net.Uri
import android.widget.Toast
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.browse.MediaBrowser
import android.service.media.MediaBrowserService
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException
import java.io.File

/** Android Auto browses this service; phone buttons and voice actions use the same session. */
class ReaderService : MediaBrowserService() {
    private data class PagePosition(val section: Int, val page: Int)
    /** [offset]: the utterance starts at this character of the page (resuming mid-page). */
    private data class DeviceUtterance(val position: PagePosition, val id: String, val offset: Int = 0)
    private data class CloudRequest(val bookId: String, val position: PagePosition, val key: String,
                                    val voiceId: String, val rate: Float, val text: String,
                                    val previous: String?, val following: String?)

    private lateinit var store: BookStore
    private lateinit var session: MediaSession
    private lateinit var tts: TextToSpeech
    private lateinit var audioFocus: PlaybackAudioFocus
    private val main = Handler(Looper.getMainLooper())
    private val browseWorker = Executors.newSingleThreadExecutor()
    private val audioWorker = Executors.newFixedThreadPool(2)
    private var cloudTask: Future<*>? = null
    private var prefetchedCloud: Future<File>? = null
    private var prefetchedRequest: CloudRequest? = null
    private var cloudPlayer: MediaPlayer? = null
    private var cloudLoading = false
    private var book: Book? = null
    private var sections: List<Section> = emptyList()
    private var playing = false
    private var ttsReady = false
    private var ttsEpoch = 0
    @Volatile private var generation = 0
    private var hasNarrated = false
    private var networkVoiceFailed = false
    private val deviceQueue = ArrayDeque<DeviceUtterance>()
    private val completedDeviceIds = mutableSetOf<String>()
    private val channel = "reader_playback"
    private var artwork: Bitmap? = null
    private var timeline = SectionTimeline(emptyList(), 1f)
    private var timelineSection = -1
    private var timelineRate = 0f
    private var pageStartedAt = 0L
    private var deviceFraction: Double? = null
    /** Character of the current page the device voice reached (when the engine reports ranges). */
    private var deviceChar: Int? = null
    // Brief interruption (navigation, a call): we keep the audio focus and continue afterwards.
    private var pausedBriefly = false
    private var pausedAt: PagePosition? = null
    private var pausedPlayer: MediaPlayer? = null
    /** Device voice: where to restart the interrupted page (sentence start). */
    private var resumeAt: Pair<PagePosition, Int>? = null
    private val resumeAfterInterruption = Runnable { resumeBriefly() }
    private var sectionFinished = false
    /** The device voice is reading a page that has no saved server audio (optional auto-switch). */
    private var deviceFallback = false
    /** Gapless device-voice playback (RenderAhead); off for the session after it fails once. */
    @Volatile private var renderer: RenderAhead? = null
    private var lastSpokenStart = -1
    /** Gapless playback is waiting for the voice (reported as buffering, so the time doesn't run on). */
    private var rendererWaiting = false
    // Sleep timer: a wall-clock time, or the end of the current section.
    private var sleepAt = 0L
    private var sleepAtSectionEnd = false
    private var sleepSection = -1
    private var fading = false
    private val sleepCheck = object : Runnable {
        override fun run() {
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) fadeOutAndPause()
            else if (sleepAt > 0) main.postDelayed(this, 5_000)
        }
    }
    private var renderFailed = false
    private var pausedRenderer = false
    /** Pieces gapless playback started most recently (newest last), for Replay. */
    private val playedChunks = ArrayDeque<RenderAhead.Chunk>()
    private val progressTick = object : Runnable {
        override fun run() {
            if (!playing) return
            publishPlayback()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        store = BookStore.get(this)
        audioFocus = PlaybackAudioFocus(this, main,
            onPausedBriefly = { pauseBriefly() },
            // A short delay lets the other app's last words finish before narration returns.
            onResumed = { main.postDelayed(resumeAfterInterruption, 600) }) { pause() }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(channel, tr("Narration"), NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "FolioTone").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seek(currentTimeline().pageAt(pos))
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    val tokens = mediaId?.split(':') ?: return
                    if (tokens.size >= 2 && tokens[0] == "book") open(tokens[1], tokens.getOrNull(2)?.toIntOrNull(), false, true)
                    // Continue: where the book was left, one page earlier if that setting is on.
                    if (tokens.size >= 3 && tokens[0] == "mark") {
                        store.bookmarks(tokens[1]).getOrNull(tokens[2].toIntOrNull() ?: -1)?.let { jumpTo(tokens[1], it) }
                    }
                    if (tokens.size >= 2 && tokens[0] == "resume") {
                        if (book?.id == tokens[1]) { if (!playing) play() } else open(tokens[1], null, true, true)
                    }
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    when (query?.trim()?.lowercase()) {
                        "stop", "stop reading" -> { stop(); return }
                        "pause", "pause reading" -> { pause(); return }
                        "resume", "resume reading" -> { play(); return }
                        "start section", "start from beginning of section", "beginning of section" -> { sectionStart(); if (!playing) play(); return }
                        "next section" -> { changeSection(1); if (!playing) play(); return }
                        "previous section" -> { changeSection(-1); if (!playing) play(); return }
                        "next page" -> { next(); if (!playing) play(); return }
                        "previous page" -> { previous(); if (!playing) play(); return }
                        "replay", "repeat", "repeat that", "say again", "say that again", "again",
                        "repetir", "repite", "repítelo", "otra vez", "de nuevo" -> { replay(); return }
                        "back", "go back", "rewind", "back 15 seconds", "atrás", "retrocede", "regresa" -> { rewind(15); return }
                        "bookmark", "bookmark this", "add bookmark", "marcador", "marcar", "guardar marcador" -> { addBookmark(); return }
                        "sleep timer", "stop in 30 minutes", "temporizador", "dormir" -> { setSleep(30); return }
                        "sleep at end of chapter", "stop at end of chapter", "al final del capítulo" -> { setSleep(-1); return }
                        "cancel sleep timer", "cancelar temporizador" -> { setSleep(0); return }
                        "report sentence", "report that", "reportar frase", "reportar" -> { reportSentence(); return }
                    }
                    val found = store.books().firstOrNull { query.isNullOrBlank() || it.title.contains(query, true) }
                    if (found != null) open(found.id, null, false, true)
                }
                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == "section_start") sectionStart()
                    if (action == "replay") replay()
                    if (action == "back15") rewind(15)
                    if (action == "bookmark") addBookmark()
                    // Car button: 30 minutes, then end of chapter, then off.
                    if (action == "sleep") setSleep(when { sleepAt == 0L && !sleepAtSectionEnd -> 30; sleepAt > 0 -> -1; else -> 0 })
                }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        startTts()
        state()
    }

    private fun startTts() {
        val epoch = ++ttsEpoch
        ttsReady = false
        if (::tts.isInitialized) {
            if (store.narrationSource == "device") generation++
            tts.stop(); tts.shutdown()
        }
        val listener = TextToSpeech.OnInitListener { status ->
            main.post { if (epoch == ttsEpoch) onTtsReady(status) }
        }
        val engine = store.speechEngine
        tts = if (engine == null) TextToSpeech(this, listener)
            else TextToSpeech(this, listener, engine)
    }

    private fun onTtsReady(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) {
            if (store.speechEngine != null) {
                store.speechEngine = null
                startTts()
            } else if (store.narrationSource == "device") {
                pause(tr("Android text-to-speech is unavailable on this device"))
            }
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId?.startsWith(RenderAhead.ID_PREFIX) == true) return
                main.post {
                    val utterance = deviceQueue.firstOrNull()
                    if (playing && utterance != null && utterance.id == utteranceId) {
                        pageStartedAt = SystemClock.elapsedRealtime()
                        deviceFraction = null
                        deviceChar = utterance.offset.takeIf { it > 0 }
                        lastSpokenStart = -1
                    }
                }
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId?.startsWith(RenderAhead.ID_PREFIX) == true) return
                main.post {
                    val utterance = deviceQueue.firstOrNull()
                    if (playing && utterance != null && utterance.id == utteranceId) {
                        val text = book?.let { sections[it.section].pages[it.page] }.orEmpty()
                        deviceChar = utterance.offset + start
                        val sentenceAt = sentenceStart(text, utterance.offset + start)
                        if (sentenceAt != lastSpokenStart) {
                            lastSpokenStart = sentenceAt
                            val next = SENTENCE_END.find(text, (utterance.offset + start).coerceAtMost(text.length))
                            spoken(sentenceAt, next?.range?.last?.plus(1) ?: text.length)
                        }
                        deviceFraction = ((utterance.offset + start).toDouble() / text.length.coerceAtLeast(1)).coerceIn(0.0, 0.99)
                    }
                }
            }
            override fun onDone(utteranceId: String?) {
                if (rendered(utteranceId, true)) return
                main.post { deviceDone(utteranceId) }
            }
            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) {
                if (rendered(utteranceId, false)) return
                main.post { narrationError(utteranceId) }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (rendered(utteranceId, false)) return
                main.post { narrationError(utteranceId) }
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                rendered(utteranceId, false)
            }
        })
        if (playing && (store.narrationSource == "device" || deviceFallback)) speak()
    }

    /** Routes a TextToSpeech callback for a RenderAhead utterance (any thread). */
    private fun rendered(utteranceId: String?, success: Boolean): Boolean {
        if (utteranceId?.startsWith(RenderAhead.ID_PREFIX) != true) return false
        android.util.Log.i("FolioToneRender", "callback $utteranceId success=$success renderer=${renderer != null}")
        renderer?.synthesisFinished(utteranceId, success)
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "GET_STATE" -> state()
            "OPEN" -> open(intent.getStringExtra("id") ?: return START_STICKY, null, true, false)
            "OPEN_CURRENT" -> {
                val id = intent.getStringExtra("id") ?: return START_STICKY
                if (book?.id == id) state() else open(id, null, false, false)
            }
            "PLAY" -> play()
            "REPLAY" -> replay()
            "BACK15" -> rewind(15)
            "REPORT" -> reportSentence()
            "READ_FROM" -> readFrom(intent.getIntExtra("section", -1), intent.getIntExtra("page", -1),
                intent.getIntExtra("char", 0), intent.getBooleanExtra("play", true))
            "SLEEP" -> setSleep(intent.getIntExtra("minutes", 0))
            "BOOKMARK" -> addBookmark()
            "BOOKMARK_JUMP" -> {
                val id = intent.getStringExtra("id")
                val mark = id?.let { store.bookmarks(it).getOrNull(intent.getIntExtra("index", -1)) }
                if (id != null && mark != null) jumpTo(id, mark)
            }
            "PAUSE" -> pause()
            "STOP" -> stop()
            "NEXT" -> next()
            "PREVIOUS" -> previous()
            "SECTION_START" -> sectionStart()
            "SECTION_NEXT" -> changeSection(1)
            "SECTION_PREVIOUS" -> changeSection(-1)
            "SECTION_JUMP" -> jumpToSection(intent.getStringExtra("id"), intent.getIntExtra("section", -1))
            "SEEK" -> seek(intent.getIntExtra("page", 0))
            "LANGUAGE" -> {
                networkVoiceFailed = false
                book?.let { current ->
                    current.language = intent.getStringExtra("language").orEmpty()
                    store.books().firstOrNull { it.id == current.id }?.let { saved ->
                        saved.language = current.language; store.update(saved)
                    }
                }
                if (playing) speak() else state()
            }
            "VOICE_CHANGED" -> {
                networkVoiceFailed = false
                renderFailed = false
                if (playing) speak() else state()
            }
            "ENGINE_CHANGED" -> {
                networkVoiceFailed = false
                renderFailed = false
                startTts()
                if (!playing) state()
            }
            "SOURCE_CHANGED" -> {
                networkVoiceFailed = false
                if (store.narrationSource == "device" && !ttsReady) {
                    haltAudio()
                    startTts()
                    if (!playing) state()
                } else if (playing) speak() else { haltAudio(); state() }
            }
        }
        return START_STICKY
    }

    private fun open(id: String, section: Int?, contextPage: Boolean, autoplay: Boolean) {
        val selected = store.books().firstOrNull { it.id == id } ?: return
        val parsed = runCatching { Epub.parse(store.file(selected)) }.getOrNull() ?: return
        haltAudio()
        if (!autoplay) audioFocus.release()
        store.lastOpenedId = id
        book = selected; sections = parsed.sections
        artwork = loadArtwork(selected)
        timelineSection = -1
        if (section != null) { selected.section = section.coerceIn(sections.indices); selected.page = 0 }
        else if (contextPage && store.contextResume) {
            if (selected.page > 0) selected.page--
            else if (selected.section > 0) {
                selected.section--
                selected.page = sections[selected.section].pages.lastIndex
            }
        }
        selected.section = selected.section.coerceIn(sections.indices)
        selected.page = selected.page.coerceIn(sections[selected.section].pages.indices)
        saveProgress(selected)
        playing = autoplay
        if (autoplay) speak() else {
            hasNarrated = false
            stopForeground(STOP_FOREGROUND_REMOVE); state()
        }
    }

    private fun play() {
        if (pausedBriefly) { main.removeCallbacks(resumeAfterInterruption); resumeBriefly(); return }
        if (book == null) {
            val recent = store.books().firstOrNull() ?: return
            open(recent.id, null, false, true)
            return
        }
        playing = true; speak()
    }

    private fun rate() = store.speechRateFor(book?.id)

    private fun pause(error: String? = null) {
        if (!fading) cancelSleep()
        clearBriefPause()
        val b = book
        val reached = deviceChar
        if (renderer != null && b != null && reached != null) {
            // Gapless playback knows the piece being read: Play continues from it, not from the page start.
            sections.getOrNull(b.section)?.pages?.getOrNull(b.page)?.let { page ->
                resumeAt = PagePosition(b.section, b.page) to sentenceStart(page, reached)
            }
        }
        playing = false
        haltAudio()
        audioFocus.release()
        state(error)
    }
    private fun stop() { pause(); hasNarrated = false; stopForeground(STOP_FOREGROUND_REMOVE) }

    /**
     * Back [seconds]: saved audio steps back in the file; gapless playback restarts the piece that began
     * about that long ago (about 14 characters a second at normal speed); otherwise the current sentence.
     */
    private fun rewind(seconds: Int) {
        val player = cloudPlayer
        if (player != null && !cloudLoading) {
            runCatching { player.seekTo((player.currentPosition - seconds * 1_000).coerceAtLeast(0)) }
            if (!playing) play() else state()
            return
        }
        val history = playedChunks.toList()
        if (history.isEmpty()) { replay(0); return }
        var chars = 0
        var back = 0
        for (i in history.indices.reversed()) {
            chars += history[i].text.length
            back = history.lastIndex - i
            if (chars >= seconds * 14 * rate()) break
        }
        replay(back)
    }

    private fun sleepLabel(): String = when {
        sleepAtSectionEnd -> tr("Sleep: end of chapter")
        sleepAt > 0 -> tr("Sleep: {0} min", ((sleepAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1))
        else -> tr("Sleep timer")
    }

    /** [minutes] > 0: stop after that long; -1: at the end of the current section; 0: off. */
    private fun setSleep(minutes: Int) {
        main.removeCallbacks(sleepCheck)
        sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        sleepAtSectionEnd = minutes < 0
        sleepSection = book?.section ?: -1
        if (sleepAt > 0) main.postDelayed(sleepCheck, 5_000)
        Toast.makeText(this, when {
            minutes > 0 -> tr("Narration stops in {0} minutes", minutes)
            minutes < 0 -> tr("Narration stops at the end of this chapter")
            else -> tr("Sleep timer off")
        }, Toast.LENGTH_SHORT).show()
        state()
    }

    private fun cancelSleep() {
        main.removeCallbacks(sleepCheck)
        sleepAt = 0L
        sleepAtSectionEnd = false
    }

    /** Lowers the volume over about 8 seconds, then pauses and restores it. */
    private fun fadeOutAndPause() {
        cancelSleep()
        if (!playing || fading) { state(); return }
        fading = true
        val steps = 16
        fun level(value: Float) {
            renderer?.setVolume(value)
            runCatching { cloudPlayer?.setVolume(value, value) }
        }
        for (step in 1..steps) main.postDelayed({
            if (fading) {
                level(1f - step / steps.toFloat())
                if (step == steps) {
                    pause()
                    level(1f)
                    fading = false
                }
            }
        }, step * 500L)
    }

    /** Saves the current place (the sentence being read, when known) as a bookmark. */
    private fun addBookmark() {
        val b = book ?: return
        val page = sections.getOrNull(b.section)?.pages?.getOrNull(b.page) ?: return
        val char = (deviceChar ?: 0).coerceIn(0, page.length)
        val start = sentenceStart(page, char)
        val snippet = page.substring(start).trim().replace(Regex("\\s+"), " ").take(90)
        store.addBookmark(b.id, BookStore.Bookmark(b.section, b.page, start, snippet, System.currentTimeMillis()))
        Toast.makeText(this, tr("Bookmark added"), Toast.LENGTH_SHORT).show()
        notifyChildrenChanged("bookmarks")
    }

    /**
     * Saves the sentence just read (as on the page and as given to the voice) for review in Listening
     * settings → Reported sentences, where a pronunciation fix can be added or the list shared.
     */
    private fun reportSentence() {
        val b = book ?: return
        val language = NarrationVoices.locale(b.language).language
        val piece = playedChunks.lastOrNull { it.text.isNotBlank() }
        val (original, spokenText) = if (piece != null) {
            val page = sections.getOrNull(piece.section)?.pages?.getOrNull(piece.page).orEmpty()
            val end = if (piece.end > piece.start) piece.end.coerceAtMost(page.length) else page.length
            page.substring(piece.start.coerceIn(0, page.length), end.coerceAtLeast(piece.start.coerceIn(0, page.length))).trim() to piece.text
        } else {
            val page = sections.getOrNull(b.section)?.pages?.getOrNull(b.page).orEmpty()
            val start = sentenceStart(page, (deviceChar ?: 0).coerceIn(0, page.length))
            val end = SENTENCE_END.find(page, start)?.range?.last?.plus(1) ?: page.length
            val text = page.substring(start, end).trim()
            text to SpeechText.normalize(text, language)
        }
        if (original.isBlank()) return
        val engine = store.speechEngine ?: android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.TTS_DEFAULT_SYNTH) ?: "default"
        store.saveReports(store.reports() + BookStore.Report(original, spokenText, b.title, engine, System.currentTimeMillis()))
        Toast.makeText(this, tr("Sentence saved for review"), Toast.LENGTH_SHORT).show()
    }

    /** Tells the reader screen which part of the page is being read, so it can highlight it. */
    private fun spoken(start: Int, end: Int) {
        val b = book ?: return
        sendBroadcast(Intent("com.linguareader.SPOKEN").setPackage(packageName)
            .putExtra("id", b.id).putExtra("section", b.section).putExtra("page", b.page)
            .putExtra("start", start).putExtra("end", end))
    }

    /** Reads from [char] of a page of the open book (tap a sentence, search results); [play] false only moves there. */
    private fun readFrom(section: Int, page: Int, char: Int, play: Boolean) {
        val b = book ?: return
        if (section !in sections.indices) return
        val target = PagePosition(section, page.coerceIn(sections[section].pages.indices))
        val text = sections[target.section].pages[target.page]
        clearBriefPause()
        val wasPlaying = playing
        haltAudio()
        sleepSection = target.section
        if (target != PagePosition(b.section, b.page)) moveTo(target)
        resumeAt = target to sentenceStart(text, char.coerceIn(0, text.length))
        if (play || wasPlaying) { playing = true; speak() } else state()
    }

    /** Opens [bookId] at [mark] and reads from there. */
    private fun jumpTo(bookId: String, mark: BookStore.Bookmark) {
        if (book?.id != bookId) open(bookId, mark.section, false, false)
        val b = book ?: return
        if (b.id != bookId || mark.section !in sections.indices) return
        clearBriefPause()
        haltAudio()
        sleepSection = mark.section // a jump is not "reaching the end of the chapter"
        moveTo(PagePosition(mark.section, mark.page.coerceIn(sections[mark.section].pages.indices)))
        resumeAt = PagePosition(b.section, b.page) to mark.char
        playing = true
        speak()
    }

    /**
     * Replay: with gapless device playback, go back [back] pieces and generate them again (voices like
     * SherpaTTS/Piper vary a little each time, so a garbled sentence usually comes out right). Otherwise
     * restart from the current sentence (device voice) or step back 10 s (saved audio).
     */
    private fun replay(back: Int = 2) {
        val b = book ?: return
        val history = playedChunks.toList()
        val target = history.getOrNull(history.size - 1 - back) ?: history.firstOrNull()
        val player = cloudPlayer
        when {
            target != null -> {
                val position = PagePosition(target.section, target.page)
                clearBriefPause()
                haltAudio()
                if (position != PagePosition(b.section, b.page)) moveTo(position)
                resumeAt = position to target.start
            }
            player != null && !cloudLoading -> {
                runCatching { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
                if (!playing) play() else state()
                return
            }
            else -> {
                val page = sections.getOrNull(b.section)?.pages?.getOrNull(b.page)
                val reached = deviceChar
                val saved = resumeAt // set by Pause: the piece that was playing
                clearBriefPause()
                resumeAt = if (page != null && reached != null) PagePosition(b.section, b.page) to sentenceStart(page, reached)
                    else saved
            }
        }
        playing = true
        speak()
    }

    /** Another app talks for a moment: hold our place and keep the audio focus. */
    private fun pauseBriefly() {
        val b = book
        if (!playing || b == null) return
        pausedBriefly = true
        val position = PagePosition(b.section, b.page)
        pausedAt = position
        val player = cloudPlayer
        val gapless = renderer
        if (gapless != null) {
            gapless.pause()
            pausedRenderer = true
        } else if (player != null && !cloudLoading && runCatching { player.isPlaying }.getOrDefault(false)) {
            player.pause()
            pausedPlayer = player
        } else {
            val page = sections[b.section].pages[b.page]
            val reached = deviceChar ?: if (deviceQueue.isNotEmpty() && pageStartedAt > 0) {
                // Engines without word progress: estimate from the time spent on the page.
                val elapsed = SystemClock.elapsedRealtime() - pageStartedAt
                (page.length * 0.9 * elapsed / currentTimeline().pageDurationMs(b.page).coerceAtLeast(1)).toInt()
            } else null
            resumeAt = reached?.let { position to sentenceStart(page, it) }
            haltAudio()
        }
        playing = false
        state()
    }

    private fun resumeBriefly() {
        if (!pausedBriefly) return
        val b = book
        val player = pausedPlayer
        val samePage = b != null && pausedAt == PagePosition(b.section, b.page)
        val gapless = renderer.takeIf { pausedRenderer }
        clearBriefPause(keepResumePoint = true)
        if (b == null) return
        playing = true
        if (gapless != null && gapless === renderer && samePage) {
            gapless.resume()
            state()
            return
        }
        if (player != null && player === cloudPlayer && samePage) {
            val resumed = runCatching {
                // Step back two seconds so the interrupted words are heard again.
                player.seekTo((player.currentPosition - 2_000).coerceAtLeast(0))
                player.start()
            }
            if (resumed.isSuccess) { state(); return }
        }
        if (!samePage) resumeAt = null
        speak()
    }

    private fun clearBriefPause(keepResumePoint: Boolean = false) {
        main.removeCallbacks(resumeAfterInterruption)
        pausedBriefly = false
        pausedAt = null
        pausedPlayer = null
        pausedRenderer = false
        if (!keepResumePoint) resumeAt = null
    }

    /** Start of the sentence containing [char], so a resumed page does not begin mid-sentence. */
    private fun sentenceStart(text: String, char: Int): Int {
        val limit = char.coerceIn(0, text.length)
        val boundary = Regex("[.!?…;:»”\"]\\s+|\\n").findAll(text, 0).lastOrNull { it.range.last < limit }
        return boundary?.range?.last?.plus(1) ?: 0
    }

    private fun next(automatic: Boolean = false) {
        val b = book ?: return
        if (b.page < sections[b.section].pages.lastIndex) b.page++
        else if (b.section < sections.lastIndex) { b.section++; b.page = 0 }
        else { finishBook(); return }
        saveProgress(b)
        if (playing || automatic) speak(preserveCloudPrefetch = true) else state()
    }

    private fun previous() {
        val b = book ?: return
        if (b.page > 0) b.page--
        else if (b.section > 0) { b.section--; b.page = sections[b.section].pages.lastIndex }
        saveProgress(b)
        if (playing) speak() else state()
    }

    private fun seek(page: Int) {
        val b = book ?: return
        b.page = page.coerceIn(sections[b.section].pages.indices)
        saveProgress(b)
        if (playing) speak() else state()
    }

    private fun sectionStart() = seek(0)

    private fun changeSection(offset: Int) {
        val b = book ?: return
        b.section = (b.section + offset).coerceIn(sections.indices)
        b.page = 0; saveProgress(b)
        if (playing) speak() else state()
    }

    private fun jumpToSection(id: String?, index: Int) {
        val b = book ?: return
        if (b.id != id || index !in sections.indices || index == b.section) return
        b.section = index
        b.page = 0
        saveProgress(b)
        if (playing) speak() else state()
    }

    private fun nextPosition(position: PagePosition): PagePosition? = when {
        position.page < sections[position.section].pages.lastIndex ->
            PagePosition(position.section, position.page + 1)
        position.section < sections.lastIndex -> PagePosition(position.section + 1, 0)
        else -> null
    }

    private fun previousPosition(position: PagePosition): PagePosition? = when {
        position.page > 0 -> PagePosition(position.section, position.page - 1)
        position.section > 0 -> PagePosition(position.section - 1, sections[position.section - 1].pages.lastIndex)
        else -> null
    }

    private fun moveTo(position: PagePosition) {
        // Sleep timer "end of chapter": stop when narration reaches the next section.
        if (sleepAtSectionEnd && playing && book?.section == sleepSection && position.section != sleepSection) {
            book?.let { it.section = position.section; it.page = position.page; saveProgress(it) }
            fadeOutAndPause()
            return
        }
        book?.let {
            it.section = position.section
            it.page = position.page
            saveProgress(it)
        }
    }

    private fun speak(preserveCloudPrefetch: Boolean = false) {
        val b = book ?: return
        haltAudio(preserveCloudPrefetch)
        val token = generation
        if (store.narrationSource == "elevenlabs") {
            speakEleven(b, token)
            return
        }
        if (store.narrationSource == "server") {
            speakServer(b, token)
            return
        }
        speakDevice(b, token, fallback = false)
    }

    /**
     * Reads with the Android voice. With [fallback] only the current page is queued: when it ends,
     * speak() runs again and returns to saved server audio if the next page has it.
     */
    private fun speakDevice(b: Book, token: Int, fallback: Boolean) {
        deviceFallback = fallback
        if (!ttsReady) {
            // Own focus while the provider initializes, so an interruption cancels pending speech too.
            if (beginPlayback()) state()
            return
        }
        val locale = NarrationVoices.locale(b.language)
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
            pause(tr("Install a {0} text-to-speech voice in Android settings", locale.displayLanguage))
            return
        }
        NarrationVoices.choose(tts, locale.language, store.voiceName(locale.language), !networkVoiceFailed)
            ?.let { tts.setVoice(it) }
        tts.setSpeechRate(rate())
        tts.setPitch(1.0f)
        tts.setAudioAttributes(PlaybackAudioFocus.attributes)
        if (!beginPlayback()) return
        val current = PagePosition(b.section, b.page)
        val offset = resumeAt?.takeIf { it.first == current }?.second ?: 0
        resumeAt = null
        if (!fallback && store.renderAhead && !renderFailed) {
            startRenderer(current, offset, token)
            hasNarrated = true
            state()
            return
        }
        if (!queueDevice(current, token, TextToSpeech.QUEUE_FLUSH, offset)) {
            pause(tr("This voice could not start reading. Try another voice in Listening settings."))
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (!fallback) queueNextDevice(token)
        hasNarrated = true
        state()
    }

    /** Starts gapless playback from [start] (character [offset] of that page) to the end of the book. */
    private fun startRenderer(start: PagePosition, offset: Int, token: Int) {
        renderer = RenderAhead(this, tts, renderChunks(start, offset), token, object : RenderAhead.Events {
            override fun onChunkStarted(chunk: RenderAhead.Chunk) {
                val b = book
                if (token != generation || b == null) return
                val position = PagePosition(chunk.section, chunk.page)
                val pageChanged = position != PagePosition(b.section, b.page)
                if (pageChanged) moveTo(position)
                if (chunk.text.isNotEmpty()) {
                    playedChunks.addLast(chunk)
                    while (playedChunks.size > 12) playedChunks.removeFirst()
                }
                val length = sections.getOrNull(chunk.section)?.pages?.getOrNull(chunk.page)?.length ?: 1
                pageStartedAt = SystemClock.elapsedRealtime()
                deviceChar = chunk.start
                deviceFraction = (chunk.start.toDouble() / length.coerceAtLeast(1)).coerceIn(0.0, 0.99)
                if (pageChanged) state() else publishPlayback()
                spoken(chunk.start, if (chunk.end > chunk.start) chunk.end else length)
            }
            override fun onFinished() {
                if (token == generation && playing) finishBook()
            }
            override fun onBuffering(waiting: Boolean) {
                if (token != generation) return
                rendererWaiting = waiting
                publishPlayback()
            }
            override fun onFailed(message: String) {
                if (token != generation) return
                android.util.Log.w("FolioTone", "Gapless playback failed ($message); using normal speech")
                renderFailed = true
                if (playing) speak()
            }
        }).start()
    }

    /** One sentence of a page: where it starts, its text, the pause after it, and whether it ends on this page. */
    private class Sentence(val start: Int, val text: String, val pauseMs: Int, val complete: Boolean)

    /**
     * Text from [start]/[offset] to the end of the book, one sentence per piece. Sentence-sized pieces
     * let FolioTone add the pauses after full stops and paragraphs that engines like SherpaTTS skip, and
     * a sentence cut by a page break is joined into one piece so it is read in one breath.
     */
    private fun renderChunks(start: PagePosition, offset: Int): Iterator<RenderAhead.Chunk> {
        val language = NarrationVoices.locale(book?.language.orEmpty()).language
        val pages = sections
        fun spoken(text: String) = SpeechText.normalize(text.trim(), language)
        return iterator {
            var position: PagePosition? = start
            var from = offset
            var carry: Pair<PagePosition, Sentence>? = null
            while (true) {
                val here = position ?: break
                val page = pages[here.section].pages[here.page]
                val sentences = renderSentences(page, from.coerceIn(0, page.length))
                var index = 0
                carry?.let { (carryPosition, unfinished) ->
                    val head = spoken(unfinished.text)
                    val rest = sentences.firstOrNull()
                    if (rest != null) {
                        val tail = spoken(rest.text)
                        yield(RenderAhead.Chunk(carryPosition.section, carryPosition.page, unfinished.start,
                            "$head $tail", rest.pauseMs, here.section, here.page, head.length + 1,
                            end = unfinished.start + unfinished.text.length, nextEnd = rest.start + rest.text.length))
                        index = 1
                    } else {
                        yield(RenderAhead.Chunk(carryPosition.section, carryPosition.page, unfinished.start, head, unfinished.pauseMs,
                            end = unfinished.start + unfinished.text.length))
                    }
                }
                carry = null
                val following = nextPosition(here)
                for (i in index until sentences.size) {
                    val sentence = sentences[i]
                    if (i == sentences.lastIndex && !sentence.complete && following != null) {
                        carry = here to sentence
                        break
                    }
                    val text = spoken(sentence.text)
                    if (text.isNotBlank()) {
                        yield(RenderAhead.Chunk(here.section, here.page, sentence.start, text, sentence.pauseMs,
                            end = sentence.start + sentence.text.length))
                    }
                }
                from = 0
                position = following
            }
            carry?.let { (carryPosition, unfinished) ->
                yield(RenderAhead.Chunk(carryPosition.section, carryPosition.page, unfinished.start,
                    spoken(unfinished.text), unfinished.pauseMs, end = unfinished.start + unfinished.text.length))
            }
        }
    }

    /**
     * Splits a page from [from] into sentences. The pause after each follows its ending: a full stop,
     * question or exclamation mark, a longer one for an ellipsis, and longer still for a paragraph break.
     * Abbreviations (Mr., Dr., Sra., initials) do not end a sentence.
     */
    private fun renderSentences(page: String, from: Int): List<Sentence> {
        val pauseScale = store.pauseScale
        val sentences = mutableListOf<Sentence>()
        var cursor = from
        for (end in SENTENCE_END.findAll(page, from)) {
            val stop = end.range.last + 1
            if (end.value.startsWith(".") && !end.value.contains('\n')) {
                val word = Regex("(\\p{L}+)$").find(page.substring(cursor, end.range.first))?.value.orEmpty()
                if (word.lowercase() in ABBREVIATIONS || (word.length == 1 && word[0].isUpperCase())) continue
            }
            val pause = when {
                end.value.count { it == '\n' } >= 1 -> PARAGRAPH_PAUSE_MS
                end.value.contains('…') || end.value.contains("...") -> SENTENCE_PAUSE_MS
                else -> SENTENCE_PAUSE_MS
            }.let { (it * pauseScale).toInt() }
            if (page.substring(cursor, stop).isNotBlank()) sentences += Sentence(cursor, page.substring(cursor, stop), pause, true)
            cursor = stop
        }
        val rest = page.substring(cursor)
        if (rest.isNotBlank()) {
            val complete = rest.trimEnd().lastOrNull()?.let { it in ".!?…»”\"'’)" } ?: true
            sentences += Sentence(cursor, rest, (SENTENCE_PAUSE_MS * pauseScale).toInt(), complete)
        }
        return sentences.flatMap { splitLong(it) }
    }

    /**
     * Splits a long sentence at commas, semicolons, colons and dashes into parts of about
     * [CLAUSE_TARGET] characters (at spaces if a clause is still too long). Voices like SherpaTTS/Piper
     * slow down more than proportionally on long input: on a test phone 20 characters rendered in 0.7 s,
     * but a 219-character sentence took over 16 s, longer than it takes to hear.
     */
    private fun splitLong(sentence: Sentence): List<Sentence> {
        val text = sentence.text
        if (text.length <= CLAUSE_LIMIT) return listOf(sentence)
        val cuts = CLAUSE_END.findAll(text).map { it.range.last + 1 }.toMutableList()
        val parts = mutableListOf<Pair<Int, Int>>()
        var partStart = 0
        while (text.length - partStart > CLAUSE_LIMIT) {
            val limit = partStart + CLAUSE_TARGET
            var cut = cuts.lastOrNull { it in (partStart + 20)..limit }
                ?: cuts.firstOrNull { it in (limit + 1)..(partStart + CLAUSE_LIMIT) }
            if (cut == null) {
                // No clause boundary nearby: cut at the last space before the target.
                cut = text.lastIndexOf(' ', limit).takeIf { it > partStart + 20 }?.plus(1) ?: break
            }
            parts += partStart to cut
            partStart = cut
        }
        parts += partStart to text.length
        return parts.mapIndexed { index, (from, to) ->
            val last = index == parts.lastIndex
            Sentence(sentence.start + from, text.substring(from, to),
                if (last) sentence.pauseMs else 0, if (last) sentence.complete else true)
        }.filter { it.text.isNotBlank() }
    }

    private fun beginPlayback(): Boolean {
        // Android 15 requires a foreground service before a background player can request focus.
        val started = runCatching { startForeground(1, notification()) }
        if (started.isFailure) {
            pause(tr("Cannot start background narration. Open the app and press Play again."))
            return false
        }
        if (audioFocus.acquire()) return true
        pause(tr("Audio is in use by another app. Press Play when it is available."))
        stopForeground(STOP_FOREGROUND_REMOVE)
        return false
    }

    private fun queueDevice(position: PagePosition, token: Int, mode: Int, offset: Int = 0): Boolean {
        val id = "$token:${position.section}:${position.page}"
        val page = sections[position.section].pages[position.page]
        val start = if (offset in 1 until page.length) offset else 0
        val language = NarrationVoices.locale(book?.language.orEmpty()).language
        val result = tts.speak(SpeechText.normalize(page.substring(start), language), mode, null, id)
        if (result != TextToSpeech.SUCCESS) return false
        deviceQueue.addLast(DeviceUtterance(position, id, start))
        return true
    }

    private fun queueNextDevice(token: Int) {
        if (deviceQueue.size != 1) return
        val position = nextPosition(deviceQueue.last().position) ?: return
        queueDevice(position, token, TextToSpeech.QUEUE_ADD)
    }

    private fun deviceDone(id: String?) {
        if (!playing || (store.narrationSource != "device" && !deviceFallback) ||
            id?.substringBefore(':')?.toIntOrNull() != generation ||
            deviceQueue.none { it.id == id }) return
        completedDeviceIds += id
        while (deviceQueue.isNotEmpty() && deviceQueue.first().id in completedDeviceIds) {
            val finished = deviceQueue.removeFirst()
            completedDeviceIds.remove(finished.id)
            val queued = deviceQueue.firstOrNull()
            if (queued != null) {
                moveTo(queued.position)
                queueNextDevice(generation)
                state()
            } else {
                val following = nextPosition(finished.position)
                if (following == null) finishBook()
                else { moveTo(following); speak() }
                return
            }
        }
    }

    private fun haltAudio(preserveCloudPrefetch: Boolean = false) {
        main.removeCallbacks(progressTick)
        pageStartedAt = 0L
        deviceFraction = null
        deviceChar = null
        pausedPlayer = null
        sectionFinished = false
        generation++
        cloudTask?.cancel(true)
        cloudTask = null
        if (!preserveCloudPrefetch) {
            prefetchedCloud?.cancel(true)
            prefetchedCloud = null
            prefetchedRequest = null
        }
        cloudLoading = false
        cloudPlayer?.apply { setOnCompletionListener(null); release() }
        cloudPlayer = null
        deviceQueue.clear()
        completedDeviceIds.clear()
        deviceFallback = false
        renderer?.stop()
        renderer = null
        rendererWaiting = false
        pausedRenderer = false
        playedChunks.clear()
        if (::tts.isInitialized) tts.stop()
    }

    private fun cloudRequest(book: Book, position: PagePosition, key: String,
                             voiceId: String, rate: Float): CloudRequest {
        val previous = previousPosition(position)?.let { sections[it.section].pages[it.page] }
        val following = nextPosition(position)?.let { sections[it.section].pages[it.page] }
        return CloudRequest(book.id, position, key, voiceId, rate,
            sections[position.section].pages[position.page], previous, following)
    }

    private fun speakEleven(book: Book, token: Int) {
        val key = ElevenLabsKey(this).read()
        val voiceId = store.elevenVoiceId(NarrationVoices.locale(book.language).language)
        if (key.isNullOrBlank() || voiceId.isNullOrBlank()) {
            pause(tr("Set an ElevenLabs API key and choose a voice in Listening settings"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (!beginPlayback()) return
        val request = cloudRequest(book, PagePosition(book.section, book.page), key, voiceId, rate())
        val queued = if (prefetchedRequest == request) prefetchedCloud else null
        if (queued == null) prefetchedCloud?.cancel(true)
        prefetchedCloud = null
        prefetchedRequest = null
        cloudLoading = true
        state()
        cloudTask = audioWorker.submit {
            if (token != generation) return@submit
            val result = runCatching {
                if (queued == null) generateCloud(request)
                else try { queued.get() }
                    catch (_: ExecutionException) { generateCloud(request) }
                    catch (_: CancellationException) { generateCloud(request) }
            }
            main.post {
                if (token != generation || !playing || store.narrationSource != "elevenlabs") return@post
                result.onSuccess { playCloudFile(it, token, request) }
                    .onFailure { cloudFailure(ElevenLabs.message(it), token) }
            }
        }
    }

    /** Plays audio that the user's narration server generated earlier; nothing is fetched here. */
    private fun speakServer(book: Book, token: Int) {
        val voice = store.serverVoice(NarrationVoices.locale(book.language).language)
        val text = sections[book.section].pages[book.page]
        val file = ServerAudio.file(this, book.id, ServerAudio.key(voice, text))
        if (!file.isFile || file.length() == 0L) {
            if (store.serverDeviceFallback) { speakDevice(book, token, fallback = true); return }
            pause(if (ServerAudio.hasAny(this, book.id))
                tr("This page has no saved audio for the selected voice. Open Manage offline audio to download the rest.")
                else tr("Download this book's audio first: Manage offline audio → Download book audio."))
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (!beginPlayback()) return
        cloudLoading = true
        state()
        // Audio is generated at normal speed; the narration speed is applied while playing.
        playCloudFile(file, token, null, rate())
    }

    private fun generateCloud(request: CloudRequest): File = ElevenLabs(this).audio(
        request.key, request.voiceId, request.text, request.rate, request.previous, request.following,
        request.bookId)

    private fun startCloudPrefetch(request: CloudRequest, token: Int) {
        val next = nextPosition(request.position) ?: return
        val currentBook = book ?: return
        if (currentBook.id != request.bookId) return
        val nextRequest = cloudRequest(currentBook, next, request.key, request.voiceId, request.rate)
        prefetchedRequest = nextRequest
        prefetchedCloud = audioWorker.submit<File> {
            if (token != generation || !playing) throw CancellationException()
            generateCloud(nextRequest)
        }
    }

    private fun playCloudFile(file: File, token: Int, request: CloudRequest?, speed: Float? = null) {
        runCatching {
            val player = MediaPlayer()
            cloudPlayer = player
            player.setAudioAttributes(PlaybackAudioFocus.attributes)
            player.setDataSource(file.path)
            player.setOnPreparedListener {
                if (token == generation && playing) {
                    cloudLoading = false
                    hasNarrated = true
                    it.start()
                    if (speed != null && speed != 1f) runCatching {
                        it.playbackParams = it.playbackParams.setSpeed(speed)
                    }
                    state()
                    if (request != null) startCloudPrefetch(request, token)
                }
            }
            player.setOnCompletionListener { if (token == generation && playing) next(true) }
            player.setOnErrorListener { _, _, _ ->
                val b = book
                if (request == null && token == generation && playing && b != null &&
                    store.narrationSource == "server" && store.serverDeviceFallback) {
                    // Unreadable saved page: read it with the device voice and keep going.
                    cloudPlayer?.release()
                    cloudPlayer = null
                    cloudLoading = false
                    speakDevice(b, token, fallback = true)
                } else cloudFailure(tr("Could not play narration audio"), token)
                true
            }
            player.prepareAsync()
        }.onFailure { cloudFailure(it.message ?: tr("Could not play narration audio"), token) }
    }

    private fun cloudFailure(message: String, token: Int) {
        if (token != generation) return
        pause(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun saveProgress(book: Book) {
        pageStartedAt = 0L
        deviceFraction = null
        sectionFinished = false
        book.sectionCount = sections.size
        book.pagesInSection = sections.getOrNull(book.section)?.pages?.size ?: 0
        book.sectionPages = sections.map { it.pages.size }
        store.update(book)
    }

    private fun finishBook() {
        playing = false
        haltAudio()
        audioFocus.release()
        sectionFinished = true
        book?.let { if (store.autoMarkRead) store.setRead(it.id, true) }
        state()
    }

    private fun currentTimeline(): SectionTimeline {
        val section = book?.section ?: -1
        if (section != timelineSection || timelineRate != rate()) {
            timeline = SectionTimeline(sections.getOrNull(section)?.pages.orEmpty(), rate())
            timelineSection = section
            timelineRate = rate()
        }
        return timeline
    }

    private fun mediaPosition(): Long {
        val b = book ?: return 0
        val clock = currentTimeline()
        if (sectionFinished) return clock.durationMs
        val player = cloudPlayer
        val fraction = if (player != null && !cloudLoading) {
            runCatching { player.currentPosition.toDouble() / player.duration.coerceAtLeast(1) }.getOrDefault(0.0)
        } else deviceFraction ?: if (playing && pageStartedAt > 0) {
            (SystemClock.elapsedRealtime() - pageStartedAt).toDouble() / clock.pageDurationMs(b.page)
        } else 0.0
        return clock.positionMs(b.page, fraction.coerceIn(0.0, 0.99))
    }

    private fun sectionDescription(): String = book?.let { b ->
        val section = sections.getOrNull(b.section) ?: return@let tr("Narration")
        tr("Section {0} of {1} · {2} · Page {3} of {4}", b.section + 1, sections.size, section.title, b.page + 1, section.pages.size)
    } ?: tr("Library")

    private fun loadArtwork(book: Book): Bitmap {
        val file = store.coverFile(book)
        val cover = file?.let {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
                BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
        if (cover != null) return cover
        return Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(37, 66, 65))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(241, 233, 216)
                textSize = 140f
                typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
            }
            canvas.drawText(book.title.take(1).uppercase(), 30f, 195f, paint)
            paint.textSize = 24f
            canvas.drawText(book.title.take(25), 28f, 285f, paint)
            paint.textSize = 18f
            canvas.drawText(book.author.take(32), 28f, 322f, paint)
        }
    }

    private fun narrationError(utteranceId: String?) {
        if (!playing || utteranceId?.substringBefore(':')?.toIntOrNull() != generation) return
        val current = deviceQueue.firstOrNull() ?: return
        if (utteranceId != current.id) {
            deviceQueue.removeAll { it.id == utteranceId }
            completedDeviceIds.remove(utteranceId)
            if (tts.voice?.isNetworkConnectionRequired == true) networkVoiceFailed = true
            return
        }
        if (!networkVoiceFailed && tts.voice?.isNetworkConnectionRequired == true) {
            networkVoiceFailed = true
            speak()
        } else {
            pause()
            state(tr("This voice could not read the page. Try another voice in Narration settings."))
        }
    }

    private fun publishPlayback(error: String? = null) {
        val builder = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
            .setState(if (cloudLoading || (playing && rendererWaiting)) PlaybackState.STATE_BUFFERING
                else if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                // Gapless playback reports its place per piece, so the time isn't run on (and jumped back).
                mediaPosition(), if (playing && !cloudLoading && renderer == null) 1f else 0f, SystemClock.elapsedRealtime())
        builder.addCustomAction("back15", tr("Back 15 s"), R.drawable.ic_car_back15)
        if (store.replayUseful(this)) builder.addCustomAction("replay", tr("Replay"), R.drawable.ic_car_replay)
        builder.addCustomAction("bookmark", tr("Bookmark"), R.drawable.ic_car_bookmark)
        builder.addCustomAction("sleep", sleepLabel(), R.drawable.ic_car_timer)
        builder.addCustomAction("section_start", tr("Start section"), R.drawable.ic_car_section_start)
        if (error != null) builder.setErrorMessage(error)
        session.setPlaybackState(builder.build())
    }

    private fun state(error: String? = null) {
        val b = book
        publishPlayback(error)
        session.setSessionActivity(mediaLaunch())
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, b?.title ?: "FolioTone")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, b?.title ?: "FolioTone")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, sectionDescription())
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, sectionDescription())
            .putString(MediaMetadata.METADATA_KEY_AUTHOR, b?.author.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ALBUM, b?.title.orEmpty())
            .putBitmap(MediaMetadata.METADATA_KEY_ART, artwork)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, currentTimeline().durationMs)
            .build())
        main.removeCallbacks(progressTick)
        if (playing) main.postDelayed(progressTick, 1_000)
        if (hasNarrated) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, notification())
        sendBroadcast(Intent("com.linguareader.STATE").setPackage(packageName)
            .putExtra("playing", playing).putExtra("error", error)
            .putExtra("finished", sectionFinished)
            .putExtra("sleepAt", sleepAt).putExtra("sleepAtSectionEnd", sleepAtSectionEnd)
            .putExtra("id", b?.id).putExtra("section", b?.section ?: 0).putExtra("page", b?.page ?: 0))
    }

    private fun mediaLaunch(): PendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("mediaBookId", book?.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): Notification {
        val launch = mediaLaunch()
        fun action(name: String, label: String, icon: Int) = Notification.Action.Builder(icon, label,
            PendingIntent.getService(this, name.hashCode(), Intent(this, ReaderService::class.java).setAction(name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        val builder = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_foliotone_monochrome).setContentTitle(book?.title ?: "FolioTone")
            .setContentText(sectionDescription())
            .setSubText(book?.author)
            .setLargeIcon(artwork)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action("PREVIOUS", tr("Back"), R.drawable.ic_foliotone_monochrome))
            .addAction(action(if (playing) "PAUSE" else "PLAY", if (playing) tr("Pause") else tr("Play"), R.drawable.ic_foliotone_monochrome))
            .addAction(action("NEXT", tr("Next"), R.drawable.ic_foliotone_monochrome))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        if (store.replayUseful(this)) builder.addAction(action("REPLAY", tr("Replay"), R.drawable.ic_foliotone_monochrome))
        return builder.build()
    }

    // ---- Android Auto (and other media browsers): the car UI ----
    //
    // Media apps can't draw their own car screens; Android Auto builds them from this browse tree. Root
    // children become tabs: Continue (recent books, resume where you left off), Library (cover grid,
    // each book opens its chapters) and Chapters (the current book). Books are found by voice through
    // onPlayFromSearch ("play <title> on FolioTone"). Covers come from CoverProvider; progress shows as a bar via the completion extras.

    private companion object Car {
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val STYLE_SINGLE_ITEM = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
        const val STYLE_LIST = 1
        const val STYLE_GRID = 2
        const val PLAYBACK_STATUS = "android.media.extra.PLAYBACK_STATUS"
        const val STATUS_NOT_PLAYED = 0
        const val STATUS_PARTIAL = 1
        const val STATUS_DONE = 2
        const val COMPLETION = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE"
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // Android's media resumption asks for just the most recent item.
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) {
            return BrowserRoot("recent", Bundle().apply { putBoolean(BrowserRoot.EXTRA_RECENT, true) })
        }
        return BrowserRoot("root", Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(STYLE_BROWSABLE, STYLE_GRID)
            putInt(STYLE_PLAYABLE, STYLE_LIST)
        })
    }

    private fun resourceUri(name: String) = Uri.parse("android.resource://$packageName/drawable/$name")

    private fun tab(id: String, title: String, icon: String, childStyle: Int) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setIconUri(resourceUri(icon))
            .setExtras(Bundle().apply { putInt(STYLE_BROWSABLE, childStyle); putInt(STYLE_PLAYABLE, childStyle) })
            .build(), MediaBrowser.MediaItem.FLAG_BROWSABLE)

    /** Progress extras: a bar in the car plus "not started / in progress / finished". */
    private fun progressExtras(b: Book) = Bundle().apply {
        val percent = store.progressPercent(b)
        putInt(PLAYBACK_STATUS, when { percent >= 100 -> STATUS_DONE; percent > 0 || b.section > 0 || b.page > 0 -> STATUS_PARTIAL; else -> STATUS_NOT_PLAYED })
        putDouble(COMPLETION, percent / 100.0)
    }

    private fun whereIn(b: Book): String {
        val percent = store.progressPercent(b)
        return if (b.sectionCount > 0) tr("Section {0} of {1} · {2}%", b.section + 1, b.sectionCount, percent)
            else b.author.ifBlank { tr("Book") }
    }

    /** Plays [b] from where it was left. */
    private fun resumeItem(b: Book) = MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId("resume:${b.id}")
        .setTitle(b.title).setSubtitle(whereIn(b)).setDescription(b.author)
        .setIconUri(CoverProvider.uri(this, store, b)).setExtras(progressExtras(b)).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE)

    /** Opens [b]'s chapter list. */
    private fun bookItem(b: Book) = MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId("book:${b.id}")
        .setTitle(b.title).setSubtitle(b.author.ifBlank { whereIn(b) })
        .setIconUri(CoverProvider.uri(this, store, b))
        .setExtras(progressExtras(b).apply { putInt(STYLE_PLAYABLE, STYLE_LIST) }).build(), MediaBrowser.MediaItem.FLAG_BROWSABLE)

    /** "Continue from …" first, then every chapter; the current one is marked. */
    private fun chapterItems(b: Book): MutableList<MediaBrowser.MediaItem> {
        val items = mutableListOf(MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId("resume:${b.id}")
            .setTitle(tr("Continue listening")).setSubtitle(whereIn(b))
            .setIconUri(resourceUri("ic_car_continue")).setExtras(progressExtras(b)).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE))
        val chapters = if (book?.id == b.id && sections.isNotEmpty()) sections
            else runCatching { Epub.parse(store.file(b)).sections }.getOrDefault(emptyList())
        chapters.forEachIndexed { index, section ->
            val current = index == b.section
            items += MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId("book:${b.id}:$index")
                .setTitle(section.title)
                .setSubtitle(if (current) tr("Reading now · page {0} of {1}", b.page + 1, section.pages.size)
                    else tr("{0} pages", section.pages.size))
                .setExtras(Bundle().apply {
                    putInt(PLAYBACK_STATUS, when { index < b.section -> STATUS_DONE; current -> STATUS_PARTIAL; else -> STATUS_NOT_PLAYED })
                    if (current) putDouble(COMPLETION, b.page.toDouble() / section.pages.size.coerceAtLeast(1))
                }).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE)
        }
        return items
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        result.detach()
        browseWorker.execute {
            val items: MutableList<MediaBrowser.MediaItem> = runCatching {
                when {
                    parentId == "root" -> mutableListOf(
                        tab("continue", tr("Continue"), "ic_car_continue", STYLE_LIST),
                        tab("library", tr("Library"), "ic_car_library", STYLE_GRID),
                        tab("chapters", tr("Chapters"), "ic_car_chapters", STYLE_LIST),
                        tab("bookmarks", tr("Bookmarks"), "ic_car_bookmark", STYLE_LIST))
                    parentId == "bookmarks" -> (book ?: store.recentBooks(1).firstOrNull())?.let { b ->
                        store.bookmarks(b.id).mapIndexed { index, mark ->
                            MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId("mark:${b.id}:$index")
                                .setTitle(mark.text.ifBlank { tr("Bookmark") })
                                .setSubtitle(tr("Section {0} · page {1}", mark.section + 1, mark.page + 1))
                                .setIconUri(resourceUri("ic_car_bookmark")).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE)
                        }.toMutableList()
                    } ?: mutableListOf()
                    parentId == "recent" -> store.recentBooks(1).map { resumeItem(it) }.toMutableList()
                    parentId == "continue" -> store.recentBooks(8).map { resumeItem(it) }.toMutableList()
                        .ifEmpty { store.books().take(8).map { resumeItem(it) }.toMutableList() }
                    parentId == "library" -> store.books().sortedBy { it.title.lowercase() }.map { bookItem(it) }.toMutableList()
                    parentId == "chapters" -> (book ?: store.recentBooks(1).firstOrNull())?.let { chapterItems(it) } ?: mutableListOf()
                    parentId.startsWith("book:") -> store.books().firstOrNull { it.id == parentId.removePrefix("book:") }
                        ?.let { chapterItems(it) } ?: mutableListOf()
                    else -> mutableListOf()
                }
            }.getOrDefault(mutableListOf())
            result.sendResult(items)
        }
    }

    override fun onDestroy() {
        ttsEpoch++
        haltAudio()
        audioFocus.release()
        audioWorker.shutdownNow()
        browseWorker.shutdown()
        if (::tts.isInitialized) tts.shutdown()
        session.release(); super.onDestroy()
    }
}

/** Silence FolioTone adds after a sentence / paragraph in gapless playback (none inside a split sentence). */
private const val SENTENCE_PAUSE_MS = 200
private const val PARAGRAPH_PAUSE_MS = 450

/** Sentences longer than this are split into clauses of about CLAUSE_TARGET characters (splitLong). */
private const val CLAUSE_LIMIT = 160
private const val CLAUSE_TARGET = 120

/** Clause boundary inside a sentence: comma, semicolon, colon or dash, then space. */
private val CLAUSE_END = Regex("[,;:]\\s+|\\s[—–]\\s+|—")

/** End of a sentence (terminal punctuation, closing quotes, then space) or a paragraph break. */
private val SENTENCE_END = Regex("[.!?…]+[»”\"'’)\\]]*\\s+|\\n\\s*\\n")

/** Words followed by a full stop that does not end the sentence. */
private val ABBREVIATIONS = setOf(
    "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "pp", "vol", "fig",
    "sra", "srta", "sres", "dra", "ud", "uds", "av", "pág", "núm",
    "mme", "mlle", "hr", "bzw", "sig", "dott"
)

