package com.linguareader

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Keep the phone reader and Android Auto on the same voice preference. */
object NarrationVoices {
    fun locale(language: String): Locale = when (language) {
        "it" -> Locale.ITALIAN
        "fr" -> Locale.FRENCH
        "de" -> Locale.GERMAN
        "es" -> Locale("es")
        else -> Locale.ENGLISH
    }

    fun available(tts: TextToSpeech, language: String): List<Voice> =
        (tts.voices ?: emptySet()).filter { voice ->
            voice.locale.language == language &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
        }.sortedWith(compareByDescending<Voice> { it.quality }
            .thenBy { it.name.endsWith("-language") }
            .thenBy { it.isNetworkConnectionRequired }
            .thenBy { it.latency }
            .thenBy { it.name })

    fun choose(tts: TextToSpeech, language: String, preferred: String?, allowNetwork: Boolean = true): Voice? {
        val voices = available(tts, language).filter { allowNetwork || !it.isNetworkConnectionRequired }
        return voices.firstOrNull { it.name == preferred } ?: voices.firstOrNull()
    }

    fun label(voice: Voice, number: Int): String {
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> tr("Very high")
            voice.quality >= Voice.QUALITY_HIGH -> tr("High")
            else -> tr("Standard")
        }
        val connection = if (voice.isNetworkConnectionRequired) tr("Online") else tr("Offline")
        return tr("Voice {0} · {1} · {2} · {3}", number, voice.locale.country.ifBlank { voice.locale.language.uppercase() }, quality, connection)
    }

    fun sample(language: String): String = when (language) {
        "it" -> "Le storie prendono vita, una pagina alla volta."
        "fr" -> "Les histoires prennent vie, une page après l'autre."
        "de" -> "Geschichten werden lebendig, Seite für Seite."
        "es" -> "Las historias cobran vida, página tras página."
        else -> "Stories come alive, one page at a time."
    }
}
