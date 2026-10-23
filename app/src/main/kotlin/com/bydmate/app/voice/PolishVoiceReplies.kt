package com.bydmate.app.voice

import com.bydmate.app.data.remote.DiParsData
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Everything the car says in Polish for the local (offline) path: question answers with numbers
 * and units, persona outcomes, errors, help and session phrases. Pure Kotlin, no Android deps,
 * and no text here is ever run through a stress marker.
 */
object PolishVoiceReplies {

    // --- numbers and units ---

    /** The Polish form of a noun after [n] >= 0: 1 stopień, 2 stopnie, 5 stopni, 12 stopni,
     *  21 stopni, 22 stopnie. Unlike Russian, only 1 itself takes the singular. */

    /** The Polish sentence for [question], or null when the car does not report the value (the
     *  caller then decides, exactly as for Russian). A charge or setpoint of 0 is a cold-start
     *  reading, not a value. */
    fun answer(question: VehicleQuestion, d: DiParsData?, rangeKm: Double?): String? = when (question) {
        VehicleQuestion.OUTSIDE_TEMP -> d?.exteriorTemp?.let { "Na zewnątrz jest ${polishDegrees(it)}." }
        VehicleQuestion.CABIN_TEMP -> d?.insideTemp?.let { "W kabinie jest ${polishDegrees(it)}." }
        VehicleQuestion.SOC -> d?.soc?.takeIf { it in 1..100 }?.let { "Poziom baterii wynosi $it procent." }
        VehicleQuestion.RANGE -> rangeKm?.roundToInt()
            ?.let { "Zasięg wynosi $it ${plural(it, "kilometr", "kilometry", "kilometrów")}." }
        VehicleQuestion.AC_TEMP -> d?.acTemp?.takeIf { it > 0 }
            ?.let { "Klimatyzacja jest ustawiona na ${polishDegrees(it)}." }
    }

    /** What to say when the car reports no value for [question]. */
    fun unavailable(question: VehicleQuestion): String = when (question) {
        VehicleQuestion.OUTSIDE_TEMP -> "Nie mam odczytu temperatury na zewnątrz."
        VehicleQuestion.CABIN_TEMP -> "Nie mam odczytu temperatury w kabinie."
        VehicleQuestion.SOC -> "Nie mam odczytu poziomu baterii."
        VehicleQuestion.RANGE -> "Nie mam odczytu zasięgu."
        VehicleQuestion.AC_TEMP -> "Nie mam odczytu ustawienia klimatyzacji."
    }

    fun persona(persona: AgentPersona, spoken: String, random: Random, gender: TtsGender): String =
        PolishPersonaReplies.persona(persona, spoken, random, gender)

    fun filler(persona: AgentPersona, random: Random): String = PolishPersonaReplies.filler(persona, random)

    fun personaPhrases(persona: AgentPersona, gender: TtsGender): List<String> =
        PolishPersonaReplies.personaPhrases(persona, gender)

    private val STYLE = mapOf(
        AgentPersona.SNARKY to "Jesteś złośliwym, sarkastycznym kumplem. Ironizuj, dogryzaj, odpowiadaj krótko i z " +
            "charakterem; dozwolone są lekko kolokwialne słówka. Nie obrażaj kierowcy na serio. Fakty i wyniki " +
            "poleceń podawaj dokładnie.",
        AgentPersona.NAVIGATOR to "Jesteś spokojnym, uprzejmym pilotem-towarzyszem. Przyjazny profesjonalista: " +
            "podpowiesz, wesprzesz, czasem trafnie zażartujesz.",
        AgentPersona.ENGINEER to "Jesteś niewzruszonym inżynierem pokładowym. Mówisz bardzo krótko i technicznie: " +
            "tylko sedno, liczby i status. Suchy humor, rzadko.",
    )
    private const val SMALL_TALK = "Możesz podtrzymać krótką rozmowę o samochodzie i tematach pokrewnych oraz " +
        "opowiedzieć dowcip, jeśli ktoś poprosi. Sterowanie samochodem jest zawsze ważniejsze od rozmowy: jeśli " +
        "w wypowiedzi jest polecenie, najpierw je wykonaj. Styl nie oznacza dłuższych odpowiedzi: 1-2 krótkie " +
        "zdania. Odpowiadaj po polsku."

    /** The Polish counterpart of [AgentPersonaPrompt.block]: appended after the base prompt. */
    fun personaPrompt(identity: AgentIdentity): String = buildString {
        append("\nCHARAKTER: ").append(STYLE.getValue(identity.persona))
        if (identity.name.isNotBlank()) {
            append("\nNazywasz się ").append(identity.name)
                .append(". Jeśli ktoś zapyta, jak masz na imię, podaj to imię.")
        }
        append("\n").append(SMALL_TALK)
        append(
            when (identity.gender) {
                TtsGender.MALE -> "\nMówisz o sobie w rodzaju męskim (zrobiłem, włączyłem)."
                TtsGender.FEMALE -> "\nMówisz o sobie w rodzaju żeńskim (zrobiłam, włączyłam)."
            }
        )
    }

    // --- errors, help, session phrases ---

    enum class Problem {
        NO_CONNECTION, AI_DISABLED, ASSISTANT_UNAVAILABLE, PAIRING_REQUIRED, APPROVAL_REQUIRED,
        MODEL_MISSING, VOICE_MISSING, UNSUPPORTED, SPEED_UNKNOWN, AC_TEMP_UNKNOWN, INTERNAL,
    }

    fun problem(problem: Problem): String = when (problem) {
        Problem.NO_CONNECTION -> "Brak połączenia z serwerem, powiedz prostą komendę."
        Problem.AI_DISABLED -> "Asystent online jest wyłączony. Powiedz jedną z obsługiwanych komend."
        Problem.ASSISTANT_UNAVAILABLE -> "Asystent jest teraz niedostępny. Spróbuj później albo powiedz prostą komendę."
        Problem.PAIRING_REQUIRED -> "Asystent wymaga sparowania w ustawieniach."
        Problem.APPROVAL_REQUIRED -> "Urządzenie czeka na zatwierdzenie w asystencie."
        Problem.MODEL_MISSING -> "Model rozpoznawania mowy nie jest gotowy. Pobierz go w ustawieniach."
        Problem.VOICE_MISSING -> "Głos nie jest gotowy. Pobierz go w ustawieniach."
        Problem.UNSUPPORTED -> "Tej komendy nie obsługuję."
        Problem.SPEED_UNKNOWN -> "Nie znam prędkości samochodu, więc nie otworzę okna ani szyberdachu."
        Problem.AC_TEMP_UNKNOWN -> "Nie znam aktualnej temperatury klimatyzacji."
        Problem.INTERNAL -> "Wystąpił błąd wewnętrzny."
    }

    /** What the driver can ask for in Polish: only the groups the Polish grammar supports. Windows
     *  and the sunroof are promised as open/close, never as an exact percentage, because several
     *  commands map to presets. */
    fun help(): String =
        "Mogę otwierać i zamykać okna, sterować szyberdachem i roletą, ustawiać klimatyzację i " +
            "ogrzewanie foteli, zmieniać głośność oraz podawać temperaturę, poziom baterii i zasięg."

    enum class Session { LISTENING, THINKING, ASK_WHAT_TO_DO, REPEAT, CANCELLED, SESSION_ENDED }

    fun session(phrase: Session): String = when (phrase) {
        Session.LISTENING -> "Słucham."
        Session.THINKING -> "Myślę."
        Session.ASK_WHAT_TO_DO -> "Co mam zrobić?"
        Session.REPEAT -> "Powtórz, proszę."
        Session.CANCELLED -> "Anulowano."
        Session.SESSION_ENDED -> "Koniec rozmowy."
    }

    // --- command names (settings, journal, help) ---

    private val COMMAND_NAMES: Map<String, String> = mapOf(
        "windows_open_all" to "otworzyć wszystkie okna",
        "windows_close_all" to "zamknąć wszystkie okna",
        "windows_vent_all" to "uchylić wszystkie okna",
        "windows_half_all" to "otworzyć wszystkie okna do połowy",
        "windows_open_front" to "otworzyć przednie okna",
        "windows_close_front" to "zamknąć przednie okna",
        "windows_open_rear" to "otworzyć tylne okna",
        "windows_close_rear" to "zamknąć tylne okna",
        "windows_half_front" to "otworzyć przednie okna do połowy",
        "windows_vent_front" to "uchylić przednie okna",
        "windows_half_rear" to "otworzyć tylne okna do połowy",
        "windows_vent_rear" to "uchylić tylne okna",
        "window_driver_open" to "otworzyć okno kierowcy",
        "window_driver_close" to "zamknąć okno kierowcy",
        "window_passenger_open" to "otworzyć okno pasażera z przodu",
        "window_passenger_close" to "zamknąć okno pasażera z przodu",
        "window_rear_left_open" to "otworzyć tylne lewe okno",
        "window_rear_left_close" to "zamknąć tylne lewe okno",
        "window_rear_right_open" to "otworzyć tylne prawe okno",
        "window_rear_right_close" to "zamknąć tylne prawe okno",
        "window_driver_vent" to "uchylić okno kierowcy",
        "window_passenger_vent" to "uchylić okno pasażera z przodu",
        "window_rear_left_vent" to "uchylić tylne lewe okno",
        "window_rear_right_vent" to "uchylić tylne prawe okno",
        "window_driver_half" to "otworzyć okno kierowcy do połowy",
        "window_passenger_half" to "otworzyć okno pasażera z przodu do połowy",
        "window_rear_left_half" to "otworzyć tylne lewe okno do połowy",
        "window_rear_right_half" to "otworzyć tylne prawe okno do połowy",
        "ac_on" to "włączyć automatyczną klimatyzację",
        "ac_off" to "wyłączyć klimatyzację",
        "ac_flow_on" to "włączyć wentylację klimatyzacji (nawiew bez chłodzenia)",
        "ac_wind_face" to "nawiew na twarz",
        "ac_wind_face_feet" to "nawiew na twarz i nogi",
        "ac_wind_feet" to "nawiew na nogi",
        "ac_wind_feet_windshield" to "nawiew na nogi i szybę",
        "ac_wind_windshield" to "nawiew na szybę",
        "ac_recirc_inner" to "włączyć obieg wewnętrzny powietrza",
        "ac_recirc_outer" to "włączyć obieg zewnętrzny powietrza",
        "defrost_front_on" to "włączyć nawiew na przednią szybę",
        "defrost_front_off" to "wyłączyć nawiew na przednią szybę",
        "ac_auto_on" to "włączyć tryb automatyczny klimatyzacji",
        "ac_auto_off" to "włączyć ręczny tryb klimatyzacji",
        "mirror_heat_on" to "włączyć ogrzewanie lusterek",
        "mirror_heat_off" to "wyłączyć ogrzewanie lusterek",
        "steering_heat_on" to "włączyć ogrzewanie kierownicy",
        "steering_heat_off" to "wyłączyć ogrzewanie kierownicy",
        "light_ambient_on" to "włączyć oświetlenie ambient",
        "light_ambient_off" to "wyłączyć oświetlenie ambient",
        "light_drl_on" to "włączyć światła do jazdy dziennej",
        "light_drl_off" to "wyłączyć światła do jazdy dziennej",
        "hazard_on" to "włączyć światła awaryjne",
        "hazard_off" to "wyłączyć światła awaryjne",
        "light_interior_on" to "włączyć oświetlenie wnętrza",
        "light_interior_off" to "wyłączyć oświetlenie wnętrza",
        "hud_on" to "włączyć wyświetlacz HUD",
        "hud_off" to "wyłączyć wyświetlacz HUD",
        "battery_preheat_on" to "włączyć podgrzewanie baterii",
        "battery_preheat_off" to "wyłączyć podgrzewanie baterii",
        "doors_lock" to "zablokować drzwi",
        "doors_unlock" to "odblokować drzwi",
        "sunroof_open" to "otworzyć szyberdach",
        "sunroof_half" to "otworzyć szyberdach do połowy",
        "sunroof_close" to "zamknąć szyberdach",
        "sunroof_stop" to "zatrzymać szyberdach",
        "sunroof_updip" to "uchylić szyberdach",
        "sunroof_comfort" to "ustawić szyberdach w pozycji komfortowej",
        "sunshade_open" to "otworzyć roletę",
        "sunshade_close" to "zamknąć roletę",
        "trunk_open" to "otworzyć bagażnik",
        "trunk_close" to "zamknąć bagażnik",
        "front_trunk_open" to "otworzyć przedni bagażnik",
        "front_trunk_close" to "zamknąć przedni bagażnik",
        "fridge_cool" to "włączyć chłodzenie w lodówce",
        "fridge_heat" to "włączyć podgrzewanie w lodówce",
        "fridge_off" to "wyłączyć lodówkę",
        "drive_mode_eco" to "włączyć tryb jazdy ECO",
        "drive_mode_normal" to "włączyć normalny tryb jazdy",
        "drive_mode_sport" to "włączyć tryb jazdy Sport",
        "drive_mode_snow" to "włączyć tryb jazdy Śnieg",
        "drive_mode_sand" to "włączyć tryb jazdy Piasek",
        "drive_mode_mud" to "włączyć tryb jazdy Błoto",
        "drive_mode_mountain" to "włączyć tryb jazdy Góry",
        "drive_mode_rock" to "włączyć tryb jazdy Skały",
        "drive_mode_smart" to "włączyć inteligentny tryb jazdy",
    ) + polishSeatNames()

    /** The Polish action name for a catalog command id (infinitive, like the Russian names), with
     *  [value] spoken for ranged commands; null for an id this module does not know. */
    fun commandName(id: String, value: Int? = null): String? = when (id) {
        "ac_set_temp" -> value?.let { "ustawić temperaturę klimatyzacji na ${polishDegrees(it)}" }
            ?: "ustawić temperaturę klimatyzacji"
        "ac_fan_level" -> value?.let { "ustawić wentylator na poziom $it" } ?: "ustawić poziom wentylatora"
        else -> COMMAND_NAMES[id]
    }
}

private object PolishPersonaReplies {
    // --- persona outcomes ---

    /** A spoken phrase with a feminine form; [f] defaults to [m] when no verb is gendered. */
    private class Phrase(val m: String, val f: String = m) {
        fun forGender(gender: TtsGender) = if (gender == TtsGender.FEMALE) f else m
    }

    private fun ph(vararg phrases: Any): List<Phrase> =
        phrases.map { if (it is Phrase) it else Phrase(it as String) }

    // The pool keys are the canonical outcomes announced by the controller.
    private const val DONE = "Gotowe"
    private const val FAILED = "Nie udało się"
    private const val NOT_UNDERSTOOD = "Nie rozumiem"
    private const val ERROR = "Błąd"
    private const val RUNNING = "Wykonuję"

    private val RUNNING_POOL = ph("Wykonuję.", "Już robię.")

    private val SNARKY_POOLS = mapOf(
        DONE to ph("Gotowe, no.", Phrase("Zrobiłem. Cud, prawda?", "Zrobiłam. Cud, prawda?"), "Jest. Nie dziękuj.",
            Phrase("No zrobiłem, zrobiłem.", "No zrobiłam, zrobiłam."), "Proszę bardzo, działa."),
        FAILED to ph("Figa z tego. Nie wyszło.", "Zonk. Samochód się zaparł.", "Nie wyszło, cholera.", "Nie. Po prostu nie."),
        NOT_UNDERSTOOD to ph("Czego?", "Co to było?", "Jeszcze raz, po ludzku."),
        ERROR to ph("Wszystko się zepsuło. Pięknie.", "Znowu błąd. No super."),
        RUNNING to RUNNING_POOL,
    )
    private val NAVIGATOR_POOLS = mapOf(
        DONE to ph("Gotowe. Coś jeszcze?", "Zrobione, dowódco.",
            Phrase("Wykonałem. Szerokiej drogi.", "Wykonałam. Szerokiej drogi."), "Gotowe."),
        FAILED to ph("Nie udało się. Spróbujmy inaczej.", "Nie wyszło, spróbujemy jeszcze raz."),
        NOT_UNDERSTOOD to ph(Phrase("Nie dosłyszałem. Powtórz, proszę.", "Nie dosłyszałam. Powtórz, proszę."),
            Phrase("Nie zrozumiałem, powiedz inaczej.", "Nie zrozumiałam, powiedz inaczej.")),
        ERROR to ph("Wystąpił błąd. Zajmiemy się tym.", "Coś poszło nie tak."),
        RUNNING to RUNNING_POOL,
    )
    private val ENGINEER_POOLS = mapOf(
        DONE to ph("Jest.", "Wykonano.", "Przyjęto. Zrobione.", "Gotowe."),
        FAILED to ph("Odmowa. System nie odpowiedział.", "Nie wykonano."),
        NOT_UNDERSTOOD to ph(Phrase("Nie rozróżniłem. Powtórz.", "Nie rozróżniłam. Powtórz."), "Polecenie niejasne. Powtórz."),
        ERROR to ph("Awaria. Szczegóły w dzienniku.", "Błąd systemu."),
        RUNNING to RUNNING_POOL,
    )

    private fun pools(p: AgentPersona): Map<String, List<Phrase>> = when (p) {
        AgentPersona.SNARKY -> SNARKY_POOLS
        AgentPersona.NAVIGATOR -> NAVIGATOR_POOLS
        AgentPersona.ENGINEER -> ENGINEER_POOLS
    }

    private val FILLER_POOLS = mapOf(
        AgentPersona.NAVIGATOR to listOf("Sprawdzam.", "Chwileczkę, już patrzę.", "Moment.", "Już sprawdzam."),
        AgentPersona.SNARKY to listOf("Zaraz zerknę.", "Chwila, patrzę.", "Sekundę, nie poganiaj.", "Moment, szukam."),
        AgentPersona.ENGINEER to listOf("Zapytanie przyjęte.", "Sprawdzam.", "Przetwarzam.", "Sekundę."),
    )

    /** Maps a canonical outcome word to a random Polish phrase of [persona]; any other text (an
     *  answer that is already Polish) passes through unchanged. */
    fun persona(persona: AgentPersona, spoken: String, random: Random, gender: TtsGender): String =
        pools(persona)[spoken]?.random(random)?.forGender(gender) ?: spoken

    fun filler(persona: AgentPersona, random: Random): String = FILLER_POOLS.getValue(persona).random(random)

    /** Every phrase [persona] can speak in Polish, for the TTS precache and tests. */
    fun personaPhrases(persona: AgentPersona, gender: TtsGender): List<String> =
        pools(persona).values.flatten().map { it.forGender(gender) } + FILLER_POOLS.getValue(persona)

}

private fun polishDegrees(t: Int): String {
    val n = abs(t)
    val said = "$n ${PolishVoiceReplies.plural(n, "stopień", "stopnie", "stopni")}"
    return if (t < 0) "minus $said" else said
}

private fun polishSeatNames(): Map<String, String> = buildMap {
    for ((who, genitive) in listOf("driver" to "kierowcy", "passenger" to "pasażera")) {
        for (level in 1..5) {
            put("seat_heat_${who}_$level", "włączyć ogrzewanie fotela $genitive, poziom $level")
            put("seat_vent_${who}_$level", "włączyć wentylację fotela $genitive, poziom $level")
        }
        put("seat_heat_${who}_off", "wyłączyć ogrzewanie fotela $genitive")
        put("seat_vent_${who}_off", "wyłączyć wentylację fotela $genitive")
    }
}

internal fun PolishVoiceReplies.plural(n: Int, one: String, few: String, many: String): String = when {
    n == 1 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}
