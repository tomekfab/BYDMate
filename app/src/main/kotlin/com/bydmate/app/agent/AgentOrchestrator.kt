package com.bydmate.app.agent

import android.util.Log
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.voice.AgentIdentity
import com.bydmate.app.voice.AgentPersona
import com.bydmate.app.voice.AgentPersonaPrompt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent loop: user text -> LLM (with tool schemas) -> execute tool calls ->
 * feed results back -> final text answer. Holds a short conversation memory
 * so follow-ups ("and close them") keep context: history is cleared [SESSION_TTL_MS]
 * after the last answer and trimmed to [MAX_HISTORY] messages. Single-flight
 * via Mutex — concurrent PTT + chat asks serialize, history stays consistent.
 */
@Singleton
@Suppress("TooManyFunctions") // the turn loop plus its small prompt/history helpers
class AgentOrchestrator @Inject constructor(
    private val backend: AgentBackend,
    private val tools: AgentTools,
    private val settingsRepository: SettingsRepository,
    private val isMoving: () -> Boolean = { false },
    private val identity: () -> AgentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
    private val memoryBlock: () -> String = { "" },
    private val dayMemory: DayMemory = DayMemory(prefs = null),
) {
    // Method injection keeps every existing constructor call intact. Null until Hilt injects it;
    // the car then counts as not a hybrid and gets the EV prompt.
    private var vehicleKindMonitor: VehicleKindMonitor? = null

    /** Called by Hilt after construction; call manually in unit tests of the hybrid prompt. */
    @Inject
    internal fun injectVehicleKind(monitor: VehicleKindMonitor) {
        vehicleKindMonitor = monitor
    }

    /** Test seam — deterministic clock for the session TTL. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Test seam — one line per traced step of the turn. Production writes to logcat under
     *  [TAG], which the log recorder captures, so a "the agent lied" report is diagnosable
     *  from the user's own log. */
    internal var trace: (String) -> Unit = { Log.i(TAG, it) }

    private val mutex = Mutex()
    private val history = mutableListOf<AgentMessage>()
    private var lastAnswerAt = 0L

    /** Warms the model host on push-to-talk, before the utterance is even recognized. */
    suspend fun prewarm() {
        if (settingsRepository.isAgentEnabled()) backend.prewarm()
    }

    /** [onFiller] gets the speech-only filler phrase (see runLoopTraced); it sits before
     *  [onSentence] so a trailing lambda keeps meaning the streamed answer sentences. */
    suspend fun ask(
        userText: String,
        onFiller: ((String) -> Unit)? = null,
        onSentence: ((String) -> Unit)? = null,
    ): AgentResult {
        mutex.withLock {
            if (!settingsRepository.isAgentEnabled()) {
                // Close the follow-up window: a disabled/unconfigured agent must not swallow NLU traffic.
                lastAnswerAt = 0L
                return AgentResult.Disabled
            }
            if (!backend.isConfigured()) {
                // Close the follow-up window: a disabled/unconfigured agent must not swallow NLU traffic.
                lastAnswerAt = 0L
                return AgentResult.Error("Asystent nie jest skonfigurowany: uzupełnij adres, klucz API i model w Ustawieniach > Integracjach")
            }
            val text = userText.trim()
            // Blank transcript: nothing to ask — degrade like a disabled agent.
            if (text.isEmpty()) return AgentResult.Disabled

            clearStaleHistory()
            removeTimeTagsFromHistory()
            val snapshot = history.toList()
            history += AgentMessage.User(withTimeTag(text) + if (isMoving()) " $MOVING_TAG" else "")
            try {
                trimHistory()
                val result = runLoop(
                    history, systemMessages(), tools.schemas(),
                    allowAutomationTools = true, onSentence, onFiller,
                    onTerminal = { lastAnswerAt = nowMs() },
                )
                // Only finished exchanges are worth keeping: an error or a disabled agent is
                // not something the driver referred to and must not come back tomorrow.
                if (result is AgentResult.Answer) dayMemory.record(text, result.text, nowMs())
                return result
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                // Roll history back to the entry snapshot: a cancelled turn must not leave an
                // unpaired tool_calls Assistant (the next ask would be rejected by the provider).
                history.clear()
                history.addAll(snapshot)
                throw ce
            }
        }
    }

    /** Fast-path (NLU) commands execute without ever going through [ask], so the agent's memory
     *  never learns about them — a follow-up such as "and now close it" would then miss the
     *  preceding "open the window". Call this right after a successful fast-path dispatch to backfill
     *  history with a synthetic note and refresh the TTL so the note (and the follow-up) survive. */
    suspend fun noteAction(text: String) {
        mutex.withLock {
            clearStaleHistory()
            history += AgentMessage.Assistant("[wykonano: $text]")
            trimHistory()
            lastAnswerAt = nowMs()
        }
    }

    /** True while the agent's LAST reply was a clarifying question ("Which window?")
     *  asked within [FOLLOW_UP_WINDOW_MS]: the driver's next utterance is the ANSWER
     *  and must go straight to [ask], not through NLU (which would turn "the driver's"
     *  into a seat command or a NotUnderstood and lose the pending question).
     *
     *  A trailing "?" alone is not enough: the agent also asks rhetorical questions AFTER
     *  it already acted ("I opened the window a little. Want it open again?"), and those
     *  must not open the window. So this walks [history] backwards from the last message to
     *  the most recent User message (the current turn); if any Assistant reply in that span
     *  carried a tool call, the turn already acted and the trailing "?" is rhetorical. */
    suspend fun expectsFollowUp(): Boolean = mutex.withLock {
        if (nowMs() - lastAnswerAt > FOLLOW_UP_WINDOW_MS) return false
        val last = history.lastOrNull() as? AgentMessage.Assistant ?: return false
        if (last.content?.trimEnd()?.endsWith("?") != true) return false
        for (i in history.indices.reversed()) {
            when (val msg = history[i]) {
                is AgentMessage.User -> return@withLock true
                is AgentMessage.Assistant -> if (msg.toolCalls.isNotEmpty()) return@withLock false
                is AgentMessage.Tool, is AgentMessage.System -> {}
            }
        }
        true
    }

    /** Automation-origin agent turn: single prompt, throwaway message list. Never touches the
     *  shared [history] or [lastAnswerAt] (no follow-up window, no context bleed into the live
     *  conversation), and refuses automation-management tools (see AgentTools.AUTOMATION_TOOLS).
     *  A live ask() in flight wins: tryLock instead of queueing, so a scheduled morning summary
     *  can never barge into the middle of a real dialog. */
    suspend fun askDetached(userText: String): AgentResult {
            if (!mutex.tryLock()) return AgentResult.Error("Asystent jest zajęty")
        try {
            if (!settingsRepository.isAgentEnabled()) return AgentResult.Disabled
            if (!backend.isConfigured()) return AgentResult.Error("Asystent nie jest skonfigurowany: uzupełnij adres, klucz API i model w Ustawieniach > Integracjach")
            val text = userText.trim()
            if (text.isEmpty()) return AgentResult.Disabled
            val messages = mutableListOf<AgentMessage>(AgentMessage.User(withTimeTag(text)))
            return runLoop(messages, systemMessages(includeMemory = false), tools.schemas(includeAutomationTools = false),
                allowAutomationTools = false, onSentence = null, onFiller = null, onTerminal = {})
        } finally {
            mutex.unlock()
        }
    }

    /** The driver's line plus the local clock time ([TIME_TAG_PREFIX]). The time rides on the
     *  user message, like [MOVING_TAG], because a per-minute value in the system messages would
     *  break the cached prefix on every turn. */
    private fun withTimeTag(text: String): String =
        "$text ($TIME_TAG_PREFIX " + SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(nowMs())) + ")"

    /** A clock value is useful only for the current utterance. Keeping it on an older turn
     * would let the model mistake a stale time for the current one. */
    private fun removeTimeTagsFromHistory() {
        for (index in history.indices) {
            val message = history[index] as? AgentMessage.User ?: continue
            history[index] = AgentMessage.User(message.content.replace(TIME_TAG_REGEX, ""))
        }
    }

    /**
     * Two system messages, and the split is the point: the first is [SYSTEM_PROMPT] and
     * nothing else, byte-identical on every turn of every day, so the provider can cache it
     * (the backend puts the cache breakpoint on exactly this message, where a breakpoint pays:
     * see LlmAgentBackend.cachesStaticPrefix). Everything that moves
     * — today's date, the persona, the driver facts — goes into the second one, after the
     * breakpoint. Per-turn state (clock time, driving or not) rides on the user message instead (see
     * [MOVING_TAG]). A detached turn (automation rule, not the driver) carries no memory —
     * neither the driver facts nor today's exchanges.
     */
    private fun systemMessages(includeMemory: Boolean = true): List<AgentMessage> {
        val dynamic = "Dzisiaj " + SimpleDateFormat("d MMMM yyyy, EEEE", Locale("pl"))
            .format(Date(nowMs())) + "." +
            AgentPersonaPrompt.block(identity()) +
            (if (includeMemory) memoryBlock() + dayMemory.promptBlock(nowMs()) else "")
        val static = if (vehicleKindMonitor?.kind?.value == VehicleKind.HYBRID) HYBRID_SYSTEM_PROMPT else SYSTEM_PROMPT
        return listOf(AgentMessage.System(static), AgentMessage.System(dynamic))
    }

    /** The LLM/tool loop shared by [ask] (live, persistent history) and [askDetached]
     *  (automation origin, throwaway messages). [onTerminal] fires exactly where the live
     *  path used to stamp lastAnswerAt. Caller must hold [mutex]. */
    @Suppress("LongParameterList") // the loop's inputs and callbacks, passed straight through
    private suspend fun runLoop(
        messages: MutableList<AgentMessage>,
        systemMessages: List<AgentMessage>,
        toolSchemas: JSONArray,
        allowAutomationTools: Boolean,
        onSentence: ((String) -> Unit)?,
        onFiller: ((String) -> Unit)?,
        onTerminal: () -> Unit,
    ): AgentResult {
        val startedAt = nowMs()
        val rounds = intArrayOf(0)
        val tracer = AgentTrace({ nowMs() }, toolNames(toolSchemas), { line -> trace(line) })
        tracer.started(Trace.cause(), detached = !allowAutomationTools)
        var outcome = "cancelled"
        try {
            val result = runLoopTraced(
                messages, systemMessages, toolSchemas, allowAutomationTools, onSentence, onFiller, onTerminal,
                rounds, tracer,
            )
            outcome = when (result) {
                is AgentResult.Answer -> "answer"
                is AgentResult.Error -> "error: " + AgentTrace.clip(result.message)
                AgentResult.Disabled -> "disabled"
            }
            return result
        } finally {
            tracer.turn(nowMs() - startedAt, rounds[0], outcome)
        }
    }

    /** The names offered to the model this turn (OpenAI tool schema: `function.name`), so the
     *  trace can tell a real tool call from a name the model invented. */
    private fun toolNames(schemas: JSONArray): Set<String> =
        (0 until schemas.length()).mapNotNullTo(mutableSetOf()) {
            schemas.optJSONObject(it)?.optJSONObject("function")?.optString("name")?.takeIf(String::isNotEmpty)
        }

    /** The answer as the driver gets it: trimmed, and marked with [TRUNCATED_MARK] when
     *  max_tokens cut it, so a reply that stops mid-sentence is visibly a cut and not a bug. */
    private fun finalAnswer(reply: AgentReply): String {
        val answer = reply.content?.trim().orEmpty()
        if (answer.isEmpty() || reply.finishReason != FINISH_LENGTH) return answer
        return if (answer.endsWith(TRUNCATED_MARK)) answer else answer + TRUNCATED_MARK
    }

    /** The loop itself; [rounds] carries the LLM round count out to the tracing wrapper. */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun runLoopTraced(
        messages: MutableList<AgentMessage>,
        systemMessages: List<AgentMessage>,
        toolSchemas: JSONArray,
        allowAutomationTools: Boolean,
        onSentence: ((String) -> Unit)?,
        onFiller: ((String) -> Unit)?,
        onTerminal: () -> Unit,
        rounds: IntArray,
        tracer: AgentTrace,
    ): AgentResult {
        val outcomes = mutableListOf<AgentToolOutcome>()
        val callCounts = mutableMapOf<String, Int>()
        var loopStrikes = 0
        // True once a sentence — the model's own streamed text via onSentence, or the filler
        // below via onFiller — has been handed out this turn; gates the filler so it never
        // doubles up on live speech and never fires twice. Lives outside the round loop: it
        // spans the whole turn.
        var spoken = false
        val speak: ((String) -> Unit)? = onSentence?.let { cb -> { s: String -> spoken = true; cb(s) } }
        repeat(MAX_ITERATIONS) {
            // Fresh chunker per LLM turn: a tool round's unterminated tail is discarded when
            // the chunker falls out of scope at the end of this iteration (only completed
            // sentences were forwarded); the final turn flushes its tail below.
            val chunker = if (speak != null) SentenceChunker() else null
            rounds[0]++
            tracer.roundStarted()
            val onDelta: ((String) -> Unit)? = if (speak != null && chunker != null) {
                { d -> tracer.delta(); chunker.feed(d).forEach(speak) }
            } else null
            val reply = backend
                .chat(systemMessages + messages, toolSchemas, onDelta)
                .getOrElse {
                    val message = (it as? LlmError)?.userMessage ?: "Brak połączenia z serwerem. Powiedz prostą komendę."
                    tracer.replyFailed(message)
                    return AgentResult.Error(message)
                }
            tracer.reply(reply)
            if (reply.toolCalls.isEmpty()) {
                val answer = finalAnswer(reply)
                if (answer.isEmpty()) return AgentResult.Error("Model nie zwrócił odpowiedzi")
                if (speak != null) chunker?.flush()?.let(speak)
                messages += AgentMessage.Assistant(answer)
                onTerminal()
                return AgentResult.Answer(answer, outcomes.toList())
            }
            messages += AgentMessage.Assistant(reply.content, reply.toolCalls)
            // Filler: this round is about to run a slow tool (search, weather, chargers, trip/
            // charge stats, range, navigation) and nothing has been spoken yet this turn — speak
            // a short persona phrase now, ahead of the tool result. Speech only: its own callback
            // (never the answer text stream), never added to messages/history and never part of
            // the returned answer.
            val slowCall = reply.toolCalls.firstOrNull { it.name in SLOW_TOOLS }
            if (onFiller != null && !spoken && slowCall != null) {
                val phrase = identity().persona.fillerPhrase()
                tracer.filler(phrase, slowCall.name)
                spoken = true
                onFiller(phrase)
            }
            for ((i, call) in reply.toolCalls.withIndex()) {
                val key = call.name + "|" + call.arguments
                val seen = callCounts.getOrDefault(key, 0)
                if (seen >= MAX_IDENTICAL_CALLS) {
                    // Loop guard: the model re-requests an identical call; feed it a synthetic
                    // error instead of executing, and give up after MAX_LOOP_STRIKES rounds.
                    loopStrikes++
                    tracer.tool(call, "loop-guard", 0L, "")
                    outcomes += AgentToolOutcome(call.name, false)
                    messages += AgentMessage.Tool(call.id, LOOP_ERROR)
                    if (loopStrikes >= MAX_LOOP_STRIKES) {
                        // The Assistant message just appended above carries every tool_call in
                        // this round; close out any calls after this one too, or the next ask()
                        // would replay an Assistant tool_call with no paired Tool message and
                        // the backend would reject it.
                        for (rest in reply.toolCalls.subList(i + 1, reply.toolCalls.size)) {
                            messages += AgentMessage.Tool(rest.id, LOOP_ERROR)
                        }
                        onTerminal()
                        return AgentResult.Error("Model zapętlił się. Spróbuj powiedzieć to inaczej.")
                    }
                    continue
                }
                callCounts[key] = seen + 1
                val toolStart = nowMs()
                val toolEvent = tracer.toolStarted(call)
                val res = withContext(Trace.causedBy(toolEvent)) { tools.execute(call, allowAutomationTools) }
                // ok = the tool JSON has no "error" key; unparseable output counts as ok
                // (free-form success payloads like web_search results are not errors).
                val ok = runCatching { !JSONObject(res).has("error") }.getOrDefault(true)
                tracer.tool(call, if (ok) "ok" else "error", nowMs() - toolStart, res)
                outcomes += AgentToolOutcome(call.name, ok)
                messages += AgentMessage.Tool(call.id, res)
            }
        }
        onTerminal()
        return AgentResult.Error("Łańcuch wywołań narzędzi jest zbyt długi")
    }

    /** Shared by [ask] and [noteAction] (both run under [mutex]) so they can't drift: a history
     *  older than [SESSION_TTL_MS] is stale and must be dropped before either appends to it —
     *  otherwise a fast-path note after the TTL expired would both resurrect the old conversation
     *  and refresh [lastAnswerAt], hiding the staleness from the next [ask]. */
    private fun clearStaleHistory() {
        if (nowMs() - lastAnswerAt > SESSION_TTL_MS) history.clear()
    }

    /** Trim from the front to at most [MAX_HISTORY]; when possible, cut exactly at the next User
     *  message so no orphan Assistant/Tool head remains (providers reject a tool message whose
     *  tool_calls assistant was dropped). The very last entry is never itself treated as that
     *  boundary — it was just appended by this very call ([ask]'s fresh User or [noteAction]'s
     *  fresh note) and must always survive the trim. If no User boundary exists in the region
     *  being dropped (e.g. a stretch of synthetic [noteAction] entries with no User message at
     *  all), fall back to a hard cap that just drops the oldest entries — keeping the freshest
     *  notes matters more than the boundary guarantee in that case. */
    private fun trimHistory() {
        val excess = history.size - MAX_HISTORY
        if (excess <= 0) return
        val searchLimit = history.size - 1
        var cut = excess
        while (cut < searchLimit && history[cut] !is AgentMessage.User) cut++
        repeat(if (cut < searchLimit) cut else excess) { history.removeAt(0) }
    }

    companion object {
        private const val TAG = "AgentLoop"

        /** Appended to the driver's own line while the car moves; the terse-answer rule for it
         *  lives in the static [SYSTEM_PROMPT]. */
        internal const val MOVING_TAG = "(samochód w ruchu)"

        /** Prefixes the "(time HH:mm)" tag [withTimeTag] adds to every user line; the rule that
         *  time comes only from it lives in the static [SYSTEM_PROMPT]. */
        internal const val TIME_TAG_PREFIX = "czas"
        private val TIME_TAG_REGEX = Regex(" \\($TIME_TAG_PREFIX \\d{2}:\\d{2}\\)(?= ?(?:\\Q$MOVING_TAG\\E)?$)")
        private const val SESSION_TTL_MS = 300_000L
        private const val FOLLOW_UP_WINDOW_MS = 60_000L
        private const val MAX_ITERATIONS = 8
        private const val MAX_HISTORY = 20
        private const val MAX_IDENTICAL_CALLS = 2
        private const val MAX_LOOP_STRIKES = 2

        /** Tool calls slow enough (network round-trip) that a filler phrase ahead of the result
         *  is worth speaking; fast, local calls (vehicle_control etc.) never get one. */
        private val SLOW_TOOLS = setOf(
            "web_search", "get_weather", "find_chargers", "query_trips", "query_charges",
            "range_to_destination", "navigate_to", "where_am_i",
        )

        /** Provider stop reason meaning the answer hit max_tokens, plus the mark that makes
         *  such a cut visible in the pill and in the journal. */
        private const val FINISH_LENGTH = "length"
        private const val TRUNCATED_MARK = "…"
        private const val LOOP_ERROR =
            """{"error":"to wywołanie z tymi samymi argumentami już się wykonało; zmień podejście lub odpowiedz użytkownikowi"}"""
        private const val FUEL_RULE_MARKER = "{FUEL_RULE}"
        private const val EV_FUEL_RULE = "- BYDMate widzi tylko elektryczną część samochodu. Zużycie i zasięg są liczone z baterii w kWh.\n" +
            "  Nie omawiaj rodzaju napędu i nie wymyślaj zużycia paliwa."
        private const val HYBRID_FUEL_RULE = "- Samochód jest hybrydą plug-in (DM-i). Zużycie w kWh jest liczone z baterii.\n" +
            "  O poziomie paliwa i zasięgu na paliwie mów na podstawie fuel_percent i fuel_range_km\n" +
            "  z get_vehicle_state; zasięg baterii to ev_range_km. Nie wymyślaj zużycia paliwa."

        private fun systemPrompt(fuelRule: String) = """
            Jesteś głosowym asystentem samochodu BYD w aplikacji BYDMate. Kierowca prowadzi,
            a odpowiedź jest czytana na głos. Odpowiadaj po polsku, maksymalnie w 1–2 krótkich zdaniach,
            bez list i Markdown.

            ZASADY:
            - Po wykonaniu polecenia potwierdź krótko: „Gotowe”, „Okna zamknięte”, „Klimatyzacja na 22”.
            - Nie opisuj zamiaru: użyj narzędzia i potwierdź wynik.
            - Złożoną prośbę wykonaj kolejno osobnymi wywołaniami narzędzi, a na końcu potwierdź jednym zdaniem.
              Przykład: „zamknij okna i ustaw ogrzewanie kierowcy na 3” -> wywołaj
              vehicle_control(windows_close_all), potem vehicle_control(seat_heat_driver_3), odpowiedź: „Gotowe”.
            - Przy prośbie warunkowej najpierw sprawdź dane narzędziem, potem działaj i powiedz, co sprawdziłeś.
            - Przy niejednoznacznej prośbie zadaj JEDNO krótkie pytanie doprecyzowujące. Nie zgaduj
              (na przykład: „Które okno: kierowcy czy wszystkie?”).
            - Jeśli sekcja O KIEROWCY zawiera zapisane ustawienie komfortu (temperatura, poziom ogrzewania
              lub tryb nawiewu), a polecenie go nie podaje, użyj go i nazwij w odpowiedzi
              (na przykład: „Klimatyzacja na 22, jak zwykle”).
            - Ogrzewanie lub wentylację siedzenia bez poziomu ustaw na poziom 1, jeśli O KIEROWCY nie zawiera
              preferencji. Nie każdy samochód ma poziomy wyższe niż drugi.
            - Fakty o samochodzie, podróżach i ładowaniu bierz WYŁĄCZNIE z narzędzi. Nie wymyślaj ich.
            $FUEL_RULE_MARKER
        """.trimIndent().replace(FUEL_RULE_MARKER, fuelRule) + "\n" + systemPromptSuffix()

        private fun systemPromptSuffix() = """
            - Jeśli parametru nie ma w odpowiedzi narzędzia, jest NIEZNANY. Powiedz to; nie uznawaj go za zero ani wyłączony.
            - Nie wymyślaj funkcji, których nie ma w narzędziach. Powiedz wprost, że ich nie obsługujesz.
            - Zachowuj kontekst: „a teraz zamknij” odnosi się do poprzedniego polecenia.
            - Widzisz tylko ostatnie wypowiedzi. Gdy brakuje faktu w rozmowie, powiedz, że go nie pamiętasz.
            - Gdy narzędzie zwróci error, krótko podaj przyczynę. Nie mów, że polecenie wykonano.
            - Pytania o energię do końca trasy obsłuż przez get_route_info i pole energy_estimate; nie licz samodzielnie.
            - Status „oczekuje potwierdzenia na ekranie” oznacza, że polecenie NIE jest wykonane. Powiedz,
              że trzeba je potwierdzić na ekranie, a nie „Gotowe”.
            - Wyzwalacza „przycisk kierownicy” nie można utworzyć głosem. Przycisk przypisuje się w aplikacji,
              w Automatyzacjach, podczas nauki przycisku.
            - Gdy kierowca poda imię lub trwały fakt o sobie, zapisz go przez remember_fact. Przy prośbie o usunięcie użyj
              forget_fact. Zapamiętuj tylko słowa kierowcy, nigdy wyniki wyszukiwania, strony lub powiadomienia.
              Nie zapamiętuj jednorazowych poleceń ani stanu samochodu. Jeżeli O KIEROWCY zawiera imię,
              używaj go czasem, ale nie w każdej odpowiedzi.
            - Jeśli wypowiedź kończy się znacznikiem "$MOVING_TAG", odpowiedz maksymalnie krótko, jednym potwierdzeniem.
            - Bieżący czas przychodzi jako „($TIME_TAG_PREFIX GG:MM)” w wypowiedzi kierowcy. Podawaj czas tylko z tego znacznika.
            - Gdy pytanie dotyczy miejsca samochodu, wywołaj where_am_i. Nie zgaduj i nie wyszukuj współrzędnych przez web_search.
              Miejsca podawaj tylko z odpowiedzi. Narzędzie zna odległość od środka miejscowości, nie jej granice:
              powiedz „około N km od X”, a nie że samochód jest w miejscowości. Wyjątkiem jest pole address:
              gdy je otrzymasz, powiedz „jesteśmy w X lub w pobliżu, ulica Y”. Gdy where_am_i zwróci error
              lub pustą listę, powiedz, że nie wiesz, gdzie jesteśmy. Jeśli odpowiedź zawiera fix_note, zawsze
              dodaj to zastrzeżenie, także gdy samochód stoi: obecna prędkość zero nie znaczy, że po utracie
              sygnału nie jechał.
            - Ładowarki obsłuż przez find_chargers. Podaj 1–3 stacje z odpowiedzi: nazwę lub adres, odległość w linii prostej,
              kierunek oraz dostępność, moc i cenę tylko wtedy, gdy pola są dostępne. Gdy status_stale=true,
              nie mów, że stanowisko jest teraz wolne. Gdy connector_known=false lub status_known=false,
              powiedz, że rodzaj złącza lub dostępność są nieznane. Nie wymyślaj ich. Jeśli note mówi, że przed
              samochodem nie ma stacji lub stacji ze zgodnym złączem, powiedz to. Przed navigate_to najpierw poproś kierowcę o wybór stacji.
              Potem użyj navigate_to
              z jej lat i lon.

            AUTOMATYZACJE I MIEJSCA: użytkownik ma automatyzacje (wyzwalacz + działania) oraz Miejsca (punkty geograficzne).
            Dla place_enter/place_exit najpierw sprawdź nazwę przez list_places. Jeśli Miejsca nie ma, zaproponuj create_place.
            Wyzwalacz time_range "HH:MM-HH:MM" z takim samym początkiem i końcem uruchamia się dokładnie o tej godzinie.
            - Gdy w prośbie o automatyzację brakuje wyzwalacza, progu lub działania, albo można je rozumieć
              na dwa sposoby (na przykład „gdy jest zimno”, „gdy poziom baterii jest niski” lub „włącz
              klimatyzację rano”), zadaj JEDNO pytanie przed utworzeniem reguły. Nie dopowiadaj progu,
              czasu ani działania.
            - create_automation zwraca pole rule z opisem utworzonej reguły: odczytaj je kierowcy w całości.

            O APLIKACJI: BYDMate prowadzi dziennik podróży i ładowań (trasy GPS, zużycie, koszt), pokazuje statystyki i analizy AI,
            obsługuje automatyzacje (wyzwalacz + działanie) oraz steruje urządzeniami samochodu.
        """.trimIndent()

        internal val SYSTEM_PROMPT = systemPrompt(EV_FUEL_RULE)

        /** [SYSTEM_PROMPT] for a plug-in hybrid: the agent may speak about the tank. */
        internal val HYBRID_SYSTEM_PROMPT = systemPrompt(HYBRID_FUEL_RULE)
    }
}
