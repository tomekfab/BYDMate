package com.bydmate.app.agent

/**
 * What the agent is expected to do with one driver utterance.
 *
 * [ACT] — a tool call is the correct answer, and [tool] / [args] say which one.
 * [NO_ACT] — talking, refusing, thinking out loud or taking it back: the agent must answer in
 * words and touch nothing in the car. [AUTOMATION] — a rule is being discussed; either
 * create_automation with the right shape, or, for the trap cases, ONE clarifying question and no
 * rule at all (expressed as tool = null).
 */
internal enum class EvalKind { ACT, NO_ACT, AUTOMATION }

internal data class EvalCase(
    val utterance: String,
    val kind: EvalKind,
    /** Tool the model must call first; null means "no tool call at all". */
    val tool: String? = null,
    /** Substrings that must appear in the tool arguments (command ids, field names). */
    val args: List<String> = emptyList(),
    /** Why this case is in the set — what it would catch if it regressed. */
    val note: String,
)

/**
 * The eval set: real phrasings a driver uses, in the register they use it in. Half of the value
 * is in the cases where the right move is to do nothing — those are the ones that turn into
 * complaints where the assistant says it is done although no action was requested.
 */
internal object AgentEvalCases {

    val ALL: List<EvalCase> = listOf(
        // ── Act: the driver asked for something concrete ──────────────────────────
        EvalCase("zamknij okna", EvalKind.ACT, "vehicle_control", listOf("windows_close_all"),
            note = "the single most common command; must not become a per-window loop"),
        EvalCase("uchyl okno kierowcy do wietrzenia", EvalKind.ACT, "vehicle_control",
            listOf("window_driver_vent"), note = "vent detent, not open"),
        EvalCase("otwórz do połowy okno kierowcy", EvalKind.ACT, "vehicle_control",
            listOf("window_driver_half"), note = "half detent on ONE window, not on all four"),
        EvalCase("uchyl tylne okna", EvalKind.ACT, "vehicle_control",
            listOf("windows_half_rear"), note = "half detent on the rear pair, not the whole car"),
        EvalCase("otwórz okno kierowcy na dwadzieścia procent", EvalKind.ACT, "vehicle_control",
            listOf("window_driver_vent"),
            note = "percentages do not exist: 20 % is nearest to the vent detent (10 %), " +
                "and the reply must say which position was used"),
        EvalCase("zrób cieplej, ustaw dwadzieścia dwa", EvalKind.ACT, "vehicle_control",
            listOf("ac_set_temp"), note = "value-carrying climate command"),
        EvalCase("włącz podgrzewanie siedzenia na trzy", EvalKind.ACT, "vehicle_control",
            listOf("seat_heat_driver_3"), note = "colloquial level; driver seat is the default"),
        EvalCase("włącz podgrzewanie kierowcy", EvalKind.ACT, "vehicle_control",
            listOf("seat_heat_driver_1"),
            note = "no level named: level 1 like the offline voice (#254, Song Plus takes only 1-2)"),
        EvalCase("włącz wentylację kierowcy", EvalKind.ACT, "vehicle_control",
            listOf("seat_vent_driver_1"), note = "no level named: level 1 (#254)"),
        EvalCase("podgrzej siedzenie pasażera", EvalKind.ACT, "vehicle_control",
            listOf("seat_heat_passenger_1"), note = "no level named: level 1 (#254)"),
        EvalCase("dmucha na nogi, przełącz na twarz", EvalKind.ACT, "vehicle_control",
            listOf("ac_wind_face"), note = "blow direction, wave 4 made it readable too"),
        EvalCase("zamknij szyberdach", EvalKind.ACT, "vehicle_control", listOf("sunroof_close"),
            note = "sunroof must not be confused with windows"),
        EvalCase("zamknij samochód", EvalKind.ACT, "vehicle_control", listOf("doors_lock"),
            note = "locks"),
        EvalCase("jaki jest poziom naładowania", EvalKind.ACT, "get_vehicle_state",
            note = "read, not guess: the number must come from the tool"),
        EvalCase("ile przejechałem w tym tygodniu", EvalKind.ACT, "query_trips",
            note = "trip statistics, not vehicle state"),
        EvalCase("ile wydałem na ładowanie w tym miesiącu", EvalKind.ACT, "query_charges",
            note = "charge statistics with a period"),
        EvalCase("jaka jutro będzie pogoda", EvalKind.ACT, "get_weather", note = "weather tool exists"),
        EvalCase("jedźmy do domu", EvalKind.ACT, "navigate_to", note = "navigation to a saved place"),
        EvalCase("jedźmy do pracy", EvalKind.ACT, "navigate_to", listOf("\"go\":true"),
            note = "the imperative to drive means START guidance: go=true, so the app presses Start itself"),
        EvalCase("wyznacz trasę do domu", EvalKind.ACT, "navigate_to", listOf("\"go\":false"),
            note = "a route-building request only builds it — the driver presses Start"),
        EvalCase("wyznacz trasę do Orszy", EvalKind.ACT, "navigate_to", listOf("\"go\":false"),
            note = "same split for a free-text destination, no auto-start"),
        EvalCase("znajdź ładowarkę w pobliżu", EvalKind.ACT, "find_chargers", note = "charger search"),
        EvalCase("włącz na YouTube podcast o samochodach", EvalKind.ACT, "youtube", listOf("podcast"),
            note = "media tool, query passed through"),
        EvalCase("wyświetl nawigację na zestawie wskaźników", EvalKind.ACT, "set_cluster_projection",
            note = "cluster projection, wave 3 made its result honest"),

        // ── Don't act: words, doubts and take-backs ───────────────────────────────
        EvalCase("co za korki dzisiaj", EvalKind.NO_ACT,
            note = "thinking out loud; nothing to do"),
        EvalCase("otwórz okno... jednak nie, jest zimno", EvalKind.NO_ACT,
            note = "take-back inside one utterance: the last word wins"),
        EvalCase("nie otwieraj okien", EvalKind.NO_ACT,
            note = "negation must not be read as the command it negates"),
        EvalCase("nie trzeba otwierać okien, zrobię to sam", EvalKind.NO_ACT,
            note = "negation with a command word at the front"),
        EvalCase("czy umiesz umyć samochód", EvalKind.NO_ACT,
            note = "capability question: answer honestly, invent nothing"),
        EvalCase("ile kosztuje benzyna", EvalKind.NO_ACT,
            note = "out of scope for an electric-side app; must not be answered from tools"),
        EvalCase("opowiedz dowcip", EvalKind.NO_ACT, note = "small talk, no tool"),
        EvalCase("przypomnij, o co prosiłem wczoraj", EvalKind.NO_ACT,
            note = "yesterday is deliberately not in the day memory (wave 5)"),

        // ── Automations, traps included ───────────────────────────────────────────
        EvalCase("utwórz regułę: jeśli poziom naładowania jest mniejszy niż dwadzieścia procent, zamknij wszystkie okna",
            EvalKind.AUTOMATION, "create_automation", listOf("SOC", "20", "windows_close_all"),
            note = "fully specified rule: trigger, threshold and action are all present"),
        EvalCase("ustaw tak, aby po wjeździe do domu włączała się klimatyzacja",
            EvalKind.AUTOMATION, "create_automation", listOf("place_enter"),
            note = "place trigger; the place has to be looked up, not invented"),
        EvalCase("w każdy dzień roboczy o 7:30 ogrzewaj kabinę", EvalKind.AUTOMATION,
            "create_automation", listOf("time_range", "07:30"),
            note = "schedule trigger with weekdays"),
        EvalCase("pokaż moje automatyzacje", EvalKind.AUTOMATION, "list_automations",
            note = "listing is not creating"),
        EvalCase("wyłącz automatyzację tryb nocny", EvalKind.AUTOMATION,
            "set_automation_enabled", listOf("tryb nocny"),
            note = "toggling an existing rule by name"),
        EvalCase("zrób coś, gdy zrobi się zimno", EvalKind.AUTOMATION, tool = null,
            note = "trap: no threshold and no action — ONE question, never a guessed rule"),
        EvalCase("ustaw automatyzację na niski poziom naładowania", EvalKind.AUTOMATION, tool = null,
            note = "trap: low is not a number — ask, do not pick 20 silently"),
    )

    val ACT = ALL.filter { it.kind == EvalKind.ACT }
    val NO_ACT = ALL.filter { it.kind == EvalKind.NO_ACT }
    val AUTOMATION = ALL.filter { it.kind == EvalKind.AUTOMATION }
}
