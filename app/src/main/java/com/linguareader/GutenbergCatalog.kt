package com.linguareader

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

data class GutenbergBook(
    val id: Int,
    val title: String,
    val author: String,
    val languages: List<String>,
    val epubUrl: String
)

data class GutenbergPage(val books: List<GutenbergBook>, val next: String?,
                         val previous: String?, val total: Int)

/** Gutendex supplies catalog metadata; the selected EPUB comes from Project Gutenberg. */
object GutenbergCatalog {
    private const val catalogHost = "gutendex.com"
    private val supportedLanguages = setOf("en", "it", "fr", "de", "es")
    private const val maxCatalogBytes = 2 * 1024 * 1024
    private const val maxEpubBytes = 100L * 1024 * 1024
    private const val userAgent = "FolioTone/1.0 (Android EPUB reader; user initiated download)"

    fun message(error: Throwable): String = when (error) {
        is UnknownHostException -> tr("Check your internet connection.")
        is SocketTimeoutException -> tr("The server timed out. Try again.")
        else -> error.message ?: tr("Please try again.")
    }

    fun search(query: String, nextUrl: String? = null): GutenbergPage {
        val url = nextUrl ?: buildString {
            append("https://gutendex.com/books/")
            query.trim().takeIf(String::isNotEmpty)?.let {
                append("?search=").append(URLEncoder.encode(it.take(120), "UTF-8"))
            }
        }
        require(safeCatalogUrl(url)) { tr("Invalid catalog page") }
        val connection = connect(url, "application/json", 30_000)
        try {
            check(connection.responseCode == 200) {
                tr("Book catalog unavailable (HTTP {0})", connection.responseCode)
            }
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= maxCatalogBytes) { tr("Catalog response was too large") }
                    output.write(buffer, 0, read)
                }
                output.toString("UTF-8")
            }
            val data = JSONObject(body)
            val rows = data.optJSONArray("results") ?: error(tr("Catalog response was incomplete"))
            val books = (0 until rows.length()).mapNotNull { index ->
                val item = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optInt("id")
                val title = item.optString("title").trim()
                val formats = item.optJSONObject("formats") ?: return@mapNotNull null
                val epub = formats.keys().asSequence()
                    .firstOrNull { it.startsWith("application/epub+zip", ignoreCase = true) }
                    ?.let(formats::optString).orEmpty()
                if (id <= 0 || title.isBlank() || !safeEpubUrl(epub)) return@mapNotNull null
                val authors = item.optJSONArray("authors")
                val author = (0 until (authors?.length() ?: 0))
                    .mapNotNull { authors?.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                    .joinToString(", ")
                val languageRows = item.optJSONArray("languages")
                val languages = (0 until (languageRows?.length() ?: 0))
                    .mapNotNull { languageRows?.optString(it)?.takeIf(String::isNotBlank) }
                if (languages.none { it in supportedLanguages }) return@mapNotNull null
                GutenbergBook(id, title, author, languages, epub)
            }
            val next = data.optString("next").takeIf { it.isNotBlank() && it != "null" }
                ?.let(::catalogPageUrl)
            val previous = data.optString("previous").takeIf { it.isNotBlank() && it != "null" }
                ?.let(::catalogPageUrl)
            require(next == null || safeCatalogUrl(next)) { tr("Invalid catalog pagination link") }
            require(previous == null || safeCatalogUrl(previous)) { tr("Invalid catalog pagination link") }
            return GutenbergPage(books, next, previous, data.optInt("count"))
        } finally { connection.disconnect() }
    }

    fun download(book: GutenbergBook, store: BookStore, onProgress: (Long, Long?) -> Unit,
                 shouldContinue: () -> Boolean = { true }): Book {
        require(safeEpubUrl(book.epubUrl)) { tr("Invalid Project Gutenberg EPUB link") }
        if (!shouldContinue()) throw CancellationException()
        val connection = openEpub(book.epubUrl)
        try {
            if (!shouldContinue()) throw CancellationException()
            val size = connection.contentLengthLong.takeIf { it >= 0 }
            require(size == null || size <= maxEpubBytes) { tr("EPUB exceeds the 100 MB import limit") }
            check(!connection.contentType.orEmpty().contains("text/html", ignoreCase = true)) {
                tr("Project Gutenberg returned a web page instead of an EPUB")
            }
            return connection.inputStream.use { input ->
                store.importBook(input, book.id) { bytes ->
                    if (!shouldContinue()) throw CancellationException()
                    onProgress(bytes, size)
                }
            }
        } finally { connection.disconnect() }
    }

    private fun openEpub(url: String, redirects: Int = 0): HttpURLConnection {
        require(redirects <= 3 && safeEpubUrl(url)) { tr("Invalid Project Gutenberg redirect") }
        val connection = connect(url, "application/epub+zip", 90_000)
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: error(tr("Download redirect was incomplete"))
                val resolved = URL(URL(url), location).toString()
                connection.disconnect()
                return openEpub(resolved, redirects + 1)
            }
            check(code == 200) {
                if (code == 403) tr("Project Gutenberg blocked this download (HTTP 403). Try again later.")
                else tr("Project Gutenberg download failed (HTTP {0})", code)
            }
            return connection
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }
    }

    private fun connect(url: String, accept: String, timeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = timeout
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", userAgent)
        }

    private fun safeCatalogUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host.equals(catalogHost, ignoreCase = true) &&
            uri.port == -1 && uri.userInfo == null && uri.path == "/books/" && value.length < 2048
    }.getOrDefault(false)

    private fun catalogPageUrl(value: String): String =
        URL(URL("https://gutendex.com/books/"), value).toString().also {
            require(safeCatalogUrl(it)) { tr("Invalid catalog pagination link") }
        }

    private fun safeEpubUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase()
        uri.scheme == "https" && host in setOf("gutenberg.org", "www.gutenberg.org") &&
            uri.port == -1 && uri.userInfo == null &&
            (uri.path.startsWith("/ebooks/") || uri.path.startsWith("/cache/epub/") ||
                uri.path.startsWith("/files/")) && value.length < 2048
    }.getOrDefault(false)
}
