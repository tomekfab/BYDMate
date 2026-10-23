package com.bydmate.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptTest {

    @Test fun prompt_mentions_places_tools() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("list_places"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("create_place"))
    }

    @Test fun prompt_instructs_the_shared_agent_to_reply_in_polish() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("Odpowiadaj po polsku"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("vehicle_control"))
    }

    @Test fun prompt_pins_unknown_semantics() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("NIEZNANY"))
    }

    @Test fun prompt_has_compound_few_shot() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("seat_heat_driver_3"))
    }

    // #254: a Song Plus log showed the model picking seat level 3 for a level-less seat request;
    // that car only takes levels 1-2, so a level-less request must default to 1 like the offline voice.
    @Test fun prompt_defaults_unnamed_seat_level_to_one() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("bez poziomu ustaw na poziom 1"))
    }

    @Test fun prompt_explains_exact_time_trigger() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("time_range"))
    }

    // Terse-while-driving is a static rule about a tag on the user turn: keeping it out of the
    // system prompt is what makes the cached prefix identical between turns.
    @Test fun prompt_has_static_moving_rule() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains(AgentOrchestrator.MOVING_TAG))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("jednym potwierdzeniem"))
    }

    // The app only sees the electric side of the car (some models in the fleet are hybrids),
    // so the prompt must scope the data instead of declaring the powertrain.
    @Test fun prompt_scopes_data_to_electric_side() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("Nie omawiaj rodzaju napędu"))
        assertFalse(AgentOrchestrator.SYSTEM_PROMPT.contains("Nie nazywaj samochodu hybrydą"))
    }

    // On-car log 25.09: the model named the time, villages and a "never asked" from thin air.
    @Test fun prompt_takes_time_only_from_the_tag() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("(${AgentOrchestrator.TIME_TAG_PREFIX} GG:MM)"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("Podawaj czas tylko z tego znacznika"))
    }

    @Test fun prompt_names_places_only_from_where_am_i() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("where_am_i"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("Nie zgaduj i nie wyszukuj współrzędnych przez web_search"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("nie wiesz, gdzie jesteśmy"))
    }

    // Review of 2c994429: a live fix's hedge must not depend on current speed - zero speed does
    // not prove the car has been parked since the fix.
    @Test fun prompt_hedges_stale_fix_regardless_of_current_speed() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("fix_note"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("where_am_i"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("także gdy samochód stoi"))
    }

    @Test fun prompt_admits_forgetting_instead_of_denying() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("że go nie pamiętasz"))
    }

    @Test fun prompt_explains_pending_confirmation_status() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("oczekuje potwierdzenia na ekranie"))
    }

    @Test fun prompt_refuses_steering_key_trigger_by_voice() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("przycisk kierownicy"))
    }

    // Wave 4: an automation built on a guessed threshold is worse than a question.
    @Test fun prompt_forbids_guessing_automation_details() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("JEDNO pytanie przed utworzeniem reguły"))
    }

    @Test fun prompt_tells_the_model_to_read_the_created_rule_back() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("zwraca pole rule"))
    }

    @Test fun prompt_applies_stored_comfort_preferences() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("ustawienie komfortu"))
    }

    @Test fun prompt_mentions_driver_memory_tools() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("remember_fact"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("forget_fact"))
    }

    // Live charger status: the model must not claim a free connector the source does not know,
    // must say the distance is direct and must route to the pick by its coordinates.
    @Test fun prompt_explains_charger_answers() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("find_chargers"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("status_known=false"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("w linii prostej"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("najpierw poproś kierowcę o wybór stacji"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("Potem użyj navigate_to"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("z jej lat i lon"))
    }
}
