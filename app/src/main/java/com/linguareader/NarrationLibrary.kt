package com.linguareader

import android.content.Context
import java.io.File

/** Book-level view of saved narration for both cloud sources: library badges and storage clean-up. */
object NarrationLibrary {
    /** Identity of the current voice for [source]; audio saved for another voice does not count. */
    fun voiceFor(context: Context, source: String, language: String, bookId: String? = null): String? {
        val store = BookStore.get(context)
        return when (source) {
            "server" -> store.serverVoice(language)
            "elevenlabs" -> store.elevenVoiceId(language)?.let { "$it@${bookId?.let(store::speechRateFor) ?: store.speechRate}" }
            else -> null
        }
    }

    /** Recounts the saved pages of [book] for the current voice of both cloud sources. */
    fun recount(context: Context, book: Book, sections: List<Section>) {
        val store = BookStore.get(context)
        val language = NarrationVoices.locale(book.language).language
        voiceFor(context, "server", language)?.let { voice ->
            val (saved, total) = ServerAudio.savedPages(context, book.id, sections, voice)
            store.setNarrationStatus("server", book.id, NarrationStatus(voice, saved, total))
        }
        val key = ElevenLabsKey(context).read()
        val elevenVoice = store.elevenVoiceId(language)
        if (key != null && elevenVoice != null) {
            val rate = store.speechRateFor(book.id)
            val (saved, total) = ElevenLabs(context).savedPages(book.id, sections, key, elevenVoice, rate)
            store.setNarrationStatus("elevenlabs", book.id, NarrationStatus("$elevenVoice@$rate", saved, total))
        }
    }

    /** Library badge text, or null when nothing is saved. Second value: true when every page is saved. */
    fun badge(context: Context, book: Book): Pair<String, Boolean>? {
        val store = BookStore.get(context)
        val language = NarrationVoices.locale(book.language).language
        val preferred = store.narrationSource.takeIf { it == "server" || it == "elevenlabs" }
        val candidates = listOfNotNull(preferred) + listOf("server", "elevenlabs").filter { it != preferred }
        for (source in candidates) {
            val status = store.narrationStatus(source, book.id) ?: continue
            val present = if (source == "server") ServerAudio.hasAny(context, book.id)
                else ElevenLabs(context).hasBookDownload(book.id)
            if (!present) { store.setNarrationStatus(source, book.id, null); continue }
            val name = if (source == "server") tr("Server narration") else tr("ElevenLabs narration")
            return when {
                status.voice != voiceFor(context, source, language, book.id) -> tr("🎧 {0} saved for another voice", name) to false
                status.complete -> tr("🎧 {0} downloaded", name) to true
                else -> tr("🎧 {0} · {1}% downloaded", name, (status.saved * 100 / status.total.coerceAtLeast(1)).coerceIn(1, 99)) to false
            }
        }
        return null
    }

    private fun folders(context: Context) = listOf("server_audio", "offline_audio", "eleven_audio")
        .map { File(context.noBackupFilesDir, it) }

    fun bytesUsed(context: Context): Long =
        folders(context).sumOf { folder -> folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    /** Deletes every saved narration page from this device. API keys and server settings stay. */
    fun deleteAll(context: Context) {
        ServerAudio.clearAll(context)
        ElevenLabs(context).clearCache()
        BookStore.get(context).clearNarrationRecords()
    }
}
