package com.linguareader

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore of the library: a zip with backup.json (the library list with reading progress,
 * and every app setting: themes, voices, speeds, bookmarks, ratings, read marks) and, optionally,
 * books/<id>.epub. API keys and the narration server token are not included (they are encrypted
 * with a key that never leaves this device), and neither is downloaded narration audio.
 */
object Backup {
    private const val FORMAT = 1
    private val BOOK_ENTRY = Regex("books/([a-f0-9]{64})\\.epub")

    data class Summary(val books: Int, val settings: Int, val skipped: Int)

    fun export(context: Context, target: Uri, includeBooks: Boolean): Summary {
        val store = BookStore.get(context)
        val books = store.books()
        val settings = store.exportSettings()
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("created", System.currentTimeMillis())
            .put("library", store.libraryJson())
            .put("settings", settings)
        val output = context.contentResolver.openOutputStream(target) ?: error(tr("Cannot write the backup file"))
        var written = 0
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            if (includeBooks) books.forEach { book ->
                val file = store.file(book)
                if (!file.isFile) return@forEach
                zip.putNextEntry(ZipEntry("books/${book.id}.epub"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                written++
            }
        }
        return Summary(written, settings.length(), 0)
    }

    /**
     * Restores books missing from this device (each checked against its id, the SHA-256 of the file),
     * reading progress for books that are here, and every setting. Existing books stay.
     */
    fun restore(context: Context, source: Uri): Summary {
        val store = BookStore.get(context)
        var manifest: JSONObject? = null
        var restoredBooks = 0
        var skipped = 0
        val input = context.contentResolver.openInputStream(source) ?: error(tr("Cannot open the backup file"))
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "backup.json" -> {
                        val bytes = zip.readBytes()
                        require(bytes.size < 32 * 1024 * 1024) { tr("This backup file is not valid") }
                        manifest = JSONObject(String(bytes))
                    }
                    BOOK_ENTRY.matches(entry.name) -> {
                        val id = BOOK_ENTRY.find(entry.name)!!.groupValues[1]
                        val target = store.fileFor(id)
                        if (target.isFile) continue
                        val staged = File.createTempFile("restore-", ".epub", context.cacheDir)
                        try {
                            val digest = MessageDigest.getInstance("SHA-256")
                            staged.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total < 512L * 1024 * 1024) { tr("A book in the backup is too large") }
                                    digest.update(buffer, 0, count)
                                    out.write(buffer, 0, count)
                                }
                            }
                            val hash = digest.digest().joinToString("") { "%02x".format(it) }
                            if (hash == id && staged.renameTo(target)) restoredBooks++ else skipped++
                        } finally {
                            staged.delete()
                        }
                    }
                    else -> skipped++
                }
            }
        }
        val data = manifest ?: error(tr("This is not a FolioTone backup"))
        require(data.optInt("format") in 1..FORMAT) { tr("This backup was made by a newer FolioTone. Update the app first.") }
        store.restoreLibrary(data.optString("library", "[]"))
        val settings = data.optJSONObject("settings") ?: JSONObject()
        store.importSettings(settings)
        return Summary(restoredBooks, settings.length(), skipped)
    }
}
