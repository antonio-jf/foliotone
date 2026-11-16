package com.linguareader

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Interface language. English text in the code is the key; assets/i18n/<language>.json maps it to
 * the translation. Missing entries fall back to English, so new text never shows up blank.
 */
object I18n {
    const val SYSTEM = "system"
    /** Interface languages offered in settings: code to the language's own name. */
    val LANGUAGES = listOf("en" to "English", "es" to "Español")

    @Volatile var language: String = "en"
        private set
    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile private var ready = false

    /** Loads the saved choice once per process; every activity and service calls this first. */
    fun init(context: Context) {
        if (!ready) apply(context, BookStore.get(context).appLanguage)
    }

    /** Switches the interface language immediately; screens show it when they are next drawn. */
    fun apply(context: Context, choice: String) {
        val code = if (choice == SYSTEM) systemLanguage() else choice
        table = if (code == "en") emptyMap() else load(context, code)
        language = code
        ready = true
    }

    /** The device language when it is one we translate, otherwise English. */
    fun systemLanguage(): String =
        Locale.getDefault().language.takeIf { code -> LANGUAGES.any { it.first == code } } ?: "en"

    fun translate(text: String): String = table[text] ?: text

    private fun load(context: Context, code: String): Map<String, String> = runCatching {
        val json = JSONObject(context.assets.open("i18n/$code.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())
}

/** Translates [text] and fills its numbered placeholders {0}, {1}, … with [args]. */
fun tr(text: String, vararg args: Any?): String {
    var result = I18n.translate(text)
    args.forEachIndexed { index, value -> result = result.replace("{$index}", value.toString()) }
    return result
}
