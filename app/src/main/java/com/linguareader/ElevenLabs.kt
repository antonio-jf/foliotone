package com.linguareader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
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

/** The user's key stays in private, non-backed-up storage encrypted by Android Keystore. */
class ElevenLabsKey(private val context: Context) {
    private val file get() = File(context.noBackupFilesDir, "elevenlabs.key")
    private val alias = "foliotone_elevenlabs_key"

    fun read(): String? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    fun save(value: String) {
        require(value.isNotBlank()) { tr("Enter an ElevenLabs API key") }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        val staged = File(context.noBackupFilesDir, "elevenlabs.key.tmp")
        staged.writeBytes(encrypted)
        if (!staged.renameTo(file)) { staged.delete(); error(tr("Could not save the API key")) }
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

data class ElevenVoice(val id: String, val name: String, val detail: String, val languages: Set<String>)

/** Small HTTPS client for the user's own ElevenLabs account. No credential is built into the APK. */
class ElevenLabs(private val context: Context) {
    companion object {
        private val generationLocks = Array(64) { Any() }
        private fun generationLock(name: String): Any =
            generationLocks[(name.hashCode() and Int.MAX_VALUE) % generationLocks.size]
        /** Accept a bare key or a copied xi-api-key header without changing the secret itself. */
        fun normalizeKey(value: String): String {
            var key = value.trim()
            if (key.startsWith("xi-api-key:", ignoreCase = true)) key = key.substringAfter(':').trim()
            if (key.length >= 2 && ((key.first() == '"' && key.last() == '"') ||
                    (key.first() == '\'' && key.last() == '\''))) key = key.substring(1, key.lastIndex)
            return key.trim()
        }

        fun message(error: Throwable): String = when (error) {
            is UnknownHostException -> tr("Cannot reach ElevenLabs. Check your internet connection.")
            is SocketTimeoutException -> tr("ElevenLabs timed out. Try again.")
            else -> error.message ?: tr("ElevenLabs request failed.")
        }
    }
    private val cache = File(context.noBackupFilesDir, "eleven_audio").also { it.mkdirs() }
    private val offline = File(context.noBackupFilesDir, "offline_audio").also { it.mkdirs() }

    private fun audioName(key: String, voiceId: String, text: String, rate: Float,
                          previousText: String?, nextText: String?): String {
        val signature = "eleven_multilingual_v2|$key|$voiceId|$rate|$text|$previousText|$nextText"
        return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".mp3"
    }

    private fun offlineFile(bookId: String, name: String): File {
        require(bookId.matches(Regex("[a-f0-9]{64}"))) { "Invalid book" }
        return File(File(offline, bookId), name)
    }

    fun savedPages(bookId: String, sections: List<Section>, key: String, voiceId: String,
                   rate: Float): Pair<Int, Int> {
        val pages = sections.flatMap { it.pages }
        val saved = pages.indices.count { index ->
            isPageSaved(bookId, key, voiceId, pages[index], rate,
                pages.getOrNull(index - 1), pages.getOrNull(index + 1))
        }
        return saved to pages.size
    }

    fun isPageSaved(bookId: String, key: String, voiceId: String, text: String, rate: Float,
                    previousText: String?, nextText: String?): Boolean {
        val file = offlineFile(bookId, audioName(key, voiceId, text, rate, previousText, nextText))
        return file.isFile && file.length() > 0
    }

    /** Deletes a page's cached and offline audio so the next request generates it again. */
    fun forgetPage(bookId: String, key: String, voiceId: String, text: String, rate: Float,
                   previousText: String?, nextText: String?) {
        val name = audioName(key, voiceId, text, rate, previousText, nextText)
        File(cache, name).delete()
        offlineFile(bookId, name).delete()
    }

    fun downloadPage(bookId: String, key: String, voiceId: String, text: String, rate: Float,
                     previousText: String?, nextText: String?): File {
        check(ElevenLabsKey(context).read() == key) { tr("ElevenLabs API key changed during download") }
        val name = audioName(key, voiceId, text, rate, previousText, nextText)
        return synchronized(generationLock(name)) {
            val target = offlineFile(bookId, name)
            if (target.isFile && target.length() > 0) return@synchronized target
            val source = audio(key, voiceId, text, rate, previousText, nextText, bookId)
            target.parentFile?.mkdirs()
            val staged = File.createTempFile("offline-", ".tmp", target.parentFile)
            try {
                source.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
                check(ElevenLabsKey(context).read() == key) { tr("ElevenLabs API key changed during download") }
                if (!staged.renameTo(target)) error(tr("Could not save offline audio"))
                target
            } finally { staged.delete() }
        }
    }

    fun removeBookDownload(bookId: String) {
        val directory = offlineFile(bookId, "unused").parentFile ?: return
        if (directory.exists() && !directory.deleteRecursively()) error(tr("Could not remove all offline audio"))
    }

    fun hasBookDownload(bookId: String): Boolean =
        offlineFile(bookId, "unused").parentFile?.listFiles()?.any { it.isFile && it.length() > 0 } == true

    fun voices(key: String): List<ElevenVoice> {
        val found = mutableListOf<ElevenVoice>()
        var token: String? = null
        repeat(5) {
            val url = "https://api.elevenlabs.io/v2/voices?page_size=100&include_total_count=false" +
                (token?.let { value -> "&next_page_token=${URLEncoder.encode(value, "UTF-8")}" } ?: "")
            val connection = connect(url, key)
            try {
                val body = response(connection)
                val result = JSONObject(body)
                val rows = result.optJSONArray("voices") ?: return found
                for (index in 0 until rows.length()) {
                    val voice = rows.optJSONObject(index) ?: continue
                    val id = voice.optString("voice_id")
                    if (id.isBlank()) continue
                    val labels = voice.optJSONObject("labels")
                    val languages = mutableSetOf<String>()
                    labels?.optString("language")?.lowercase()?.substringBefore('-')
                        ?.takeIf { it.length == 2 }?.let(languages::add)
                    voice.optJSONArray("verified_languages")?.let { verified ->
                        for (item in 0 until verified.length()) {
                            verified.optJSONObject(item)?.optString("language")?.lowercase()?.substringBefore('-')
                                ?.takeIf { it.length == 2 }?.let(languages::add)
                        }
                    }
                    val detail = listOfNotNull(labels?.optString("accent")?.takeIf(String::isNotBlank),
                        labels?.optString("gender")?.takeIf(String::isNotBlank),
                        labels?.optString("language")?.takeIf(String::isNotBlank)).joinToString(" · ")
                    found += ElevenVoice(id, voice.optString("name").ifBlank { tr("Unnamed voice") }, detail, languages)
                }
                if (!result.optBoolean("has_more")) return found.distinctBy { it.id }.sortedBy { it.name.lowercase() }
                token = result.optString("next_page_token").takeIf { it.isNotBlank() } ?: return found
            } finally { connection.disconnect() }
        }
        return found.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    fun audio(key: String, voiceId: String, text: String, rate: Float,
              previousText: String? = null, nextText: String? = null,
              bookId: String? = null): File {
        require(voiceId.matches(Regex("[A-Za-z0-9_-]{8,100}"))) { "Choose an ElevenLabs voice" }
        require(text.isNotBlank() && text.length <= 10_000) { tr("This page is too long for narration") }
        val name = audioName(key, voiceId, text, rate, previousText, nextText)
        return synchronized(generationLock(name)) {
            if (bookId != null) offlineFile(bookId, name).let {
                if (it.isFile && it.length() > 0) return@synchronized it
            }
            val file = File(cache, name)
            if (file.isFile && file.length() > 0) {
                file.setLastModified(System.currentTimeMillis())
                return@synchronized file
            }
            val connection = connect("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128", key)
            val staged = File.createTempFile("eleven-", ".tmp", cache)
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2")
                    .put("voice_settings", JSONObject().put("speed", rate.toDouble().coerceIn(0.7, 1.2)))
                previousText?.takeIf(String::isNotBlank)?.let { body.put("previous_text", it.takeLast(300)) }
                nextText?.takeIf(String::isNotBlank)?.let { body.put("next_text", it.take(300)) }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                checkResponse(connection)
                check(!connection.contentType.orEmpty().contains("json", ignoreCase = true)) {
                    tr("ElevenLabs did not return audio")
                }
                var count = 0L
                connection.inputStream.use { input -> staged.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= 20L * 1024 * 1024) { tr("Audio response was too large") }
                        output.write(buffer, 0, read)
                    }
                } }
                require(count > 0) { tr("ElevenLabs returned empty audio") }
                check(ElevenLabsKey(context).read() == key) { tr("ElevenLabs API key changed during generation") }
                if (!staged.renameTo(file)) error(tr("Could not save narration audio"))
                trimCache()
                file
            } finally { staged.delete(); connection.disconnect() }
        }
    }

    fun clearCache() {
        cache.listFiles()?.forEach { it.delete() }
        offline.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun trimCache() {
        val files = cache.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified) ?: return
        var kept = 0L
        files.forEach { file ->
            kept += file.length()
            if (kept > 150L * 1024 * 1024) file.delete()
        }
    }

    private fun connect(url: String, key: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 90_000
            instanceFollowRedirects = false
            setRequestProperty("xi-api-key", key)
            setRequestProperty("Accept", "application/json, audio/mpeg")
        }

    private fun response(connection: HttpURLConnection): String {
        checkResponse(connection)
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun checkResponse(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code in 200..299) return
        val detail = runCatching {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText().take(4096) }.orEmpty()
            JSONObject(body).opt("detail")
        }.getOrNull()
        val errorCode = (detail as? JSONObject)?.let {
            it.optString("code").ifBlank { it.optString("status") }
        }.orEmpty()
        val reason = when (code) {
            401 -> tr("ElevenLabs rejected this key (HTTP 401{0}). Paste the full key shown when you create it in Developers → API Keys. A key name or last four characters cannot connect; expired keys must be replaced.", errorCode.takeIf(String::isNotBlank)?.let { ": $it" } ?: "")
            403 -> tr("ElevenLabs blocked this request (HTTP 403{0}). Enable Voices (read) and Text to Speech for this key, and check any IP restriction.", errorCode.takeIf(String::isNotBlank)?.let { ": $it" } ?: "")
            402, 429 -> tr("ElevenLabs credit or rate limit reached (HTTP {0}). Check your account.", code)
            in 500..599 -> tr("ElevenLabs is unavailable right now. Try again later.")
            else -> tr("ElevenLabs request failed (HTTP {0}).", code)
        }
        throw IllegalStateException(reason)
    }
}
