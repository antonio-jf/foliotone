package com.linguareader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class Section(val title: String, val pages: List<String>)

/** Saved narration of one book for one cloud source and voice; shown as a badge in the library. */
data class NarrationStatus(val voice: String, val saved: Int, val total: Int) {
    val complete get() = total > 0 && saved >= total
}
data class Book(
    val id: String,
    val title: String,
    val author: String,
    var language: String,
    var section: Int = 0,
    var page: Int = 0,
    var sectionCount: Int = 0,
    var pagesInSection: Int = 0,
    var gutenbergId: Int? = null
)

/** EPUBs are copied into private app storage; only metadata and progress are kept in JSON. */
class BookStore private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: BookStore? = null
        fun get(context: Context): BookStore = instance ?: synchronized(this) {
            instance ?: BookStore(context.applicationContext).also { instance = it }
        }
    }
    private val root = File(context.filesDir, "books").also { it.mkdirs() }
    private val covers = File(context.filesDir, "covers").also { it.mkdirs() }
    private val checkedCovers = mutableSetOf<String>()
    private val index = File(context.filesDir, "library.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        root.listFiles()?.filter { it.name.startsWith("import-") && it.name.endsWith(".epub") }
            ?.forEach(File::delete)
    }

    var contextResume: Boolean
        get() = prefs.getBoolean("context_resume", true)
        set(value) { prefs.edit().putBoolean("context_resume", value).apply() }

    var autoMarkRead: Boolean
        get() = prefs.getBoolean("auto_mark_read", true)
        set(value) { prefs.edit().putBoolean("auto_mark_read", value).apply() }

    var themeId: String
        get() = prefs.getString("theme", "folio") ?: "folio"
        set(value) { prefs.edit().putString("theme", value).apply() }

    /** Interface language: I18n.SYSTEM (follow the device) or a code from I18n.LANGUAGES. */
    var appLanguage: String
        get() = prefs.getString("app_language", I18n.SYSTEM) ?: I18n.SYSTEM
        set(value) { prefs.edit().putString("app_language", value).apply() }

    var libraryGrid: Boolean
        get() = prefs.getBoolean("library_grid", false)
        set(value) { prefs.edit().putBoolean("library_grid", value).apply() }

    fun isRead(bookId: String) = prefs.getBoolean("book_read_$bookId", false)

    fun setRead(bookId: String, read: Boolean) {
        prefs.edit().putBoolean("book_read_$bookId", read).apply()
    }

    var folderUri: String?
        get() = prefs.getString("folder_uri", null)
        set(value) { prefs.edit().putString("folder_uri", value).apply() }

    var lastOpenedId: String?
        get() = prefs.getString("last_opened_id", null)
        set(value) { prefs.edit().putString("last_opened_id", value).apply() }

    var speechRate: Float
        get() = prefs.getFloat("speech_rate", 0.94f).coerceIn(0.75f, 1.2f)
        set(value) { prefs.edit().putFloat("speech_rate", value.coerceIn(0.75f, 1.2f)).apply() }

    var speechEngine: String?
        get() = prefs.getString("speech_engine", null)
        set(value) { prefs.edit().apply {
            if (value == null) remove("speech_engine") else putString("speech_engine", value)
        }.apply() }

    var narrationSource: String
        get() = prefs.getString("narration_source", "device") ?: "device"
        set(value) { prefs.edit().putString("narration_source",
            if (value == "elevenlabs" || value == "server") value else "device").apply() }

    fun narrationStatus(source: String, bookId: String): NarrationStatus? =
        prefs.getString("narration_${source}_$bookId", null)?.split('|')?.takeIf { it.size == 3 }?.let {
            NarrationStatus(it[0], it[1].toIntOrNull() ?: 0, it[2].toIntOrNull() ?: 0)
        }
    fun setNarrationStatus(source: String, bookId: String, status: NarrationStatus?) {
        prefs.edit().apply {
            if (status == null || status.saved <= 0) remove("narration_${source}_$bookId")
            else putString("narration_${source}_$bookId", "${status.voice}|${status.saved}|${status.total}")
        }.apply()
    }
    /** Forgets every narration summary, running server job and resume range (after deleting all audio). */
    fun clearNarrationRecords() {
        prefs.edit().apply {
            prefs.all.keys.filter {
                it.startsWith("narration_server_") || it.startsWith("narration_elevenlabs_") ||
                    it.startsWith("server_job_") || it.startsWith("server_range_")
            }.forEach { remove(it) }
        }.apply()
    }

    /** Read pages without saved server audio with the device voice instead of stopping. Off by default. */
    var serverDeviceFallback: Boolean
        get() = prefs.getBoolean("server_device_fallback", false)
        set(value) { prefs.edit().putBoolean("server_device_fallback", value).apply() }

    /** Voice on the user's narration server for a book language; defaults to the language's own voice. */
    fun serverVoice(language: String): String = prefs.getString("server_voice_$language", null)
        ?: serverDefaults()[language] ?: "neutral_female"
    fun setServerVoice(language: String, voice: String) {
        prefs.edit().putString("server_voice_$language", voice).apply()
    }

    /** Voices and per-language defaults last reported by the server's /v1/health. */
    fun serverVoices(): List<String> = prefs.getString("server_voices", null)
        ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    private fun serverDefaults(): Map<String, String> = prefs.getString("server_defaults", null)
        ?.split(',')?.mapNotNull { pair -> pair.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        ?.toMap() ?: mapOf("en" to "neutral_female", "it" to "it_female", "fr" to "fr_female",
            "de" to "de_female", "es" to "es_female")
    fun saveServerInfo(info: ServerInfo) {
        prefs.edit().putString("server_voices", info.voices.joinToString(","))
            .putString("server_defaults", info.defaults.entries.joinToString(",") { "${it.key}=${it.value}" })
            .apply()
    }

    /** A generation job still running on the server for a book, so a paused download can resume it. */
    fun serverJob(bookId: String): Pair<String, String>? = prefs.getString("server_job_$bookId", null)
        ?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun setServerJob(bookId: String, jobId: String, voice: String) {
        prefs.edit().putString("server_job_$bookId", "$jobId|$voice").apply()
    }
    fun clearServerJob(bookId: String) { prefs.edit().remove("server_job_$bookId").apply() }

    /** Page range [from, to) of the book's last server download, used when resuming it. */
    fun serverRange(bookId: String): Pair<Int, Int>? = prefs.getString("server_range_$bookId", null)
        ?.split('-')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    fun setServerRange(bookId: String, from: Int, to: Int) {
        prefs.edit().putString("server_range_$bookId", "$from-$to").apply()
    }
    fun serverJobs(): List<Pair<String, String>> = prefs.all.keys.filter { it.startsWith("server_job_") }
        .mapNotNull { key -> serverJob(key.removePrefix("server_job_"))?.let { key.removePrefix("server_job_") to it.first } }
    fun clearServerSettings() {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("server_") }.forEach { remove(it) }
        }.apply()
    }

    private fun elevenVoiceKey(language: String) = "eleven_voice_$language"
    fun elevenVoiceId(language: String): String? = prefs.getString(elevenVoiceKey(language), null)
    fun elevenVoiceName(language: String): String? = prefs.getString("${elevenVoiceKey(language)}_name", null)
    fun setElevenVoice(language: String, id: String, name: String) {
        prefs.edit().putString(elevenVoiceKey(language), id)
            .putString("${elevenVoiceKey(language)}_name", name).apply()
    }

    fun resetElevenVoices(voices: List<ElevenVoice>) {
        require(voices.isNotEmpty())
        prefs.edit().apply {
            listOf("en", "it", "fr", "de", "es").forEach { language ->
                val voice = voices.firstOrNull { language in it.languages } ?: voices.first()
                putString(elevenVoiceKey(language), voice.id)
                putString("${elevenVoiceKey(language)}_name", voice.name)
            }
        }.apply()
    }

    private fun voiceKey(language: String, engine: String?) = "voice_${engine ?: "system"}_$language"
    fun voiceName(language: String, engine: String? = speechEngine): String? =
        prefs.getString(voiceKey(language, engine), null)
            ?: if (engine == null) prefs.getString("voice_$language", null) else null

    fun setVoiceName(language: String, name: String?, engine: String? = speechEngine) {
        prefs.edit().apply {
            val key = voiceKey(language, engine)
            if (name == null) remove(key) else putString(key, name)
        }.apply()
    }

    @Synchronized fun books(): List<Book> {
        val rows = runCatching { JSONArray(index.readText()) }.getOrDefault(JSONArray())
        return (0 until rows.length()).mapNotNull { i ->
            runCatching {
                val o = rows.getJSONObject(i)
                Book(o.getString("id"), o.getString("title"), o.optString("author"),
                    o.optString("language"), o.optInt("section"), o.optInt("page"),
                    o.optInt("sectionCount"), o.optInt("pagesInSection"),
                    if (o.has("gutenbergId") && !o.isNull("gutenbergId")) o.optInt("gutenbergId") else null)
            }.getOrNull()
        }.filter { File(root, "${it.id}.epub").exists() }
    }

    @Synchronized private fun save(all: List<Book>) {
        val data = JSONArray()
        all.forEach { b -> data.put(JSONObject().apply {
            put("id", b.id); put("title", b.title); put("author", b.author)
            put("language", b.language); put("section", b.section); put("page", b.page)
            put("sectionCount", b.sectionCount); put("pagesInSection", b.pagesInSection)
            b.gutenbergId?.let { put("gutenbergId", it) }
        }) }
        val temp = File(context.filesDir, "library.json.tmp")
        temp.writeText(data.toString())
        if (!temp.renameTo(index)) { index.writeText(data.toString()); temp.delete() }
    }

    /**
     * Changes the newest saved copy of a book. Screens hold copies read earlier, and writing one of
     * those back with update() would undo reading progress the narration service saved since.
     */
    @Synchronized fun edit(bookId: String, change: (Book) -> Unit): Book? {
        val all = books().toMutableList()
        val at = all.indexOfFirst { it.id == bookId }
        if (at < 0) return null
        change(all[at])
        save(all)
        return all[at]
    }

    @Synchronized fun update(book: Book) {
        val all = books().toMutableList()
        val at = all.indexOfFirst { it.id == book.id }
        if (at >= 0) { all[at] = book; save(all) }
    }

    /** Integer tenths preserve exact scores; no entry means unrated, distinct from 0.0. */
    fun rating(bookId: String): Int? {
        val value = prefs.getInt("book_rating_$bookId", -1)
        return value.takeIf { it in 0..50 }
    }

    fun setRating(bookId: String, tenths: Int?) {
        require(tenths == null || tenths in 0..50) { tr("Rating must be between 0.0 and 5.0") }
        prefs.edit().apply {
            if (tenths == null) remove("book_rating_$bookId")
            else putInt("book_rating_$bookId", tenths)
        }.apply()
    }

    fun file(book: Book) = File(root, "${book.id}.epub")

    @Synchronized fun coverFile(book: Book): File? {
        val output = File(covers, "${book.id}.cover")
        if (output.isFile && output.length() > 0) return output
        if (!checkedCovers.add(book.id)) return null
        val bytes = runCatching { Epub.cover(file(book)) }.getOrNull() ?: return null
        return runCatching {
            output.writeBytes(bytes)
            output
        }.getOrNull()
    }

    fun importBook(uri: Uri): Book = context.contentResolver.openInputStream(uri)?.use { importBook(it) }
        ?: error(tr("Cannot open the selected file"))

    /** The caller owns [input]. Both local and downloaded EPUBs use the same validation and storage path. */
    fun importBook(input: InputStream, gutenbergId: Int? = null,
                   onProgress: (Long) -> Unit = {}): Book {
        val staged = File.createTempFile("import-", ".epub", root)
        try {
            staged.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= 100L * 1024 * 1024) { tr("EPUB exceeds the 100 MB import limit") }
                    output.write(buffer, 0, count)
                    onProgress(size)
                }
            }
            require(staged.length() > 0) { tr("The file is empty") }
            val digest = MessageDigest.getInstance("SHA-256")
            staged.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val id = digest.digest()
                .joinToString("") { "%02x".format(it) }
            val parsed = try {
                Epub.parse(staged)
            } catch (error: ZipException) {
                throw IllegalArgumentException(
                    tr("This EPUB is incomplete or damaged. Copy it to the device again or download a fresh copy, then retry."),
                    error
                )
            }
            return synchronized(this) {
                books().firstOrNull { it.id == id || (gutenbergId != null && it.gutenbergId == gutenbergId) }
                    ?.let { existing ->
                        if (gutenbergId != null && existing.gutenbergId == null) {
                            existing.gutenbergId = gutenbergId
                            update(existing)
                        }
                        return@synchronized existing
                    }
                val book = Book(id, parsed.title, parsed.author, parsed.language,
                    sectionCount = parsed.sections.size, pagesInSection = parsed.sections.first().pages.size,
                    gutenbergId = gutenbergId)
                require(staged.renameTo(file(book))) { tr("Cannot save the book") }
                try {
                    save(books() + book)
                    coverFile(book)
                    book
                } catch (error: Exception) {
                    file(book).delete()
                    throw error
                }
            }
        } finally { staged.delete() }
    }

    fun scanFolder(): Pair<Int, Int> {
        val uri = folderUri?.let(Uri::parse) ?: return 0 to 0
        val folder = DocumentFile.fromTreeUri(context, uri) ?: return 0 to 0
        var added = 0; var failed = 0
        folder.listFiles().filter { it.isFile && it.name?.endsWith(".epub", true) == true }.forEach {
            val before = books().size
            runCatching { importBook(it.uri) }.onSuccess { if (books().size > before) added++ }
                .onFailure { failed++ }
        }
        return added to failed
    }
}

data class ParsedBook(val title: String, val author: String, val language: String, val sections: List<Section>)

object Epub {
    private val languages = setOf("en", "it", "fr", "de", "es")

    fun cover(file: File): ByteArray? = ZipFile(file).use { zip ->
        fun bytes(path: String, limit: Int): ByteArray? {
            val entry = zip.getEntry(path) ?: return null
            if (entry.size > limit) return null
            return zip.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) return@use null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        fun document(path: String) = zip.getEntry(path)?.let { entry ->
            if (entry.size > 1024 * 1024) return@let null
            val data = bytes(path, 1024 * 1024) ?: return@let null
            val source = data.toString(Charsets.UTF_8)
            if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(source)) return@let null
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(ByteArrayInputStream(data))
        }
        val container = document("META-INF/container.xml") ?: return null
        val rootfiles = container.getElementsByTagNameNS("*", "rootfile")
        if (rootfiles.length == 0) return null
        val opfPath = (rootfiles.item(0) as Element).getAttribute("full-path")
        val opf = document(opfPath) ?: return null
        val items = opf.getElementsByTagNameNS("*", "item")
        val coverId = opf.getElementsByTagNameNS("*", "meta").let { metas ->
            (0 until metas.length).map { metas.item(it) as Element }
                .firstOrNull { it.getAttribute("name").equals("cover", true) }
                ?.getAttribute("content")
        }
        val images = (0 until items.length).map { items.item(it) as Element }
            .filter { it.getAttribute("media-type").startsWith("image/") }
        val cover = images.firstOrNull { "cover-image" in it.getAttribute("properties").split(' ') }
            ?: images.firstOrNull { it.getAttribute("id") == coverId }
            ?: images.firstOrNull { "cover" in it.getAttribute("id").lowercase() || "cover" in it.getAttribute("href").lowercase() }
            ?: return null
        val path = resolve(opfPath.substringBeforeLast('/', ""), cover.getAttribute("href").substringBefore('#'))
        bytes(path, 5 * 1024 * 1024)
    }

    fun parse(file: File): ParsedBook = ZipFile(file).use { zip ->
        var extractedBytes = 0L
        fun read(path: String, maxBytes: Int): ByteArray {
            val entry = zip.getEntry(path) ?: error(tr("Missing EPUB entry: {0}", path))
            return zip.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { tr("EPUB section is too large") }
                    extractedBytes += count
                    require(extractedBytes <= 48L * 1024 * 1024) { tr("EPUB contains too much extracted text") }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        fun xml(path: String) = zip.getEntry(path)?.let {
            val bytes = read(path, 4 * 1024 * 1024)
            val source = bytes.toString(Charsets.UTF_8)
            require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
                tr("EPUB package contains an unsupported XML declaration")
            }
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { isXIncludeAware = false }
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } ?: error(tr("Missing EPUB entry: {0}", path))

        val container = xml("META-INF/container.xml")
        val rootfiles = container.getElementsByTagNameNS("*", "rootfile")
        require(rootfiles.length > 0) { tr("Invalid EPUB: no package document") }
        val opfPath = (rootfiles.item(0) as Element).getAttribute("full-path")
        val opf = xml(opfPath)
        fun meta(name: String): String {
            val nodes = opf.getElementsByTagNameNS("*", name)
            return if (nodes.length > 0) nodes.item(0).textContent.trim() else ""
        }
        val manifest = mutableMapOf<String, String>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            manifest[item.getAttribute("id")] = item.getAttribute("href")
        }
        val base = opfPath.substringBeforeLast('/', "")
        val sections = mutableListOf<Section>()
        val spine = opf.getElementsByTagNameNS("*", "itemref")
        require(spine.length <= 2_000) { tr("EPUB has too many sections") }
        var totalTextChars = 0L
        var totalPages = 0
        for (i in 0 until spine.length) {
            val ref = spine.item(i) as Element
            val href = manifest[ref.getAttribute("idref")] ?: continue
            val path = resolve(base, href.substringBefore('#'))
            if (zip.getEntry(path) == null) continue
            val html = read(path, 8 * 1024 * 1024).toString(Charsets.UTF_8)
            val doc = Jsoup.parse(html)
            doc.select("script,style,nav,head,svg").remove()
            val body = doc.body()
            val heading = body.selectFirst("h1,h2,h3")?.text()?.trim().orEmpty()
            val paragraphs = body.select("h1,h2,h3,h4,p,li,blockquote")
                .filter { node -> node.parents().none { it.tagName() in setOf("p", "li", "blockquote") } }
                .map { it.text().trim() }.filter { it.isNotBlank() }
            val text = if (paragraphs.isEmpty()) body.text() else paragraphs.joinToString("\n\n")
            totalTextChars += text.length
            require(totalTextChars <= 8_000_000) { tr("EPUB contains too much text") }
            val pages = paginate(text)
            totalPages += pages.size
            require(totalPages <= 12_000) { tr("EPUB has too many pages") }
            if (pages.isNotEmpty()) sections += Section(heading.ifBlank { "Section ${sections.size + 1}" }, pages)
        }
        require(sections.isNotEmpty()) { tr("This EPUB has no readable text") }
        val declared = meta("language").substringBefore('-').lowercase()
        ParsedBook(meta("title").ifBlank { file.nameWithoutExtension }, meta("creator"),
            if (declared in languages) declared else inferLanguage(sections.take(3).flatMap { it.pages.take(2) }.joinToString(" ")),
            sections)
    }

    private fun resolve(base: String, href: String): String {
        val parts = (if (base.isBlank()) href else "$base/$href").split('/')
        val stack = mutableListOf<String>()
        parts.forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                else -> stack += part
            }
        }
        return java.net.URLDecoder.decode(stack.joinToString("/"), "UTF-8")
    }

    private fun paginate(text: String): List<String> {
        val paragraphs = text.trim().split(Regex("\\n\\s*\\n"))
        val pages = mutableListOf<String>()
        val page = StringBuilder()
        for (paragraph in paragraphs) {
            val words = paragraph.replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            if (page.isNotEmpty()) page.append("\n\n")
            for (word in words) {
                if (page.length + word.length > 950 && page.isNotEmpty()) {
                    pages += page.toString().trim()
                    page.clear()
                }
                if (page.isNotEmpty() && page.last() != '\n') page.append(' ')
                page.append(word)
            }
        }
        if (page.isNotEmpty()) pages += page.toString().trim()
        return pages
    }

    private fun inferLanguage(text: String): String {
        val words = Regex("[\\p{L}]+").findAll(text.lowercase()).map { it.value }.toList()
        val clues = mapOf(
            "en" to setOf("the", "and", "of", "with", "that"),
            "it" to setOf("il", "che", "della", "con", "sono"),
            "fr" to setOf("le", "les", "des", "avec", "dans"),
            "de" to setOf("der", "die", "das", "und", "mit"),
            "es" to setOf("el", "los", "las", "con", "que")
        )
        val scores = clues.mapValues { (_, markers) -> words.count { it in markers } }
        return scores.maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key.orEmpty()
    }
}
