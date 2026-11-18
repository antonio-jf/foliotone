package com.linguareader

/**
 * Rewrites text before it is handed to a device voice, for things some engines (SherpaTTS/Piper)
 * read literally. Only the spoken text changes; the page shown is untouched.
 *
 * Currency: "$5" -> "5 dollars", "€1" or "1 €" -> "1 euro", "$5 million" -> "5 million dollars",
 * in the book's language (en, es, fr, de, it; others use English).
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

    fun normalize(text: String, language: String): String {
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
