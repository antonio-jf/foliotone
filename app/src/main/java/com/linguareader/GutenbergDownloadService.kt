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
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Owns one user-requested EPUB transfer across activity recreation. */
class GutenbergDownloadService : Service() {
    companion object {
        const val UPDATE = "com.linguareader.GUTENBERG_DOWNLOAD"
        @Volatile var activeId: Int? = null
            private set
        @Volatile var progressText: String? = null
            private set
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val channel = "gutenberg_download"
    @Volatile private var cancelled = false
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(channel, tr("Book downloads"), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "CANCEL" -> {
                if (intent.getIntExtra("id", -1) == activeId) {
                    cancelled = true
                    report(activeId, tr("Cancelling book download…"))
                } else if (activeId == null) stopSelf(startId)
            }
            "DOWNLOAD" -> {
                if (activeId != null) return START_NOT_STICKY
                val id = intent.getIntExtra("id", -1)
                val title = intent.getStringExtra("title").orEmpty()
                val url = intent.getStringExtra("url").orEmpty()
                if (id <= 0 || title.isBlank() || url.isBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                activeId = id
                cancelled = false
                try {
                    val notice = notification(tr("Preparing {0}…", title), false)
                    if (Build.VERSION.SDK_INT >= 29)
                        startForeground(3, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    else startForeground(3, notice)
                } catch (error: RuntimeException) {
                    activeId = null
                    report(id, tr("Could not start download: {0}", error.message ?: tr("Android background limit")))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                report(id, tr("Preparing {0}…", title))
                worker.execute { download(GutenbergBook(id, title, "", emptyList(), url), startId) }
            }
        }
        return START_NOT_STICKY
    }

    private fun download(entry: GutenbergBook, startId: Int) {
        val store = BookStore.get(this)
        var lastStep = -1L
        val result = runCatching {
            store.books().firstOrNull { it.gutenbergId == entry.id } ?: GutenbergCatalog.download(
                entry, store,
                onProgress = { bytes, total ->
                    val step = if (total != null && total > 0) bytes * 20 / total
                        else bytes / (512 * 1024)
                    if (step > lastStep) {
                        lastStep = step
                        val message = if (total != null && total > 0)
                            tr("Downloading {0} · {1}%", entry.title, (bytes * 100 / total).coerceAtMost(100))
                        else tr("Downloading {0} · {1} KB", entry.title, bytes / 1024)
                        report(entry.id, message, bytes, total)
                    }
                },
                shouldContinue = { !cancelled && !Thread.currentThread().isInterrupted }
            )
        }
        if (destroyed) return
        val book = result.getOrNull()
        val message = when {
            book != null -> tr("{0} saved to your library", book.title)
            cancelled || result.exceptionOrNull() is CancellationException -> tr("Book download cancelled")
            else -> tr("Download failed: {0}", GutenbergCatalog.message(result.exceptionOrNull()!!))
        }
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(3, notification(message, true))
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        progressText = null
        activeId = null
        report(entry.id, message, savedBookId = book?.id)
        stopSelf(startId)
    }

    private fun report(id: Int?, message: String, done: Long = 0, total: Long? = null,
                       savedBookId: String? = null) {
        progressText = message
        sendBroadcast(Intent(UPDATE).setPackage(packageName)
            .putExtra("id", id ?: -1).putExtra("message", message)
            .putExtra("savedBookId", savedBookId))
        if (activeId != null) runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(3,
                notification(message, false, done, total))
        }
    }

    private fun cancelAction(): Notification.Action = Notification.Action.Builder(
        R.drawable.ic_foliotone_monochrome, tr("Cancel"),
        PendingIntent.getService(this, 5,
            Intent(this, GutenbergDownloadService::class.java).setAction("CANCEL")
                .putExtra("id", activeId ?: -1),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    private fun notification(message: String, finished: Boolean, done: Long = 0,
                             total: Long? = null): Notification =
        Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_foliotone_monochrome)
            .setContentTitle(if (finished) tr("Project Gutenberg") else tr("Saving a book"))
            .setContentText(message)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setContentIntent(PendingIntent.getActivity(this, 6,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .apply {
                if (!finished) addAction(cancelAction())
                if (total != null && total > 0)
                    setProgress(100, (done * 100 / total).toInt().coerceIn(0, 100), false)
            }.build()

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelled = true
        report(activeId, tr("Download paused by Android's background time limit"))
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        cancelled = true
        worker.shutdownNow()
        activeId = null
        progressText = null
        super.onDestroy()
    }
}
