package com.linguareader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from GitHub releases (see .github/workflows/release.yml).
 *
 * The release tag is v<VERSION>.<run number> and the run number is the APK's versionCode, so the tag
 * alone says whether a release is newer. A newer APK is downloaded into private storage, checked
 * against the release's SHA256SUMS.txt and against the installed app's signing key, and installed
 * through PackageInstaller. Android asks the user to confirm unless FolioTone itself installed the
 * current version (Android 12+), in which case later updates install without a prompt.
 */
object AppUpdates {
    const val REPO = "antonio-jf/foliotone"
    const val EXTRA_INSTALL = "installUpdate"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val JOB_ID = 4107
    private const val CHANNEL = "app_updates"
    private const val NOTIFICATION_ID = 41
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val OPEN_CHECK_AFTER_MS = 6 * 60 * 60 * 1000L

    data class Release(val code: Long, val name: String, val apkUrl: String, val apkName: String,
                       val sumsUrl: String?)
    data class Ready(val code: Long, val name: String, val file: File)

    sealed class Result {
        data class UpToDate(val name: String) : Result()
        data class Downloaded(val ready: Ready) : Result()
        data class Failed(val message: String) : Result()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    fun isAutomatic(context: Context) = prefs(context).getBoolean("automatic", true)
    fun setAutomatic(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("automatic", on).apply()
        schedule(context)
    }

    /** Builds from Android Studio are debuggable: they only check when asked, so a dev build is not replaced. */
    fun isDevelopmentBuild(context: Context) =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun packageInfo(context: Context, flags: Int = 0): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, flags)

    @Suppress("DEPRECATION")
    fun installedCode(context: Context): Long = packageInfo(context).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }

    fun installedName(context: Context): String = packageInfo(context).versionName ?: "?"

    /** The latest published release. Network; call off the main thread. */
    fun latest(): Release {
        val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "FolioTone")
        }
        try {
            when (connection.responseCode) {
                200 -> Unit
                404 -> throw IOException(tr("No release has been published yet."))
                403, 429 -> throw IOException(tr("GitHub is limiting requests. Try again later."))
                else -> throw IOException(tr("GitHub answered {0}.", connection.responseCode))
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val name = tag.removePrefix("v")
            val code = name.substringAfterLast('.').toLongOrNull()
                ?: throw IOException(tr("Unrecognised release name {0}.", tag))
            val assets = json.getJSONArray("assets")
            var apk: Pair<String, String>? = null
            var sums: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.getString("name")
                val url = asset.getString("browser_download_url")
                if (assetName.endsWith(".apk")) apk = assetName to url
                if (assetName == "SHA256SUMS.txt") sums = url
            }
            val (apkName, apkUrl) = apk ?: throw IOException(tr("Release {0} has no APK.", tag))
            return Release(code, name, apkUrl, apkName, sums)
        } finally {
            connection.disconnect()
        }
    }

    /** Checks GitHub and, when a newer release exists, downloads it. Network; call off the main thread. */
    fun check(context: Context): Result = try {
        prefs(context).edit().putLong("lastCheck", System.currentTimeMillis()).apply()
        val release = latest()
        if (release.code <= installedCode(context)) {
            clearReady(context)
            Result.UpToDate(installedName(context))
        } else {
            val ready = readyUpdate(context)?.takeIf { it.code == release.code } ?: download(context, release)
            Result.Downloaded(ready)
        }
    } catch (error: Exception) {
        Result.Failed(error.message ?: error.javaClass.simpleName)
    }

    fun checkIsDue(context: Context) = isAutomatic(context) && !isDevelopmentBuild(context) &&
        System.currentTimeMillis() - prefs(context).getLong("lastCheck", 0) > OPEN_CHECK_AFTER_MS

    private fun download(context: Context, release: Release): Ready {
        val folder = File(context.filesDir, "updates").apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val partial = File(folder, "${release.apkName}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("User-Agent", "FolioTone")
        }
        try {
            if (connection.responseCode != 200) throw IOException(tr("Download failed ({0}).", connection.responseCode))
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        release.sumsUrl?.let { url ->
            val expected = URL(url).openStream().bufferedReader().use { it.readText() }.lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it[1].removePrefix("*") == release.apkName }?.get(0)
            if (expected != null && !expected.equals(hash, ignoreCase = true)) {
                partial.delete()
                throw IOException(tr("The downloaded update is damaged. It will be downloaded again next time."))
            }
        }
        if (!sameSigner(context, partial)) {
            partial.delete()
            throw IOException(tr("Version {0} is signed with a different key than the installed app, so Android cannot install it as an update. Install it once by hand (see Releases in the README).", release.name))
        }
        val file = File(folder, release.apkName)
        if (!partial.renameTo(file)) throw IOException(tr("Could not save the update."))
        prefs(context).edit()
            .putLong("readyCode", release.code).putString("readyName", release.name)
            .putString("readyPath", file.path).apply()
        return Ready(release.code, release.name, file)
    }

    /** A downloaded update that is newer than the installed app, if any. */
    fun readyUpdate(context: Context): Ready? {
        val stored = prefs(context)
        val path = stored.getString("readyPath", null) ?: return null
        val code = stored.getLong("readyCode", 0)
        val file = File(path)
        if (!file.isFile || code <= installedCode(context)) { clearReady(context); return null }
        return Ready(code, stored.getString("readyName", null) ?: code.toString(), file)
    }

    private fun clearReady(context: Context) {
        prefs(context).edit().remove("readyPath").remove("readyCode").remove("readyName").apply()
        File(context.filesDir, "updates").listFiles()?.forEach { it.delete() }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): Set<String> = when {
        info == null -> emptySet()
        Build.VERSION.SDK_INT >= 28 -> info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        }.orEmpty().map { it.toCharsString() }.toSet()
        else -> info.signatures.orEmpty().map { it.toCharsString() }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(context: Context, apk: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val downloaded = signers(archive)
        // Some Android versions do not report an archive's certificates; the installer still checks them.
        if (downloaded.isEmpty()) return true
        return downloaded.intersect(signers(packageInfo(context, flags))).isNotEmpty()
    }

    /**
     * Hands the APK to Android's installer. Returns false (after opening the setting) when FolioTone
     * is not yet allowed to install apps; the user taps Install again after allowing it.
     */
    fun install(context: Context, ready: Ready): Boolean {
        val manager = context.packageManager
        if (!manager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val installer = manager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("foliotone.apk", 0, ready.file.length()).use { output ->
                ready.file.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // Mutable: the installer adds the result to this intent (explicit, so allowed on Android 14).
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutable
            val result = PendingIntent.getBroadcast(context, sessionId,
                Intent(context, UpdateInstallReceiver::class.java), flags)
            session.commit(result.intentSender)
        }
        return true
    }

    /** Keeps a twice-daily background check scheduled while automatic updates are on. */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!isAutomatic(context) || isDevelopmentBuild(context)) { scheduler.cancel(JOB_ID); return }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        // A refused job must never stop the app from opening; checks on open still run.
        runCatching {
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(CHECK_INTERVAL_MS)
                .build())
        }
    }

    /** One notification per downloaded version; tapping it opens the app's install prompt. */
    fun notifyReady(context: Context, ready: Ready) {
        val stored = prefs(context)
        if (stored.getLong("notifiedCode", 0) == ready.code) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, tr("App updates"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_INSTALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_foliotone_monochrome)
            .setContentTitle(tr("FolioTone {0} is ready", ready.name))
            .setContentText(tr("Tap to install the update."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
        stored.edit().putLong("notifiedCode", ready.code).apply()
    }

    /** Whether the in-app "ready to install" prompt was already shown for this version. */
    fun wasOffered(context: Context, ready: Ready) = prefs(context).getLong("offeredCode", 0) == ready.code
    fun markOffered(context: Context, ready: Ready) = prefs(context).edit().putLong("offeredCode", ready.code).apply()
}

/** The periodic background check scheduled by [AppUpdates.schedule]. */
class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        I18n.init(this)
        Thread {
            val result = AppUpdates.check(this)
            if (result is AppUpdates.Result.Downloaded) AppUpdates.notifyReady(this, result.ready)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

/** Receives the installer's answer: shows Android's confirmation, or explains a failure. */
class UpdateInstallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        I18n.init(context)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> Toast.makeText(context, tr("Update not installed: {0}",
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status.toString()), Toast.LENGTH_LONG).show()
        }
    }
}
