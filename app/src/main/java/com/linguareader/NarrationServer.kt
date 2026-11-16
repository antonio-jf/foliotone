package com.linguareader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.JsonWriter
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Address and Modal proxy-auth token of the user's own narration server (tts-server/app.py). */
data class ServerConfig(val url: String, val tokenId: String, val tokenSecret: String) {
    val host: String get() = runCatching { URL(url).host }.getOrDefault(url)
}

/** Server settings stay in private, non-backed-up storage encrypted by Android Keystore. */
class ServerCredentials(private val context: Context) {
    private val file get() = File(context.noBackupFilesDir, "narration_server.json")
    private val alias = "foliotone_narration_server"

    fun read(): ServerConfig? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        ServerConfig(json.getString("url"), json.optString("tokenId"), json.optString("tokenSecret"))
    }.getOrNull()

    fun save(config: ServerConfig) {
        val plain = JSONObject().put("url", config.url).put("tokenId", config.tokenId)
            .put("tokenSecret", config.tokenSecret).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val staged = File(context.noBackupFilesDir, "narration_server.json.tmp")
        staged.writeBytes(encrypted)
        if (!staged.renameTo(file)) { staged.delete(); error(tr("Could not save the server settings")) }
    }

    fun clear() {
        file.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }

    private fun secretKey(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}

/** Page audio saved on the device: noBackupFilesDir/server_audio/<bookId>/<key>.mp3. */
object ServerAudio {
    /** Part of every key, so audio from another model or voice is never mixed into a book. */
    const val MODEL_TAG = "voxtral-4b-2603"

    fun key(voice: String, text: String): String =
        MessageDigest.getInstance("SHA-256").digest("$MODEL_TAG|$voice|$text".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun root(context: Context) = File(context.noBackupFilesDir, "server_audio")

    private fun folder(context: Context, bookId: String): File {
        require(bookId.matches(Regex("[a-f0-9]{64}"))) { "Invalid book" }
        return File(root(context), bookId)
    }

    fun file(context: Context, bookId: String, key: String): File = File(folder(context, bookId), "$key.mp3")

    fun isSaved(context: Context, bookId: String, key: String): Boolean =
        file(context, bookId, key).let { it.isFile && it.length() > 0 }

    /** Saved and total page counts for the voice, in reading order. */
    fun savedPages(context: Context, bookId: String, sections: List<Section>, voice: String): Pair<Int, Int> {
        val pages = sections.flatMap { it.pages }
        return pages.count { isSaved(context, bookId, key(voice, it)) } to pages.size
    }

    fun hasAny(context: Context, bookId: String): Boolean =
        folder(context, bookId).listFiles()?.any { it.isFile && it.length() > 0 } == true

    fun bytesUsed(context: Context): Long =
        root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun remove(context: Context, bookId: String) {
        val directory = folder(context, bookId)
        if (directory.exists() && !directory.deleteRecursively()) error(tr("Could not remove all saved audio"))
    }

    fun clearAll(context: Context) { root(context).deleteRecursively() }

    /** Writes through a temporary file so a cancelled download never leaves a partial page. */
    fun store(context: Context, bookId: String, key: String, write: (File) -> Unit) {
        val target = file(context, bookId, key)
        target.parentFile?.mkdirs()
        val staged = File.createTempFile("page-", ".tmp", target.parentFile)
        try {
            write(staged)
            require(staged.length() > 0) { tr("The server returned empty audio") }
            if (!staged.renameTo(target)) error(tr("Could not save page audio"))
        } finally { staged.delete() }
    }
}

data class ServerInfo(val model: String, val voices: List<String>, val defaults: Map<String, String>,
                      val samples: Boolean, val maxPages: Int, val maxPageChars: Int)

data class ServerJob(val id: String, val state: String, val total: Int, val done: Int, val failed: Int,
                     val error: String?, val doneKeys: List<String>, val next: Int,
                     val charsPerSecond: Double?) {
    val finished get() = state == "done" || state == "failed" || state == "cancelled"
}

class ServerJobGone : IllegalStateException(tr("The server no longer has this job"))

/** HTTPS client for the FolioTone narration server. Nothing is built into the APK. */
class NarrationServer(private val context: Context, private val config: ServerConfig) {
    companion object {
        /** Accepts the URL Modal prints after `modal deploy`, with or without a trailing slash. */
        fun normalizeUrl(value: String): String {
            var url = value.trim().trimEnd('/')
            if (url.isNotEmpty() && !url.contains("://")) url = "https://$url"
            require(url.startsWith("https://")) { tr("Use the https:// address of your server") }
            require(runCatching { URL(url).host }.getOrNull().orEmpty().isNotBlank()) { tr("Enter a valid server address") }
            return url
        }

        fun message(error: Throwable): String = when (error) {
            is UnknownHostException -> tr("Cannot reach the narration server. Check the address and your connection.")
            is ConnectException -> tr("Cannot connect to the narration server.")
            is SocketTimeoutException -> tr("The narration server timed out. Try again.")
            is ServerJobGone -> tr("The server no longer has this job.")
            else -> error.message ?: tr("Narration server request failed.")
        }

        val LANGUAGE_NAMES = mapOf("en" to "English", "it" to "Italian", "fr" to "French", "de" to "German",
            "es" to "Spanish", "pt" to "Portuguese", "nl" to "Dutch", "ar" to "Arabic", "hi" to "Hindi")

        /** "it_female" → "Italian · female"; "neutral_male" → "Neutral · male". */
        fun voiceLabel(voice: String): String {
            val parts = voice.split('_')
            val style = tr(LANGUAGE_NAMES[parts.first()] ?: parts.first().replaceFirstChar { it.uppercase() })
            return if (parts.size > 1) "$style · ${parts.drop(1).joinToString(" ") { tr(it) }}" else style
        }

        /** Voices native to the language first, then the language-neutral ones, then the rest. */
        fun rankVoices(voices: List<String>, language: String): List<String> = voices.sortedWith(
            compareBy<String> {
                when {
                    it.startsWith("${language}_") -> 0
                    it.substringBefore('_') !in LANGUAGE_NAMES -> 1
                    else -> 2
                }
            }.thenBy { it })
    }

    fun health(): ServerInfo {
        val json = JSONObject(text(connect("/v1/health")))
        val voices = json.optJSONArray("voices")?.let { array -> (0 until array.length()).map { array.getString(it) } }
            .orEmpty()
        val defaults = json.optJSONObject("defaults")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            .orEmpty()
        check(voices.isNotEmpty()) { tr("The server reported no voices") }
        return ServerInfo(json.optString("model"), voices, defaults, json.optBoolean("samples"),
            json.optInt("max_pages", 15_000), json.optInt("max_page_chars", 3_000))
    }

    /** Starts generating [pages] (key to text). Returns the job id. */
    fun submit(bookId: String, voice: String, pages: List<Pair<String, String>>): String {
        val connection = connect("/v1/jobs")
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setChunkedStreamingMode(0)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            JsonWriter(OutputStreamWriter(connection.outputStream, Charsets.UTF_8)).use { writer ->
                writer.beginObject()
                writer.name("book").value(bookId)
                writer.name("voice").value(voice)
                writer.name("pages").beginArray()
                pages.forEach { (key, text) -> writer.beginObject().name("key").value(key).name("text").value(text).endObject() }
                writer.endArray()
                writer.endObject()
            }
            return JSONObject(text(connection)).getString("id")
        } finally { connection.disconnect() }
    }

    fun job(id: String, after: Int): ServerJob {
        val json = JSONObject(text(connect("/v1/jobs/${path(id)}?after=$after"), missingJob = true))
        val keys = json.optJSONArray("done_keys")
        return ServerJob(id, json.optString("state", "queued"), json.optInt("total"), json.optInt("done"),
            json.optInt("failed"), json.optString("error").takeIf { it.isNotBlank() && it != "null" },
            keys?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty(),
            json.optInt("next", after), json.optDouble("chars_per_second").takeIf { !it.isNaN() })
    }

    fun download(jobId: String, bookId: String, key: String) {
        val connection = connect("/v1/jobs/${path(jobId)}/audio/${path(key)}")
        try {
            if (connection.responseCode !in 200..299) throw IOException(failure(connection, missingJob = false))
            ServerAudio.store(context, bookId, key) { target ->
                var count = 0L
                connection.inputStream.use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= 20L * 1024 * 1024) { tr("Page audio was too large") }
                        output.write(buffer, 0, read)
                    }
                } }
            }
        } finally { connection.disconnect() }
    }

    fun delete(jobId: String) {
        val connection = connect("/v1/jobs/${path(jobId)}")
        try {
            connection.requestMethod = "DELETE"
            connection.responseCode
        } finally { connection.disconnect() }
    }

    /** Pre-rendered voice sample, cached in the app's cache folder. */
    fun sample(voice: String, language: String): File {
        val target = File(File(context.cacheDir, "server_samples"), "${language}_$voice.mp3")
        if (target.isFile && target.length() > 0) return target
        val connection = connect("/v1/voices/${path(voice)}/sample?language=${path(language)}")
        try {
            check(connection.responseCode in 200..299) {
                if (connection.responseCode == 404) tr("No preview is available for this voice yet")
                else failure(connection, missingJob = false)
            }
            target.parentFile?.mkdirs()
            val staged = File.createTempFile("sample-", ".tmp", target.parentFile)
            try {
                connection.inputStream.use { input -> staged.outputStream().use { input.copyTo(it) } }
                if (!staged.renameTo(target)) error(tr("Could not save the preview"))
            } finally { staged.delete() }
            return target
        } finally { connection.disconnect() }
    }

    private fun path(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun connect(path: String): HttpURLConnection =
        (URL(config.url + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = false
            if (config.tokenId.isNotBlank()) setRequestProperty("Modal-Key", config.tokenId)
            if (config.tokenSecret.isNotBlank()) setRequestProperty("Modal-Secret", config.tokenSecret)
            setRequestProperty("Accept", "application/json, audio/mpeg")
        }

    private fun text(connection: HttpURLConnection, missingJob: Boolean = false): String = try {
        val code = connection.responseCode
        if (code !in 200..299) {
            if (missingJob && code == 404) throw ServerJobGone()
            throw IOException(failure(connection, missingJob))
        }
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }

    private fun failure(connection: HttpURLConnection, missingJob: Boolean): String {
        val code = connection.responseCode
        val detail = runCatching {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText().take(2048) }.orEmpty()
            JSONObject(body).opt("detail")?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return when (code) {
            401, 403 -> tr("The server rejected the access token (HTTP {0}). Check the token ID and secret.", code)
            404 -> if (missingJob) tr("The server no longer has this job") else
                tr("Not found on the server (HTTP 404). Check the address ends with .modal.run and has no extra path.")
            in 300..399 -> tr("The server redirected the request (HTTP {0}). Check the address.", code)
            in 500..599 -> tr("The narration server had a problem (HTTP {0}). Try again shortly.", code)
            else -> tr("Narration server request failed (HTTP {0}){1}", code, detail?.let { ": $it" } ?: "")
        }
    }
}
