package com.bydmate.app.voice

import java.text.Normalizer

/**
 * Pure Polish pre-pass shared by the utterance and the command dictionary. Case and punctuation go,
 * number words become digits. Diacritics are kept and never folded: an unaccented spelling a driver
 * or a keyboard produces is accepted only where the dictionary lists it, word for word. Negation is
 * never removed or ignored. Closed word lists, no stemming, no fuzzy matching.
 */
object PolishVoiceNormalizer {

    private const val HUNDRED = 100
    private const val MAX_DIGITS = 3

    private val NON_WORD = Regex("[^\\p{L}\\p{Nd} ]")
    private val WHITESPACE = Regex("\\s+")

    /** Politeness and address words. Never a negation, a quote marker or a connector. */
    val FILLERS: Set<String> = setOf(
        "proszę", "prosze", "dziękuję", "dziekuje", "dzięki", "dzieki", "słuchaj", "sluchaj", "hej", "ej",
        "mi", "możesz", "mozesz", "chcę", "chce", "ja",
    )

    /** Words that turn a request around. Tokens that start with "nie" and continue into a command
     *  verb ("nieotwieraj") count too; see [PolishVoiceTextRules]. */
    val NEGATIONS: Set<String> = setOf("nie", "ani", "bez", "nigdy", "przestań", "przestan", "zaprzestań", "zaprzestan")

    /** Words that make what follows a quotation, a report or a text to write, not an order. */
    val QUOTE_WORDS: Set<String> = setOf(
        "cytuję", "cytuje", "cytat", "zacytuj", "powiedział", "powiedziała", "powiedzieli", "powiedziałem", "powiedziałam",
        "powiedz", "powiem", "powiesz", "powtórz", "powtorz", "mówi", "mowi", "mówię", "mowie", "mówił", "mowil", "mówiła",
        "mowila", "mówią", "mowia", "krzyknął", "krzyknal", "krzyknęła", "krzyknela", "napisz", "napisał", "napisal",
        "napisała", "napisala", "wpisz", "przeczytaj", "zapytaj", "pytał", "pytal", "pytała", "pytala", "brzmi",
        "słowa", "slowa", "zdanie", "zdania", "komenda", "komendę", "komende", "polecenie", "polecenia", "hasło", "haslo",
        "fraza", "frazę", "fraze", "wypowiedz",
    )

    private val UNITS: Map<String, Int> = mapOf(
        "zero" to 0, "jeden" to 1, "jedna" to 1, "jedną" to 1, "jedno" to 1, "dwa" to 2, "dwie" to 2, "trzy" to 3,
        "cztery" to 4, "pięć" to 5, "piec" to 5, "sześć" to 6, "szesc" to 6, "siedem" to 7, "osiem" to 8,
        "dziewięć" to 9, "dziewiec" to 9,
        // After "do": "do dwudziestu pięciu".
        "dwóch" to 2, "trzech" to 3, "czterech" to 4, "pięciu" to 5, "sześciu" to 6, "siedmiu" to 7, "ośmiu" to 8,
        "dziewięciu" to 9,
    )
    private val TEENS: Map<String, Int> = mapOf(
        "dziesięć" to 10, "dziesiec" to 10, "jedenaście" to 11, "jedenascie" to 11, "dwanaście" to 12,
        "dwanascie" to 12, "trzynaście" to 13, "trzynascie" to 13, "czternaście" to 14, "czternascie" to 14,
        "piętnaście" to 15, "pietnascie" to 15, "szesnaście" to 16, "szesnascie" to 16, "siedemnaście" to 17,
        "siedemnascie" to 17, "osiemnaście" to 18, "osiemnascie" to 18, "dziewiętnaście" to 19,
        "dziewietnascie" to 19,
        "dziesięciu" to 10, "jedenastu" to 11, "dwunastu" to 12, "trzynastu" to 13, "czternastu" to 14,
        "piętnastu" to 15, "szesnastu" to 16, "siedemnastu" to 17, "osiemnastu" to 18, "dziewiętnastu" to 19,
    )
    private val TENS: Map<String, Int> = mapOf(
        "dwadzieścia" to 20, "dwadziescia" to 20, "trzydzieści" to 30, "trzydziesci" to 30, "czterdzieści" to 40,
        "czterdziesci" to 40, "pięćdziesiąt" to 50, "piecdziesiat" to 50, "sześćdziesiąt" to 60,
        "szescdziesiat" to 60, "siedemdziesiąt" to 70, "siedemdziesiat" to 70, "osiemdziesiąt" to 80,
        "osiemdziesiat" to 80, "dziewięćdziesiąt" to 90, "dziewiecdziesiat" to 90, "sto" to 100,
        "dwudziestu" to 20, "trzydziestu" to 30, "czterdziestu" to 40, "pięćdziesięciu" to 50,
        "sześćdziesięciu" to 60, "siedemdziesięciu" to 70, "osiemdziesięciu" to 80, "dziewięćdziesięciu" to 90,
        "stu" to 100,
    )

    /** Composed (NFC), lowercase, "%" -> "procent", every character that is not a letter or digit splits
     *  tokens. Diacritics stay as they are. */
    fun tokens(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
        .replace("%", " procent ")
        .replace(NON_WORD, " ")
        .split(WHITESPACE)
        .filter { it.isNotBlank() }

    /** [tokens] with every number word written as digits, tens and units composed:
     *  "dwadzieścia dwa" -> "22", "pięćdziesięciu" -> "50". Other words pass unchanged. */
    fun digits(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val tens = TENS[t]
            val unit = tokens.getOrNull(i + 1)?.let { UNITS[it] }
            when {
                tens != null && tens < HUNDRED && unit != null && unit > 0 -> { out.add((tens + unit).toString()); i++ }
                tens != null -> out.add(tens.toString())
                else -> out.add(((TEENS[t] ?: UNITS[t])?.toString()) ?: t)
            }
            i++
        }
        return out
    }

    private val ALL_TARGETS = setOf("wszystko", "wszystkiego")

    /** A short order whose only object is "everything" ("zamknij wszystko"): it names no control,
     *  so it is refused and never guessed. [tokens] still holds the fillers. */
    fun isBareAll(tokens: List<String>): Boolean {
        val core = tokens.filterNot { it in FILLERS }
        return core.size <= 3 && core.any { it in ALL_TARGETS }
    }

    /** The number a digit token stands for, or null for any other word. */
    fun number(token: String): Int? =
        token.takeIf { it.length <= MAX_DIGITS && it.all { c -> c in '0'..'9' } }?.toInt()
}

/**
 * Polish [VoiceTextRules]: [PolishVoiceNormalizer], "i" / "oraz" / "a także" as connectors, and the
 * check that tells a harmless unknown phrase from a request to the car that was rejected.
 */
object PolishVoiceTextRules : VoiceTextRules {

    override val language = VoiceLanguage.PL

    override val fillers: Set<String> get() = PolishVoiceNormalizer.FILLERS

    override val reservedWords: Set<String> =
        PolishVoiceNormalizer.FILLERS + PolishVoiceNormalizer.NEGATIONS + PolishVoiceNormalizer.QUOTE_WORDS

    override val refusesRejectedCommands = true

    override fun tokens(text: String): List<String> = PolishVoiceNormalizer.tokens(text)

    /** Politeness that only counts at the very start: "czy możesz ..." (a request, not the filler
     *  "możesz" mid-phrase) and "chciałbym ...". Anywhere else these words stay and the phrase
     *  no longer equals a template. */
    private val OPENERS_WITH_ABILITY = setOf("możesz", "mozesz", "mógłbyś", "moglbys", "mogłabyś", "moglabys")
    private val OPENERS_WISH = setOf("chciałbym", "chcialbym", "chciałabym", "chcialabym")

    override fun words(text: String): List<String> {
        val tokens = tokens(text)
        val rest = when {
            tokens.firstOrNull() == "czy" && tokens.getOrNull(1) in OPENERS_WITH_ABILITY -> tokens.drop(2)
            tokens.firstOrNull() in OPENERS_WISH -> tokens.drop(1)
            else -> tokens
        }
        return digits(rest.filterNot { it in fillers })
    }

    override fun digits(tokens: List<String>): List<String> = PolishVoiceNormalizer.digits(tokens)

    override fun number(token: String): Int? = PolishVoiceNormalizer.number(token)

    override fun splitCompound(words: List<String>): List<List<String>> {
        val parts = mutableListOf(mutableListOf<String>())
        var i = 0
        while (i < words.size) {
            val also = words[i] == "a" && words.getOrNull(i + 1).let { it == "także" || it == "takze" }
            val andThen = words[i] == "i" && words.getOrNull(i + 1) == "potem"
            val connector = words[i] == "i" || words[i] == "oraz" || words[i] == "potem" || also
            if (connector) parts.add(mutableListOf()) else parts.last().add(words[i])
            i += if (also || andThen) 2 else 1
        }
        return parts
    }

    private const val QUOTE_MARKS = "\"„”“«»"

    override fun isQuoted(text: String): Boolean = text.any { it in QUOTE_MARKS }

    // Stems of the imperative, infinitive and "-aj" forms of every action word the grammar knows, and
    // of the vehicle nouns it names, plus the unsupported ones. Prefix matching here only widens what
    // is refused, never what is run: dispatch needs a whole-phrase dictionary match.
    private val VERB_STEMS = listOf(
        "otwórz", "otworz", "otwier", "otwórzcie", "zamkn", "zamyk", "uchyl", "przewietrz", "wywietrz", "ustaw", "zrób", "zrob",
        "włącz", "wlacz", "wyłącz", "wylacz", "zgaś", "zgas", "podnieś", "podnies", "opuść", "opusc", "podgrzej", "podgrzewaj", "ogrzej", "ogrzewaj",
        "ochłodź", "ochlodz", "schłodź", "schlodz", "zwiększ", "zwieksz", "zmniejsz", "ścisz", "scisz", "przycisz", "wycisz",
        "podgłośn", "podglosn", "odblokuj", "zablokuj", "odemknij", "zaryglu", "uruchom", "odpal", "przełącz", "przelacz",
        "zmień", "zmien", "aktywuj", "dezaktywuj", "złóż", "zloz", "rozłóż", "rozloz", "zapal", "puść", "pusc", "zagraj", "nawiguj",
        "obniż", "obniz", "zasłoń", "zasłon", "zaslon", "odsłoń", "odsłon", "odslon", "podwyższ", "podwyzsz",
    )
    // Whole words only: "jedz" as a prefix would take "jedzenie".
    private val VERB_WORDS = setOf("prowadź", "prowadz", "jedź", "jedz", "jedźmy", "jedzmy")

    private val TERM_STEMS = listOf(
        "okn", "okien", "szyb", "szyberdach", "werdach", "dach", "rolet", "zasłon", "zaslon", "klim", "temperatur", "stopn", "stopie", "siedzen", "siedzeń",
        "fotel", "foteli", "podgrzew", "ogrzew", "wentyl", "nawiew", "nadmuch", "kierown", "luster", "głośn", "glosn", "dźwięk",
        "dzwiek", "yandex", "yandeks", "jandex", "jandeks", "bagażnik", "bagaznik", "drzwi", "zamki", "zamek", "zamków", "samochód", "samochod", "auto", "pojazd", "świat",
        "swiat", "oświetl", "oswietl", "lamp", "ambient", "podświetl", "podswietl", "drl", "hud", "wyświetl", "wyswietl",
        "projekc", "tryb", "recyrk", "obieg", "odmgl", "odpar", "odmraż", "odmraz", "cieplej", "chłodn", "chlodn", "zimn",
        "gorąc", "gorac", "duszn", "zmarz", "bateri", "akumulat", "zasięg", "zasieg", "naładow", "naladow", "procent",
        "mask", "silnik", "hamul", "klakson", "wycieracz", "reflektor", "kierunkowsk", "awaryjn", "pas", "tempomat",
        "autopilot", "ładowan", "ladowan", "zawiesz", "napęd", "naped", "masaż", "masaz", "klap", "szyb", "ciszej", "głośniej",
        "glosniej", "kabin", "wnętrz", "wnetrz", "salon",
        "grzan", "chłodz", "chlodz", "dworz", "zewn",
    )

    private fun isVerb(token: String) = token in VERB_WORDS || VERB_STEMS.any { token.startsWith(it) }

    private fun isTerm(token: String) = TERM_STEMS.any { token.startsWith(it) }

    /** "nie" glued to a command verb ("nieotwieraj"): the speech engine dropped the space. */
    private fun isGluedNegation(token: String) =
        token.length > 3 && token.startsWith("nie") && isVerb(token.removePrefix("nie"))

    override fun commandLikeRefusal(text: String): String? {
        val tokens = PolishVoiceNormalizer.tokens(text)
        val verb = tokens.any { isVerb(it) || isGluedNegation(it) }
        val term = tokens.any { isTerm(it) }
        if (!verb && !term) return null
        return when {
            isQuoted(text) || tokens.any { it in PolishVoiceNormalizer.QUOTE_WORDS } -> NluRefusal.QUOTED
            tokens.any { it in PolishVoiceNormalizer.NEGATIONS || isGluedNegation(it) } -> NluRefusal.NEGATED
            verb && term -> NluRefusal.UNSUPPORTED
            verb && PolishVoiceNormalizer.isBareAll(words(text)) -> NluRefusal.UNSUPPORTED
            else -> null
        }
    }
}
