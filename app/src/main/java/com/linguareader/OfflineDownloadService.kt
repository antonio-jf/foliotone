package com.linguareader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

/** Keeps a user-requested book download running while the reader is in the background. */
class OfflineDownloadService : Service() {
    companion object {
        const val UPDATE = "com.linguareader.OFFLINE_DOWNLOAD"
        @Volatile var activeBookId: String? = null
            private set
        @Volatile var progressText: String? = null
            private set
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val channel = "offline_narration"
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(channel, tr("Offline book audio"), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "CANCEL" -> {
                if (intent.getStringExtra("id") == activeBookId) {
                    cancelled = true
                    report(activeBookId, tr("Cancelling download…"))
                } else if (activeBookId == null) stopSelf(startId)
            }
            "DOWNLOAD" -> {
                val id = intent.getStringExtra("id") ?: return START_NOT_STICKY
                if (activeBookId != null) return START_NOT_STICKY
                activeBookId = id
                cancelled = false
                try {
                    val notice = notification(tr("Preparing offline audio…"), false)
                    if (Build.VERSION.SDK_INT >= 29)
                        startForeground(2, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    else startForeground(2, notice)
                } catch (error: RuntimeException) {
                    activeBookId = null
                    report(id, tr("Could not start offline download: {0}", error.message ?: tr("Android background limit")))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                report(id, tr("Preparing offline audio…"))
                val source = intent.getStringExtra("source") ?: BookStore.get(this).narrationSource
                // Optional page range in reading order: [from, to). Defaults to the whole book.
                val from = intent.getIntExtra("from", 0)
                val to = intent.getIntExtra("to", -1)
                // Regenerate: throw away the saved audio of these pages and create it again.
                val regenerate = intent.getBooleanExtra("regenerate", false)
                worker.execute {
                    if (source == "server") downloadFromServer(id, from, to, regenerate)
                    else download(id, from, to, regenerate)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Page indices [from, to) of [size] book pages, clamped; `to < 0` means the end of the book. */
    private fun range(from: Int, to: Int, size: Int): IntRange {
        val start = from.coerceIn(0, size)
        val end = (if (to < 0) size else to).coerceIn(start, size)
        return start until end
    }

    private fun scope(range: IntRange, size: Int) =
        if (range.first == 0 && range.last == size - 1) "" else tr(" in the selected part")

    private fun download(id: String, from: Int, to: Int, regenerate: Boolean = false) {
        finish(id, runCatching {
            val store = BookStore.get(this)
            val book = store.books().firstOrNull { it.id == id } ?: error(tr("Book is no longer in the library"))
            val key = ElevenLabsKey(this).read() ?: error(tr("Connect ElevenLabs in Listening settings"))
            val voice = store.elevenVoiceId(NarrationVoices.locale(book.language).language)
                ?: error(tr("Choose an ElevenLabs voice for this book"))
            val rate = store.speechRateFor(book.id)
            val sections = Epub.parse(store.file(book)).sections
            val pages = sections.flatMap { it.pages }
            val eleven = ElevenLabs(this)
            val wanted = range(from, to, pages.size)
            val where = scope(wanted, pages.size)
            fun isSaved(index: Int) = eleven.isPageSaved(id, key, voice, pages[index], rate,
                pages.getOrNull(index - 1), pages.getOrNull(index + 1))
            if (regenerate) for (index in wanted) eleven.forgetPage(id, key, voice, pages[index], rate,
                pages.getOrNull(index - 1), pages.getOrNull(index + 1))
            val total = wanted.count()
            var saved = wanted.count(::isSaved)
            report(id, tr("{0} of {1} pages saved{2}", saved, total, where), saved, total)
            for (index in wanted) {
                if (cancelled) return@runCatching tr("Download paused · {0} of {1} pages saved{2}", saved, total, where)
                if (isSaved(index)) continue
                eleven.downloadPage(id, key, voice, pages[index], rate,
                    pages.getOrNull(index - 1), pages.getOrNull(index + 1))
                saved++
                report(id, tr("{0} of {1} pages saved{2}", saved, total, where), saved, total)
            }
            check(wanted.all(::isSaved)) { tr("Some pages could not be saved") }
            if (regenerate) tr("Page audio regenerated") else tr("Offline audio ready · {0} pages saved{1}", total, where)
        }) { error ->
            if (cancelled) tr("Download paused · saved pages are kept")
            else tr("Offline download stopped: {0}", ElevenLabs.message(error))
        }
    }

    /**
     * Sends the pages that are not on this device yet to the user's narration server, then
     * downloads each page as soon as the server finishes it. Generation continues on the server
     * if this service stops; the saved job id lets the next download pick it up again.
     */
    private fun downloadFromServer(id: String, from: Int, to: Int, regenerate: Boolean = false) {
        var serverStatus = ""
        finish(id, runCatching {
            val store = BookStore.get(this)
            val book = store.books().firstOrNull { it.id == id } ?: error(tr("Book is no longer in the library"))
            val config = ServerCredentials(this).read() ?: error(tr("Connect your narration server in Listening settings"))
            val voice = store.serverVoice(NarrationVoices.locale(book.language).language)
            val allPages = Epub.parse(store.file(book)).sections.flatMap { it.pages }
            val wanted = range(from, to, allPages.size)
            val where = scope(wanted, allPages.size)
            val pages = allPages.subList(wanted.first, wanted.last + 1)
            val total = pages.size
            // A regeneration is a separate one-off job: it must not replace a paused book download.
            if (regenerate) pages.forEach { ServerAudio.file(this, id, ServerAudio.key(voice, it)).delete() }
            else store.setServerRange(id, wanted.first, wanted.last + 1)
            val texts = LinkedHashMap<String, String>()
            val occurrences = HashMap<String, Int>()
            pages.forEach { page ->
                val key = ServerAudio.key(voice, page)
                texts.putIfAbsent(key, page)
                occurrences[key] = (occurrences[key] ?: 0) + 1
            }
            fun missing() = texts.filterKeys { !ServerAudio.isSaved(this, id, it) }
            var saved = occurrences.entries.sumOf { (key, count) -> if (ServerAudio.isSaved(this, id, key)) count else 0 }
            report(id, tr("{0} of {1} pages saved{2}", saved, total, where), saved, total)
            if (missing().isEmpty()) {
                if (!regenerate) store.clearServerJob(id)
                return@runCatching tr("Offline audio ready · {0} pages saved{1}", total, where)
            }
            val server = NarrationServer(this, config)
            fun submit(): String {
                val pending = missing()
                serverStatus = tr("Sending {0} pages to your narration server…", pending.size)
                report(id, serverStatus, saved, total)
                return server.submit(id, voice, pending.toList()).also {
                    if (!regenerate) store.setServerJob(id, it, voice)
                }
            }
            var job = (if (regenerate) null else store.serverJob(id)?.takeIf { it.second == voice }?.first) ?: submit()
            var after = 0
            var networkFailures = 0
            var downloadFailures = 0
            var lastState = ""
            var resubmitted = false
            while (true) {
                if (cancelled) return@runCatching tr("Download paused · {0} of {1} pages saved. Your server keeps generating; resume to fetch the rest.", saved, total)
                val status = try {
                    server.job(job, after).also { networkFailures = 0 }
                } catch (gone: ServerJobGone) {
                    if (!regenerate) store.clearServerJob(id)
                    if (missing().isEmpty()) break
                    job = submit(); after = 0
                    continue
                } catch (error: java.io.IOException) {
                    if (++networkFailures >= 8) throw error
                    report(id, tr("Connection problem, retrying… ({0} of {1} pages saved)", saved, total), saved, total)
                    pause(networkFailures * 5_000L)
                    continue
                }
                var downloadFailed = false
                for (key in status.doneKeys) {
                    if (cancelled) break
                    if (key in texts && !ServerAudio.isSaved(this, id, key)) {
                        try {
                            server.download(job, id, key)
                            downloadFailures = 0
                        } catch (error: java.io.IOException) {
                            if (++downloadFailures >= 8) throw error
                            downloadFailed = true
                            break
                        }
                        saved += occurrences[key] ?: 1
                        report(id, tr("{0} of {1} pages saved{2}", saved, total, where), saved, total)
                    }
                }
                if (downloadFailed) {
                    // Keep `after` unchanged: the next poll lists these pages again.
                    report(id, tr("Connection problem, retrying… ({0} of {1} pages saved)", saved, total), saved, total)
                    pause(downloadFailures * 5_000L)
                    continue
                }
                after = status.next
                if (status.state != lastState || status.doneKeys.isEmpty()) {
                    lastState = status.state
                    serverStatus = when (status.state) {
                        "queued" -> tr("Starting your narration server… The first start can take a few minutes.")
                        "running" -> tr("Generating on your server · {0} of {1} pages saved", saved, total) +
                            (status.charsPerSecond?.takeIf { it > 0 }?.let { speed ->
                                val left = missing().values.sumOf { it.length } / speed / 60
                                if (left >= 1) tr(" · about {0} min left", left.toInt() + 1) else ""
                            } ?: "")
                        else -> tr("Saving the last pages…")
                    }
                    report(id, serverStatus, saved, total)
                }
                if (status.finished && status.doneKeys.isEmpty()) {
                    // A resumed job may cover a different part of the book: ask once more for what is
                    // still missing, unless the server already tried and failed some of these pages.
                    if (cancelled || status.failed > 0 || resubmitted || missing().isEmpty()) break
                    resubmitted = true
                    runCatching { server.delete(job) }
                    job = submit(); after = 0; lastState = ""
                    continue
                }
                if (status.doneKeys.isEmpty()) pause(if (status.state == "queued") 10_000L else 5_000L)
            }
            if (cancelled) return@runCatching tr("Download paused · {0} of {1} pages saved{2}", saved, total, where)
            runCatching { server.delete(job) }
            if (!regenerate) store.clearServerJob(id)
            val left = missing().size
            check(left == 0) { tr("{0} pages could not be generated. Download again to retry them.", left) }
            if (regenerate) tr("Page audio regenerated") else tr("Offline audio ready · {0} pages saved{1}", total, where)
        }) { error ->
            if (cancelled) tr("Download paused · saved pages are kept")
            else tr("Offline download stopped: {0}", NarrationServer.message(error))
        }
    }

    /** Waits up to [millis] but returns as soon as the user pauses the download. */
    private fun pause(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (!cancelled && System.currentTimeMillis() < until) Thread.sleep(250)
    }

    private fun finish(id: String, result: Result<String>, failure: (Throwable) -> String) {
        val message = result.getOrElse(failure)
        // Keep the library badge in step with what is now on the device.
        runCatching {
            val store = BookStore.get(this)
            store.books().firstOrNull { it.id == id }?.let { book ->
                NarrationLibrary.recount(this, book, Epub.parse(store.file(book)).sections)
            }
        }
        activeBookId = null
        report(id, message)
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(2, notification(message, true))
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        progressText = null
        stopSelf()
    }

    private fun report(id: String?, message: String, done: Int = 0, total: Int = 0) {
        progressText = message
        sendBroadcast(Intent(UPDATE).setPackage(packageName)
            .putExtra("id", id).putExtra("message", message))
        if (activeBookId != null) {
            val notice = notification(message, false).let { original ->
                if (total == 0) original else Notification.Builder(this, channel)
                    .setSmallIcon(R.drawable.ic_foliotone_monochrome)
                    .setContentTitle(tr("Saving book audio"))
                    .setContentText(message)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setProgress(total, done, false)
                    .setContentIntent(PendingIntent.getActivity(this, 4,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .addAction(cancelAction())
                    .build()
            }
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(2, notice)
            }
        }
    }

    private fun cancelAction(): Notification.Action {
        val intent = Intent(this, OfflineDownloadService::class.java).setAction("CANCEL")
            .putExtra("id", activeBookId)
        return Notification.Action.Builder(R.drawable.ic_foliotone_monochrome, tr("Pause"),
            PendingIntent.getService(this, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    }

    private fun notification(message: String, finished: Boolean): Notification =
        Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_foliotone_monochrome)
            .setContentTitle(if (finished) tr("Offline book audio") else tr("Saving book audio"))
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setContentIntent(PendingIntent.getActivity(this, 4,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .apply { if (!finished) addAction(cancelAction()) }
            .build()

    override fun onDestroy() {
        cancelled = true
        worker.shutdownNow()
        activeBookId = null
        progressText = null
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled = true
        report(activeBookId, tr("Download paused by Android's background time limit"))
        stopSelf()
    }
}
