package com.linguareader

/**
 * Rewrites text before it is handed to a device voice, for things some engines (SherpaTTS/Piper)
 * read literally. Only the spoken text changes; the page shown is untouched.
 *
 * Currency: "$5" -> "5 dollars", "€1" or "1 €" -> "1 euro", "$5 million" -> "5 million dollars",
 * in the book's language (en, es, fr, de, it; others use English).
 *
 * Clean-up ([clean]): footnote numbers stuck to punctuation ("it.10" -> "it."), "[3]" references,
 * square brackets around editorial insertions ("he's [Ross Walker's]" keeps the words), curly
 * apostrophes and quotes made straight, dashes between words read as a comma, "&" and "%" spelled
 * out, and stray "*" or "_" markup removed. Piper voices garble some of these the same way every time.
 */
object SpeechText {
    private class Names(val singular: String, val plural: String)

    private val currencies: Map<String, Map<Char, Names>> = mapOf(
        "en" to mapOf('$' to Names("dollar", "dollars"), '€' to Names("euro", "euros"),
            '£' to Names("pound", "pounds"), '¥' to Names("yen", "yen")),
        "es" to mapOf('$' to Names("dólar", "dólares"), '€' to Names("euro", "euros"),
            '£' to Names("libra", "libras"), '¥' to Names("yen", "yenes")),
        "fr" to mapOf('$' to Names("dollar", "dollars"), '€' to Names("euro", "euros"),
            '£' to Names("livre", "livres"), '¥' to Names("yen", "yens")),
        "de" to mapOf('$' to Names("Dollar", "Dollar"), '€' to Names("Euro", "Euro"),
            '£' to Names("Pfund", "Pfund"), '¥' to Names("Yen", "Yen")),
        "it" to mapOf('$' to Names("dollaro", "dollari"), '€' to Names("euro", "euro"),
            '£' to Names("sterlina", "sterline"), '¥' to Names("yen", "yen"))
    )

    // Longer words first, so "mil millones" wins over "mil".
    private const val SCALES = "mil millones|millones|millón|mil|thousand|millions?|billions?|trillions?|" +
        "milliards?|Millionen|Million|Milliarden|Milliarde|Tausend|milioni|milione|miliardi|miliardo|mila"
    /** Scales that take "de"/"di" before the currency in Spanish, French and Italian. */
    private val LINKED = Regex("(?i)mil millones|millones|millón|millions?|milliards?|milioni|milione|miliardi|miliardo")
    private val SYMBOL_FIRST = Regex("([\$€£¥])\\s?(\\d[\\d.,]*)(\\s+(?:$SCALES)\\b)?")
    private val SYMBOL_AFTER = Regex("(\\d[\\d.,]*)\\s?([€£¥])")

    private val FOOTNOTE_REFERENCE = Regex("\\[\\d{1,3}]")
    /** Up to three digits right after a word's closing punctuation: a footnote mark ("it.10"), not "3.14". */
    private val FOOTNOTE_NUMBER = Regex("(?<=[\\p{L}][.,;:!?”\"’)\\]])\\d{1,3}(?=\\s|$)")
    private val APOSTROPHES = Regex("[’‘‚‛]")
    private val QUOTES = Regex("[“”„«»‹›]")
    private val WORD_DASH = Regex("(?<=[^\\d\\s])\\s*[—–]\\s*(?=[^\\d\\s])")
    private val MARKUP = Regex("[*_]+")
    private val AMPERSAND = Regex("\\s*&\\s*")
    private val PERCENT = Regex("(?<=\\d)\\s?%")
    private val SPACES = Regex("[ \\t]{2,}")
    private val AND = mapOf("en" to "and", "es" to "y", "fr" to "et", "de" to "und", "it" to "e")
    private val PER_CENT = mapOf("en" to "percent", "es" to "por ciento", "fr" to "pour cent", "de" to "Prozent", "it" to "per cento")

    /** Words the user taught a different pronunciation (Listening settings → Pronunciations). */
    @Volatile private var pronunciations: List<Pair<Regex, String>> = emptyList()

    fun setPronunciations(rules: List<Pair<String, String>>) {
        pronunciations = rules.filter { it.first.isNotBlank() && it.second.isNotBlank() }.map { (written, spoken) ->
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(written.trim()) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) to
                Regex.escapeReplacement(spoken.trim())
        }
    }

    private fun pronounce(text: String): String =
        pronunciations.fold(text) { result, (pattern, spoken) -> pattern.replace(result, spoken) }

    fun normalize(text: String, language: String): String = currency(clean(pronounce(text), language), language)

    /** Punctuation and markup that voices read literally or garble (see the class comment). */
    fun clean(text: String, language: String): String {
        var result = FOOTNOTE_REFERENCE.replace(text, "")
        result = FOOTNOTE_NUMBER.replace(result, "")
        result = result.replace("[", "").replace("]", "")
        result = APOSTROPHES.replace(result, "'")
        result = QUOTES.replace(result, "\"")
        result = WORD_DASH.replace(result, ", ")
        result = MARKUP.replace(result, " ")
        if ('&' in result) result = AMPERSAND.replace(result, " ${AND[language] ?: "and"} ")
        if ('%' in result) result = PERCENT.replace(result, " ${PER_CENT[language] ?: "percent"}")
        return SPACES.replace(result, " ")
    }

    private fun currency(text: String, language: String): String {
        if (text.none { it == '$' || it == '€' || it == '£' || it == '¥' }) return text
        val table = currencies[language] ?: currencies.getValue("en")
        fun spoken(symbol: Char, number: String, scale: String): String {
            val amount = number.trimEnd('.', ',')
            val trailing = number.substring(amount.length)
            val names = table[symbol] ?: return number
            val name = if (scale.isEmpty() && amount == "1") names.singular else names.plural
            val link = if (scale.isNotEmpty() && LINKED.matches(scale.trim())) when (language) {
                "es", "fr" -> " de"; "it" -> " di"; else -> ""
            } else ""
            return "$amount$scale$link $name$trailing"
        }
        val first = SYMBOL_FIRST.replace(text) { m ->
            spoken(m.groupValues[1][0], m.groupValues[2], m.groupValues[3])
        }
        return SYMBOL_AFTER.replace(first) { m -> spoken(m.groupValues[2][0], m.groupValues[1], "") }
    }
}
