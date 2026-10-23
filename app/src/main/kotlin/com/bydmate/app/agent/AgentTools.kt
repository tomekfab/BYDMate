package com.bydmate.app.agent

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.cluster.ClusterMode
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.camera.BlindSpotPreferences
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.ActionValidationError
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.ConfirmOverlayManager
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.PlaceGeometry
import com.bydmate.app.data.automation.PowerStateRuleMigration
import com.bydmate.app.data.automation.RouteNavigatorUris
import com.bydmate.app.data.automation.RuleDraftValidator
import com.bydmate.app.data.automation.RuleInserts
import com.bydmate.app.data.automation.TriggerValidationError
import com.bydmate.app.data.automation.ScheduleSpec
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.data.automation.hhmmToMinute
import com.bydmate.app.data.charging.ChargeConnector
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.remote.InsightStatsAggregator
import com.bydmate.app.data.nativestack.MotorSplit
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.nativestack.motorSplitPercent
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.CommandTranslator
import com.bydmate.app.data.vehicle.DriveMode
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.WriteAllowlist
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.domain.calculator.RangeEstimate
import com.bydmate.app.domain.cost.CostCalculator
import com.bydmate.app.domain.tracker.TrackPoint
import com.bydmate.app.domain.tracker.TravelCourse
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.data.camera.CameraStateMonitor
import com.bydmate.app.media.NaviRouteHolder
import com.bydmate.app.media.NaviScreenReader
import com.bydmate.app.navdata.NavPackages
import com.bydmate.app.navdata.NavAppId
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.automation.OPERATORS
import com.bydmate.app.ui.automation.TRIGGER_PARAMS
import com.bydmate.app.ui.automation.TriggerParamOption
import com.bydmate.app.ui.automation.localizedEnumLabel
import com.bydmate.app.voice.VoiceGate
import com.bydmate.app.voice.VoiceUserPhrases
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.bydmate.app.split.DisabledSplitPreferences
import com.bydmate.app.split.Pane
import com.bydmate.app.split.SplitPair
import com.bydmate.app.split.SplitPreferences
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitSessionState
import com.bydmate.app.split.SplitSide
import com.bydmate.app.split.SplitStartResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Tool registry + executor for the voice agent. Tools are the ONLY surface the
 * LLM sees: reads go through existing repositories, writes go exclusively through
 * ActionDispatcher (kind="param"/"media_volume") so every safety gate applies.
 * Executor results are compact JSON strings fed back as tool-role messages.
 */
@Singleton
class AgentTools @Inject constructor(
    private val gate: VoiceGate,
    private val batteryStateRepository: BatteryStateRepository,
    private val rangeCalculator: RangeCalculator,
    private val tripDao: TripDao,
    private val chargeDao: ChargeDao,
    private val actionDispatcher: ActionDispatcher,
    private val ruleDao: RuleDao,
    private val automationEngine: AutomationEngine,
    private val placeRepository: PlaceRepository,
    private val weatherClient: WeatherClient,
    private val exaSearchClient: ExaSearchClient,
    private val openRouterClient: OpenRouterClient,
    private val settingsRepository: SettingsRepository,
    private val contactLookup: ContactLookup,
    @ApplicationContext private val context: Context,
    private val clusterVoiceControl: ClusterVoiceControl,
    private val chargerSearchClient: ChargerSearchClient,
    private val insightsManager: InsightsManager,
    private val zaiSearchClient: ZaiSearchClient,
    private val llmConnections: LlmConnectionResolver,
) {
    // Split-screen dependencies are not in the @Inject constructor to avoid updating
    // every existing test file. Hilt injects them via method injection after construction;
    // tests that cover split_screen call injectSplit() directly.
    private var splitPrefs: SplitPreferences = DisabledSplitPreferences
    private var splitMgr: SplitSessionManager? = null

    /** Called by Hilt after construction; call manually in unit tests that test split_screen. */
    @Inject
    internal fun injectSplit(prefs: SplitPreferences, mgr: SplitSessionManager) {
        splitPrefs = prefs
        splitMgr = mgr
    }

    // Same method-injection reason as splitPrefs above: keeps every existing test constructor
    // call intact. Null until Hilt injects it; add_charge then leaves the session unpriced
    // rather than inventing a rate.
    private var costCalculator: CostCalculator? = null

    /** Called by Hilt after construction; call manually in unit tests that add charges. */
    @Inject
    internal fun injectCostCalculator(calculator: CostCalculator) {
        costCalculator = calculator
    }

    // Same method-injection reason as splitPrefs above: keeps every existing test constructor
    // call intact. The default is an in-memory instance, so remember_fact never crashes.
    private var driverMemory: DriverMemory = DriverMemory(prefs = null)

    /** Called by Hilt after construction; call manually in unit tests that test the memory tools. */
    @Inject
    internal fun injectDriverMemory(memory: DriverMemory) {
        driverMemory = memory
    }

    // Same method-injection reason as splitPrefs above. The in-memory default holds no phrases,
    // so create_automation then checks only the other rules.
    private var userPhrases: VoiceUserPhrases = VoiceUserPhrases()

    /** Called by Hilt after construction; call manually in unit tests of voice-phrase collisions. */
    @Inject
    internal fun injectUserPhrases(phrases: VoiceUserPhrases) {
        userPhrases = phrases
    }

    // Same method-injection reason as splitPrefs above. Null until Hilt injects it; get_vehicle_state
    // then falls back to the old dev=1006 drive_mode mapping (see driveModeName below).
    private var helperClient: HelperClient? = null

    /** Called by Hilt after construction; call manually in unit tests of get_vehicle_state's
     *  drive_mode field. */
    @Inject
    internal fun injectHelperClient(client: HelperClient) {
        helperClient = client
    }

    // Same method-injection reason as splitPrefs above. Null until Hilt injects it; where_am_i
    // then answers with an error instead of guessing.
    private var settlementSearchClient: SettlementSearchClient? = null

    /** Called by Hilt after construction; call manually in unit tests of where_am_i. */
    @Inject
    internal fun injectSettlementSearch(client: SettlementSearchClient) {
        settlementSearchClient = client
    }

    // Same method-injection reason as splitPrefs above. Null until Hilt injects it; the car then
    // counts as not a hybrid and get_vehicle_state answers as on an EV.
    private var vehicleKindMonitor: VehicleKindMonitor? = null

    /** Called by Hilt after construction; call manually in unit tests of the hybrid fields. */
    @Inject
    internal fun injectVehicleKind(monitor: VehicleKindMonitor) {
        vehicleKindMonitor = monitor
    }

    /** Sorted names of the enabled automations, kept in step with the rules table by
     *  [startRuleWatcher]; null until the first emission. */
    @Volatile private var cachedRuleNames: List<String>? = null
    private val ruleWatcherStarted = AtomicBoolean(false)

    /** Test seam — scope the rules-table watcher collects on. */
    internal var ruleWatchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Test seam — deterministic time for period queries. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Test seam — overlay confirm gate for dangerous agent actions (P7). */
    internal var confirmGate: (Context, String, String, () -> Unit, () -> Unit) -> Boolean =
        { ctx, ruleName, summary, onConfirm, onCancel ->
            ConfirmOverlayManager.show(ctx, ruleName, summary, onConfirm, onCancel)
        }

    /** Test seam — scope that runs a confirmed dangerous dispatch off the UI thread. */
    internal var confirmScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Test seam — live GPS position, default reads TrackingService's last known fix. */
    internal var locationProvider: () -> Pair<Double, Double>? =
        { TrackingService.lastLocation.value?.let { it.latitude to it.longitude } }

    /** A GPS fix for where_am_i: [ageMs] since it was taken, [live] = delivered by the GPS
     *  listener in this service run rather than the start-time last-known seed. */
    internal data class GpsFix(val lat: Double, val lon: Double, val ageMs: Long, val live: Boolean)

    /** Test seam - monotonic clock for [gpsFixAgeMs], default SystemClock.elapsedRealtimeNanos().
     *  Not System.currentTimeMillis()/Location.time: a wall-clock change (manual or NTP) must not
     *  turn a stale fix fresh, or a fresh one stale. */
    internal var elapsedRealtimeNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() }

    /** GPS fix age from the monotonic clock in [elapsedRealtimeNanos], immune to the wall clock
     *  ([nowMs]) changing underneath it. A provider that leaves the monotonic stamp unset (0)
     *  would make every fix look as old as the uptime, so such a fix falls back to the wall clock. */
    internal fun gpsFixAgeMs(location: Location): Long {
        val stamp = location.elapsedRealtimeNanos
        val ageMs = if (stamp > 0L) (elapsedRealtimeNanos() - stamp) / 1_000_000L else nowMs() - location.time
        return ageMs.coerceAtLeast(0L)
    }

    /** Test seam - [locationProvider] plus the age and origin of the fix, for where_am_i. Reads
     *  TrackingService's location+live snapshot once, so the two never come from different fixes. */
    internal var gpsFixProvider: () -> GpsFix? = {
        TrackingService.lastLocationFix?.let { fix ->
            GpsFix(fix.location.latitude, fix.location.longitude, gpsFixAgeMs(fix.location), fix.isLive)
        }
    }

    /** Test seam - launchable apps as label to package pairs. */
    internal var launcherAppsProvider: () -> List<Pair<String, String>> = { queryLauncherApps() }

    /** Test seam - a11y read of the Navigator window. findNavigatorRoot() also sees the
     *  Navigator when it is minimized or projected to the instrument cluster (display 2),
     *  where rootInActiveWindow is blind. Caller-side recycle happens here. */
    internal var naviScreenProvider: () -> NaviScreenReader.ScreenInfo? = {
        val root = SteeringWheelKeyService.instance?.findNavigatorRoot()
        try {
            NaviScreenReader.read(root)
        } finally {
            @Suppress("DEPRECATION")
            root?.let { r -> runCatching { r.recycle() } }
        }
    }

    /** Test seam - which packages came to the front since [sinceMs]: the UsageStats
     *  ACTIVITY_RESUMED one and the live a11y window one. Android 12 blocks background activity
     *  starts SILENTLY, so a non-throwing startActivity alone is not proof. */
    internal var foregroundPackagesSince: (Long) -> List<String> = { sinceMs ->
        val viaEvents = runCatching {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
            CameraStateMonitor.latestResumed(usm, sinceMs, System.currentTimeMillis() + 1)?.first
        }.getOrNull()
        val viaWindow = runCatching {
            SteeringWheelKeyService.instance?.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        listOfNotNull(viaEvents, viaWindow)
    }

    /** Test seam - did the Navigator reach the foreground since [sinceMs]? */
    internal var naviForegroundCheck: (Long) -> Boolean = { sinceMs ->
        NaviRouteHolder.NAVI_PACKAGE in foregroundPackagesSince(sinceMs)
    }

    /** The same question for app="maps" (#200): Maps ships under several store package names,
     *  and waiting for the Navigator there would call a working route a failure. */
    internal var mapsForegroundCheck: (Long) -> Boolean = { sinceMs ->
        foregroundPackagesSince(sinceMs).any { it in NavPackages.YANDEX_MAPS }
    }

    /** The same question for a route pinned to 2GIS or Waze (#305): that package, not the Navigator. */
    internal var pinnedForegroundCheck: (Long, String) -> Boolean = { since, pkg ->
        pkg in foregroundPackagesSince(since)
    }

    /** Test seam - poll interval for the navigate foreground verification. */
    internal var naviVerifyIntervalMs = 500L

    /** Test seam - poll attempts before declaring the Navigator missing (10 x 500ms = 5s). */
    internal var naviVerifyAttempts = 10

    // All agent navigation goes through here: startActivity "success" only means "no exception
    // thrown", Android 12 background-start blocks are silent. Verify the Navigator actually
    // surfaced before letting the LLM claim the route was built.
    private suspend fun dispatchNavigate(displayName: String, payload: JSONObject): DispatchResult {
        val since = System.currentTimeMillis() - 1_000L
        // The route went to whichever app the payload named or the settings default resolved
        // to (#200), so that is the app whose arrival proves it: waiting for the Navigator on
        // a Maps route (explicit app="maps" or Maps chosen in settings) fails a working route.
        // The same goes for a route pinned to 2GIS or Waze (#305).
        val maps = actionDispatcher.willOpenMaps(payload)
        val pinned = if (maps) null else actionDispatcher.willOpenPinned(payload)
        val surfaced: (Long) -> Boolean = when {
            maps -> mapsForegroundCheck
            pinned != null -> { since -> pinnedForegroundCheck(since, pinned) }
            else -> naviForegroundCheck
        }
        val appName = when {
            maps -> "Yandex Maps"
            pinned == RouteNavigatorUris.DGIS_PACKAGE -> "2GIS"
            pinned == RouteNavigatorUris.WAZE_PACKAGE -> "Waze"
            else -> "Nawigator"
        }
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = displayName, kind = "navigate",
                payload = payload.toString()), data = null)
        if (!result.success) return result
        repeat(naviVerifyAttempts) {
            if (runCatching { surfaced(since) }.getOrDefault(false)) return result
            delay(naviVerifyIntervalMs)
        }
        return DispatchResult(false,
            "Intent został wysłany, ale $appName nie pojawił się na pierwszym planie: trasa prawdopodobnie " +
                "nie została wyznaczona; zaproponuj użytkownikowi ręczne otwarcie aplikacji")
    }

    /**
     * Names of the enabled automations for the run_automation enum — the only part of the
     * tool schemas that moves with user data. Recomputing it from the DB on every turn both
     * costs a query on the voice path and can reshuffle the tail of the static prefix, which
     * throws away the provider's prompt cache. The rules table is mirrored into a sorted
     * cache instead: Room re-emits [RuleDao.getAll] after every insert/update/delete, so a
     * rule edit invalidates the cache exactly once and nothing else does.
     *
     * Until the first emission (and wherever the table cannot be watched) this falls back to
     * a direct read, so the enum is never silently empty.
     */
    private suspend fun enabledRuleNames(): List<String> {
        startRuleWatcher()
        cachedRuleNames?.let { return it }
        return runCatchingCancellable { ruleDao.getEnabled() }.getOrDefault(emptyList())
            .map { it.name }.sorted()
    }

    private fun startRuleWatcher() {
        if (!ruleWatcherStarted.compareAndSet(false, true)) return
        ruleWatchScope.launch {
            runCatchingCancellable {
                ruleDao.getAll().collect { rules ->
                    cachedRuleNames = rules.filter { it.enabled }.map { it.name }.sorted()
                }
            }
        }
    }

    private fun queryLauncherApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }
    }

    suspend fun schemas(includeAutomationTools: Boolean = true): JSONArray = JSONArray().apply {
        put(tool(
            "get_vehicle_state",
            "Bieżący stan samochodu: naładowanie, zasięg, prędkość, temperatury, klimatyzacja, okna, drzwi, opony, światła, ogrzewanie siedzeń, pozycja GPS i nazwa zapisanego Miejsca, jeśli samochód jest w nim. " +
                "Pole age_s oznacza wiek danych w sekundach; jeśli przekracza 60, ostrzeż, że dane mogą być nieaktualne.",
            JSONObject(), emptyList(),
        ))
        put(tool(
            "query_trips",
            "Statystyki podróży za okres: liczba, kilometraż, zużycie energii, średnie zużycie i koszt.",
            JSONObject().put("period", periodParam())
                .put("from", JSONObject().put("type", "string")
                    .put("description", "Początek zakresu, data RRRR-MM-DD (na przykład 2026-06-01)"))
                .put("to", JSONObject().put("type", "string")
                    .put("description", "Koniec zakresu włącznie, RRRR-MM-DD; bez to = jeden dzień from")),
            emptyList(),
        ))
        put(tool(
            "query_charges",
            "Statystyki ładowania za okres: sesje, kWh, koszt, podział AC (wolne) / DC (szybkie).",
            JSONObject().put("period", periodParam())
                .put("from", JSONObject().put("type", "string")
                    .put("description", "Początek zakresu, data RRRR-MM-DD (na przykład 2026-06-01)"))
                .put("to", JSONObject().put("type", "string")
                    .put("description", "Koniec zakresu włącznie, RRRR-MM-DD; bez to = jeden dzień from")),
            emptyList(),
        ))
        put(tool(
            "add_charge",
            "Dodaj ręcznie sesję ładowania do historii (jak ręczne dodanie na ekranie Ładowania). " +
                "Podaj soc_start+soc_end (kWh zostanie obliczone z pojemności baterii) albo bezpośrednio kwh. " +
                "Taryfa jest domyślnie pobierana z ustawień dla typu. Odczytaj użytkownikowi wynik z odpowiedzi.",
            JSONObject()
                .put("type", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("AC", "DC")))
                    .put("description", "AC wolna (dom), DC szybka stacja. Według domyślnie AC"))
                .put("soc_start", JSONObject().put("type", "integer")
                    .put("description", "Procent naładowania przed ładowaniem, 0..100"))
                .put("soc_end", JSONObject().put("type", "integer")
                    .put("description", "Procent naładowania po ładowaniu, 0..100, większy niż soc_start"))
                .put("kwh", JSONObject().put("type", "number")
                    .put("description", "Liczba pobranych kWh, jeśli SOC jest nieznany"))
                .put("tariff", JSONObject().put("type", "number")
                    .put("description", "Cena za kWh, jeśli podał ją użytkownik; w przeciwnym razie z ustawień"))
                .put("date", JSONObject().put("type", "string")
                    .put("description", "Data ładowania RRRR-MM-DD, jeśli nie dzisiaj")),
            emptyList(),
        ))
        put(tool(
            "get_stats_summary",
            "Podsumowanie trendów: zużycie, podróże, krótkie podróże, średnia odległość i zużycie na postoju. " +
                "Porównaj bieżący okres z poprzednim. Powiedz krótko, co najważniejsze.",
            JSONObject().put("period", JSONObject().put("type", "string")
                .put("enum", JSONArray(listOf("week", "month")))
                .put("description", "week = 7 dni (po domyślnie), month = 30 dni")),
            emptyList(),
        ))
        put(tool(
            "vehicle_control",
            "Wykonaj polecenie sterowania samochodem. Okna mają dokładnie trzy położenia: całkowicie otwarte, " +
                "do połowy i wentylacja; samochód nie obsługuje dowolnego procentu — jeśli kierowca " +
                "podał procent, wybierz najbliższe z trzech i powiedz w odpowiedzi, które. " +
                "command — identyfikator z listy:\n" +
                AgentCommandCatalog.idsDoc(),
            JSONObject()
                .put("command", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(AgentCommandCatalog.ALL.map { it.id }))
                    .put("description", "Identyfikator polecenia z listy"))
                .put("value", JSONObject()
                    .put("type", "integer")
                    .put("description", "Wartość dla poleceń z value, na przykład temperatura")),
            listOf("command"),
        ))
        put(tool(
            "media_volume",
            "Głośność multimediów: op = liczba (poziom), \"+N\"/\"-N\" (krok), \"mute\", \"unmute\".",
            JSONObject().put("op", JSONObject().put("type", "string")),
            listOf("op"),
        ))
        put(tool(
            "get_weather",
            "Pogoda teraz i prognoza na 3 dni. city — tylko jeśli użytkownik wyraźnie pyta o inne miejsce; " +
                "w przeciwnym razie użyj bieżącej pozycji GPS samochodu.",
            JSONObject().put("city", JSONObject()
                .put("type", "string")
                .put("description", "Miasto, jeśli pytanie nie dotyczy bieżącej pozycji samochodu")),
            emptyList(),
        ))
        put(tool(
            "call_contact",
            "Zadzwoń do kontaktu z książki telefonicznej po nazwie. Jeśli jest kilka dopasowań, " +
                "zadaj dokładnie jedno pytanie doprecyzowujące, który kontakt jest potrzebny.",
            JSONObject().put("name", JSONObject()
                .put("type", "string")
                .put("description", "Nazwa kontaktu, jak zapisano w książce telefonicznej")),
            listOf("name"),
        ))
        put(tool(
            "where_am_i",
            "Gdzie jest teraz samochód: najbliższe miejscowości wokół aktualnej pozycji GPS (dane " +
                "OpenStreetMap), z odległością do centrum w km i kierunkiem od samochodu, najbliższa " +
                "jako pierwsza, oraz najbliższe miasto. Znane są tylko centra, nie granice: odpowiedź mówi, " +
                "jak blisko samochód jest danej miejscowości, a nie w jakiej miejscowości się znajduje. Wywołuj przy pytaniach \"gdzie jestem\", " +
                "\"co jest w pobliżu\", \"jaka to miejscowość\"; nie szukaj współrzędnych przez web_search.",
            JSONObject(), emptyList(),
        ))
        // Always declared: with no Exa key the execute path below falls back to the primary
        // connection's native search (openrouter:web_search server tool or z.ai web_search).
        put(tool(
            "web_search",
            "Wyszukiwanie w internecie dla aktualnych pytań, których nie obsługują inne narzędzia.",
            JSONObject().put("query", JSONObject()
                .put("type", "string")
                .put("description", "Zapytanie do wyszukiwania")),
            listOf("query"),
        ))
        if (includeAutomationTools) {
            val ruleNames = enabledRuleNames()
            put(tool(
                "run_automation",
                if (ruleNames.isEmpty()) "Uruchom zapisaną automatyzację po nazwie. Nie ma obecnie włączonych automatyzacji."
                else "Uruchom zapisaną automatyzację użytkownika po nazwie.",
                JSONObject().put("name", JSONObject().apply {
                    put("type", "string")
                    if (ruleNames.isNotEmpty()) put("enum", JSONArray(ruleNames))
                }),
                listOf("name"),
            ))
        }
        put(tool(
            "list_automations",
            "Lista wszystkich zapisanych automatyzacji użytkownika, także wyłączonych, z ich wyzwalaczami i działaniami.",
            JSONObject(), emptyList(),
        ))
        if (includeAutomationTools) {
            put(tool(
                "set_automation_enabled",
                "Włącz lub wyłączyć zapisaną automatyzację po nazwie.",
                JSONObject()
                    .put("name", JSONObject().put("type", "string").put("description", "Nazwa automatyzacji"))
                    .put("enabled", JSONObject().put("type", "boolean").put("description", "true = włączyć, false = wyłączyć")),
                listOf("name", "enabled"),
            ))
        }
        put(tool(
            "list_places",
            "Lista zapisanych miejsc (dom, praca itd.): nazwa, współrzędne i promień w metrach. " +
                "Miejsca są używane przez wyzwalacze place_enter/place_exit i get_vehicle_state.",
            JSONObject(), emptyList(),
        ))
        put(tool(
            "create_place",
            "Zapisz nowe miejsce. Bez lat/lon używana jest bieżąca pozycja GPS samochodu " +
                "(na przykład „zapamiętaj to miejsce jako Dom”).",
            JSONObject()
                .put("name", JSONObject().put("type", "string")
                    .put("description", "Nazwa miejsca, unikalna, do 40 znaków"))
                .put("lat", JSONObject().put("type", "number")
                    .put("description", "Szerokość geograficzna; razem z lon, inaczej GPS"))
                .put("lon", JSONObject().put("type", "number")
                    .put("description", "Długość geograficzna; razem z lat, inaczej GPS"))
                .put("radius_m", JSONObject().put("type", "integer")
                    .put("description", "Promień w metrach, 20..500, domyślnie 100")),
            listOf("name"),
        ))
        // Memory tools live behind the automation gate: a session started BY an automation
        // talks to a rule, not to the driver, and must not rewrite what we know about them.
        if (includeAutomationTools) {
            put(tool(
                "remember_fact",
                "Zapamiętaj trwały fakt o kierowcy na przyszłość: imię, forma zwracania się, " +
                    "preferencje (temperatura, muzyka, trasy) lub rodzina. Jedno krótkie zdanie, " +
                    "na przykład „Kierowca ma na imię Andrzej”, „Lubi 22 stopnie w kabinie”. " +
                    "Nie zapisuj jednorazowych poleceń ani stanu samochodu.",
                JSONObject().put("fact", JSONObject().put("type", "string")
                    .put("description", "Fakt w jednym krótkim zdaniu")),
                listOf("fact"),
            ))
            put(tool(
                "forget_fact",
                "Zapomnij zapisany fakt o kierowcy. fact = fragment tekstu faktu; " +
                    "all = true, aby usunąć wszystkie fakty.",
                JSONObject()
                    .put("fact", JSONObject().put("type", "string")
                    .put("description", "Fragment tekstu faktu do usunięcia"))
                    .put("all", JSONObject().put("type", "boolean")
                    .put("description", "true = usuń wszystkie fakty o kierowcy")),
                emptyList(),
            ))
        }
        put(tool(
            "navigate_to",
            "Wyznacz trasę od bieżącej pozycji w aplikacji wybranej w ustawieniach " +
                "(Nawigator/2GIS/Mapy). app=\"maps\" wybierz tylko dla Yandex Maps, gdy użytkownik " +
                "wyraźnie o to prosi. Polecenia „jedź do domu”, „do domu” i „do pracy” obsługuje " +
                "to narzędzie: przekaż destination \"Dom\" lub \"Praca\". Trasa zostanie wyznaczona " +
                "do miejsca BYDMate lub adresu zapisanego w Nawigatorze. destination może być nazwą " +
                "zapisanego miejsca, miastem lub miejscowością (trasa powstaje od razu) albo dokładnym " +
                "adresem (otworzy się wyszukiwanie na mapie, a użytkownik wybierze punkt). Odpowiedź może " +
                "zawierać ocenę zasięgu: gdy enough=false lub jest charge_note, ostrzeż użytkownika i " +
                "zaproponuj find_chargers. Do wyszukiwania miejsca bez trasy " +
                "(poszukaj, znajdź) użyj search_on_map.",
            JSONObject().put("destination", JSONObject().put("type", "string")
                .put("description", "Dokąd jechać: nazwa miejsca, adres lub miejscowość"))
                .put("lat", JSONObject().put("type", "number")
                    .put("description", "Szerokość punktu, jeśli znane są dokładne współrzędne (na przykład z find_chargers)"))
                .put("lon", JSONObject().put("type", "number")
                    .put("description", "Długość geograficzna punkty"))
                .put("go", JSONObject().put("type", "boolean")
                    .put("description", "true, gdy kierowca mówi „jedź/prowadź/zawieź” — rozpocznij " +
                        "prowadzenie po trasie, aplikacja naciśnie przycisk Jedź. false (domyślnie) " +
                        "dla „wyznacz/pokaż trasę” — tylko " +
                        "wyznaczyć, kierowca naciśnie Jedź sam"))
                .put("app", JSONObject().put("type", "string")
                    .put("enum", JSONArray().put("navigator").put("maps"))
                    .put("description", "navigator = aplikacja z ustawień (Nawigator/2GIS/Mapy, po domyślnie), " +
                        "maps = Yandex Maps; maps przekazuj tylko, gdy użytkownik wyraźnie prosi o Yandex Maps")),
            emptyList(),
        ))
        put(tool(
            "search_on_map",
            "Otwórz wyszukiwanie miejsca w aplikacji wybranej w ustawieniach (app=\"maps\" tylko dla " +
                "Yandex Maps, gdy użytkownik wyraźnie o to prosi). Na mapie pojawią się wyniki, a użytkownik " +
                "sam wybierze punkt. Używaj dla poleceń takich jak „poszukaj kawiarni”, „znajdź stację paliw” " +
                "lub „gdzie jest najbliższa apteka”. Gdy użytkownik chce gdzieś jechać, także " +
                "do domu lub na pracę, użyj navigate_to.",
            JSONObject().put("query", JSONObject().put("type", "string")
                .put("description", "Czego szukać: nazwa miejsca lub kategoria"))
                .put("app", JSONObject().put("type", "string")
                    .put("enum", JSONArray().put("navigator").put("maps"))
                    .put("description", "navigator = aplikacja z ustawień (Nawigator/2GIS/Mapy, po domyślnie), " +
                        "maps = Yandex Maps; maps przekazuj tylko, gdy użytkownik wyraźnie prosi o Yandex Maps")),
            listOf("query"),
        ))
        put(tool(
            "show_point_on_map",
            "Pokaż punkt na mapie bez wyznaczania trasy (domyślnie w aplikacji z " +
                "ustawień, app=\"maps\" tylko dla Yandex Maps): polecenia takie jak " +
                "pokaż na mapie, gdzie znajduje się. destination: nazwa Miejsca, miasto lub adres; " +
                "albo lat/lon, jeśli współrzędne znane (na przykład z find_chargers). " +
                "Do trasy użyj navigate_to, a do wyszukiwania kategorii search_on_map.",
            JSONObject().put("destination", JSONObject().put("type", "string")
                .put("description", "Co pokaż: nazwa Miejsca, adres lub nazwa"))
                .put("lat", JSONObject().put("type", "number").put("description", "Szerokość geograficzna, jeśli znana"))
                .put("lon", JSONObject().put("type", "number").put("description", "Długość geograficzna"))
                .put("app", JSONObject().put("type", "string")
                    .put("enum", JSONArray().put("navigator").put("maps"))
                    .put("description", "navigator = aplikacja z ustawień (Nawigator/2GIS/Mapy, po domyślnie), " +
                        "maps = Yandex Maps; maps przekazuj tylko, gdy użytkownik wyraźnie prosi o Yandex Maps")),
            emptyList(),
        ))
        put(tool(
            "find_chargers",
            "Znajdź stacje ładowania. Na Białorusi pokazuje bieżącą zajętość złączy, moc i cenę " +
                "(mapa BETA), a gdzie indziej tylko stacje z OpenStreetMap. Bez city szuka od bieżącej " +
                "pozycji samochodu. where: „po drodze” lub „przed nami” = ahead (stacje w kierunku jazdy; " +
                "po trasie navigate_to z niewielkim objazdem do celu), „w pobliżu” = around. Bez where " +
                "podczas jazdy szuka ahead, na postoju around. Pola stacji: distance_km w linii prostej; " +
                "direction_from_car = kierunek świata; position ahead/behind/aside = przed, za lub z boku " +
                "w kierunku jazdy; detour_km = dodatkowy objazd; status free/busy/unavailable oraz " +
                "connectors_free z connectors_total = złącza zgodne z samochodem; connectors_unknown = brak " +
                "danych o zajętości części złączy; status_scope=station = zajętość całej stacji, nie złącza; " +
                "max_power_kw; price_per_kwh i currency; status_age_min = wiek zajętości w minutach; " +
                "status_stale=true = dane nieaktualne; beyond_range=true = poza zasięgiem. connector_known=false " +
                "= nieznane złącza, moc i cena; status_known=false = nieznana zajętość. Gdy kierowca wybierze " +
                "stację, wywołaj navigate_to z jej lat i lon.",
            JSONObject()
                .put("city", JSONObject().put("type", "string")
                    .put("description", "Miasto lub punkt wyszukiwania, jeśli nie wokół samochodu"))
                .put("radius_km", JSONObject().put("type", "integer")
                    .put("description", "Promień wyszukiwania w km, domyślnie 30 (ahead: 30-100 zależnie od prędkości), maksimum 100"))
                .put("connector", JSONObject().put("type", "string")
                    .put("enum", JSONArray(ChargeConnector.entries.map { it.label }))
                    .put("description", "Typ złącza, tylko gdy kierowca podał inny; domyślnie z ustawień"))
                .put("where", JSONObject().put("type", "string")
                    .put("enum", JSONArray(ChargerWhere.entries.map { it.code }))
                    .put("description", "ahead - po kierunku jazdy jazdy, around - wokół samochodu")),
            emptyList(),
        ))
        put(tool(
            "range_to_destination",
            "Sprawdź, czy bieżący ładunek wystarczy do celu: porównaj odległość z zasięgiem. " +
                "Odległość jest szacowana w linii prostej ze współczynnikiem drogowym, więc jest przybliżona. " +
                "Jeśli reserve_km to mniej niż 20% odległości, ostrzeż, że zasięg jest na granicy, i zaproponuj ładowanie.",
            JSONObject().put("destination", JSONObject().put("type", "string")
                .put("description", "Dokąd jechać: zapisane miejsce albo miasto lub miejscowość")),
            listOf("destination"),
        ))
        put(tool(
            "get_route_info",
            "Stan prowadzenia w Yandex Navigatorze: następny manewr (maneuver, maneuver_distance, " +
                "street), wiersze powiadomienia route_lines (zwykle pozostała droga i czas przyjazdu), " +
                "limit prędkości speed_limit i numer zjazdu exit_number (tylko gdy Nawigator jest na " +
                "ekranie), ładunek soc i zasięg range_km. Odpowiadaj na pytania o czas jazdy, skręt, " +
                "przyjazd i zasięg według tych pól. Jeśli pola nie ma w odpowiedzi, dane nie są teraz " +
                "dostępne — powiedz to wprost. Pole energy_estimate: ile kWh zużyje trasa i ile procent " +
                "baterii zostanie na końcu.",
            JSONObject(), emptyList(),
        ))
        put(tool(
            "go_home",
            "Zwiń wszystkie okna i pokaż ekran główny samochodu. Używaj dla poleceń takich jak " +
                "„wróć do ekranu głównego”, „pokaż pulpit” i „zwiń wszystko”.",
            JSONObject(), emptyList(),
        ))
        put(tool(
            "end_conversation",
            "Zamknij asystenta głosowego. Wywołuj tylko, gdy kierowca wprost prosi o zamknięcie, " +
                "wyłączenie lub zatrzymanie asystenta albo mówi, że rozmowa się skończyła. Nigdy nie " +
                "wywołuj tego tylko po wykonaniu polecenia. Po wywołaniu odpowiedz bardzo krótkim pożegnaniem.",
            JSONObject(), emptyList(),
        ))
        put(tool(
            "play_music",
            "Włącz muzykę w Yandex Music. Bez query gra osobista propozycja (Moja fala). " +
                "Z query wyszukuje i włącza utwór, album albo wykonawcę.",
            JSONObject().put("query", JSONObject().put("type", "string")
                .put("description", "Utwór, album lub wykonawca; nie przekazuj dla Mojej fali"))
                .put("mode", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("play", "search")))
                    .put("description", "play = od razu włączyć najlepsze dopasowanie (\"włącz/ustaw X\"), " +
                        "search = tylko otworzyć wyszukiwanie (\"znajdź X\"). Według domyślnie play.")),
            emptyList(),
        ))
        put(tool(
            "youtube",
            "YouTube: włącz wideo według zapytania albo otwórz wyszukiwanie. „Włącz na YouTube X” = " +
                "mode play (od razu uruchamia najlepsze dopasowanie), „znajdź na YouTube X” = mode search. " +
                "Do samego uruchomienia aplikacji bez zapytania użyj launch_app.",
            JSONObject()
                .put("query", JSONObject().put("type", "string")
                    .put("description", "Czego szukać: nazwa filmu, kanału lub temat"))
                .put("mode", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("play", "search")))
                    .put("description", "play = włącz, search = pokaż wyniki. Domyślnie play.")),
            listOf("query"),
        ))
        put(tool(
            "launch_app",
            "Uruchom zainstalowaną aplikację po nazwie. Rozumie polskie nazwy fabrycznych aplikacji " +
                "samochodu: nawigator, mapy, muzyka, kamera, wideorejestrator, przeglądarka, YouTube, " +
                "ustawienia samochodu, pliki, tryb jazdy, tryb ochrony, ABRP, centrum multimediów i telefon.",
            JSONObject().put("name", JSONObject().put("type", "string")
                .put("description", "Nazwa aplikacji taka jak na ekranie głównym")),
            listOf("name"),
        ))
        put(tool(
            "set_cluster_projection",
            "Pokaż lub ukryj wybraną aplikację (zwykle Nawigator) na zestawie wskaźników przed " +
                "kierowcą, jak przyciskiem gwiazdki na kierownicy. Włączenie pokazuje aplikację, a " +
                "wyłączenie usuwa ją z zestawu wskaźników i przywraca na ekran główny.",
            JSONObject().put("on", JSONObject().put("type", "boolean")
                .put("description", "true = pokaż mapę na zestawie wskaźników, false = ukryj")),
            listOf("on"),
        ))
        put(tool(
            "set_sentry",
            "Włącz lub wyłącz tryb ochrony (Sentry) — kamery obserwują otoczenie zaparkowanego samochodu.",
            JSONObject().put("on", JSONObject().put("type", "boolean")
                .put("description", "true = włączyć ochronny tryb, false = wyłączyć")),
            listOf("on"),
        ))
        put(tool(
            "set_hotspot",
            "Włącz lub wyłączyć punkt dostępu Wi-Fi (udostępnianie internetu z samochodu). " +
                "\"włącz udostępnianie internetu\", \"włącz punkt dostępu\" = on; " +
                "\"wyłącz udostępnianie\" = off.",
            JSONObject().put("on", JSONObject().put("type", "boolean")
                .put("description", "true = włączyć punkt dostępu, false = wyłączyć")),
            listOf("on"),
        ))
        put(tool(
            "set_screen",
            "Wyłącz lub włącz centralny ekran multimediów (bez zestawu wskaźników i HUD). Gaśnie tylko " +
                "podświetlenie: muzyka i aplikacje nadal działają, a dotknięcie ekranu włącza go ponownie.",
            JSONObject().put("on", JSONObject().put("type", "boolean")
                .put("description", "true = włączyć ekran, false = wyłączyć")),
            listOf("on"),
        ))
        put(tool(
            "split_screen",
            "Podzielony ekran: dwie aplikacje obok siebie (1/3 + 2/3). action=start uruchamia parę " +
                "(bez narrow_app/wide_app przywraca ostatnią parę); mirror przenosi wąski panel na drugą " +
                "stronę; swap zamienia aplikacje miejscami; change zmienia aplikację w panelu (side = " +
                "strona ekranu, app = aplikacja); exit kończy tryb podzielonego ekranu.",
            JSONObject()
                .put("action", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("start", "mirror", "swap", "change", "exit")))
                    .put("description", "Operacja na podzielonym ekranie"))
                .put("narrow_app", JSONObject().put("type", "string")
                    .put("description", "Dla action=start: aplikacja w wąskim panelu (1/3)"))
                .put("wide_app", JSONObject().put("type", "string")
                    .put("description", "Dla action=start: aplikacja w szerokim panelu (2/3)"))
                .put("side", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("left", "right")))
                    .put("description", "Dla action=start: strona wąskim panelu (po domyślnie right). " +
                        "Dla action=change: strona ekranu zmienianego panelu"))
                .put("app", JSONObject().put("type", "string")
                    .put("description", "Tylko dla action=change: nowe aplikacja")),
            listOf("action"),
        ))
        if (includeAutomationTools) {
            put(tool(
                "create_automation",
                "Utwórz nową automatyzację: „jeśli <wyzwalacz>, to <działania>”. Jeden wyzwalacz i jedno lub " +
                    "więcej działań. Wyzwalacze: dokładny czas i harmonogram (kind=time_range, value=\"HH:MM-HH:MM\", " +
                    "identyczny początek i koniec = uruchom dokładnie o tej porze, weekdays = dni tygodnia; przykład: " +
                    "\"każdy roboczy dzień w 8:00\" -> value=\"08:00-08:00\", weekdays=[1,2,3,4,5]), " +
                    "fraza głosowa (kind=voice, pole phrase), przycisk widżetu (kind=button_press, pole button), " +
                    "parametr samochodu (kind=param), wjazd/wyjazd z miejsca, pora dnia (kind=time_of_day).",
                JSONObject()
                    .put("name", JSONObject().put("type", "string")
                        .put("description", "Nazwa automatyzacji, musi być unikalna"))
                    .put("trigger", JSONObject()
                        .put("type", "object")
                        .put("description", "Warunek uruchomienia, dokładnie jeden wyzwalacz")
                        .put("properties", JSONObject()
                            .put("kind", JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray(listOf(
                                    "param", "place_enter", "place_exit", "time_of_day",
                                    "time_range", "service_start", "network_available",
                                    "voice", "button_press")))
                                .put("description", "Typ wyzwalacza"))
                            .put("param", JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray(TRIGGER_PARAMS.map { it.param }))
                                .put("description", "Tylko dla kind=param: parametr samochodu"))
                            .put("operator", JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray(OPERATORS))
                                .put("description", "Tylko dla kind=param: operator porównania"))
                            .put("value", JSONObject()
                                .put("description", "Tylko dla kind=param: wartość progu. Parametry liczbowe " +
                                    "(prędkość, SOC, temperatura, ciśnienie, procenty) podaj jako tekst liczby. " +
                                    "Parametry przełączników przyjmują kod LUB nazwę, lepiej użyć kodu: ${enumValueGuide()}. " +
                                    "ACStatus/FanLevel podaj liczbą (ACStatus 1=włączony, 0=wyłączony). " +
                                    "Dla kind=time_of_day: dawn/day/dusk/night; dla kind=time_range: \"HH:MM-HH:MM\" " +
                                    "(identyczny początek i koniec = uruchom dokładnie o tej porze)")
                                .put("anyOf", JSONArray(listOf(
                                    JSONObject().put("type", "string"),
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray(listOf("dawn", "day", "dusk", "night")))
                                        .put("description", "Dla kind=time_of_day: czas doby"),
                                ))))
                            .put("place_name", JSONObject()
                                .put("type", "string")
                                .put("description", "Tylko dla kind=place_enter/place_exit: nazwa zapisanego miejsca"))
                            .put("phrase", JSONObject().put("type", "string")
                                .put("description", "Tylko dla kind=voice: fraza uruchamiająca regułę"))
                            .put("button", JSONObject().put("type", "integer")
                                .put("description", "Tylko dla kind=button_press: numer przycisku widżetu, 1-4"))
                            .put("weekdays", JSONObject().put("type", "array")
                                .put("items", JSONObject().put("type", "integer"))
                                .put("description", "Tylko dla kind=time_range: dni tygodnia, 1=poniedziałek .. 7=niedziela. Brak = każdy dzień")))
                        .put("required", JSONArray(listOf("kind"))))
                    .put("actions", JSONObject()
                        .put("type", "array")
                        .put("description", "Lista działań, minimum jedno")
                        .put("items", JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject()
                                .put("kind", JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(listOf(
                                        "param", "delay", "media_volume", "notification",
                                        "call", "navigate", "url",
                                        "yandex_music", "sentry", "hotspot", "screen", "app_launch", "app_close", "media_key",
                                        "cluster_projection", "speak", "agent_query",
                                        "split_screen", "split_screen_close",
                                        "split_screen_toggle", "youtube", "go_home")))
                                    .put("description", "Typ działania. split_screen_close, " +
                                        "split_screen_toggle i go_home nie wymagają dodatkowych pól"))
                                // No second enum: the same ~90 ids already ship with
                                // vehicle_control, and repeating them here cost ~2 kB of the
                                // static prefix on every single turn.
                                .put("command_id", JSONObject()
                                    .put("type", "string")
                                    .put("description", "Tylko dla kind=param: identyfikator polecenia " +
                                        "ze schematu narzędzia vehicle_control"))
                                .put("value", JSONObject().put("type", "integer")
                                    .put("description", "Tylko dla kind=param: wartość, jeśli polecenie jej wymaga"))
                                .put("ms", JSONObject().put("type", "integer")
                                    .put("description", "Tylko dla kind=delay: pauza w ms, 0..60000"))
                                .put("op", JSONObject().put("type", "string")
                                    .put("description", "Tylko dla kind=media_volume: liczba / \"+N\" / \"-N\" / mute / unmute"))
                                .put("title", JSONObject().put("type", "string")
                                    .put("description", "Tylko dla kind=notification: tytuł"))
                                .put("text", JSONObject().put("type", "string")
                                    .put("description", "Dla kind=notification: tekst powiadomienia. Dla kind=speak: tekst, który samochód odczyta"))
                                .put("phone", JSONObject().put("type", "string")
                                    .put("description", "Tylko dla kind=call: numer telefonu"))
                                .put("lat", JSONObject().put("type", "number")
                                    .put("description", "Tylko dla kind=navigate: szerokość geograficzna"))
                                .put("lon", JSONObject().put("type", "number")
                                    .put("description", "Tylko dla kind=navigate: długość geograficzna"))
                                .put("go", JSONObject().put("type", "boolean")
                                    .put("description", "Tylko dla kind=navigate: true - od razu rozpocząć " +
                                        "prowadzenie po trasie, false - tylko wyznaczyć trasę"))
                                .put("url", JSONObject().put("type", "string")
                                    .put("description", "Tylko dla kind=url: łącze ze schematem, na przykład https://"))
                                .put("mode", JSONObject().put("type", "string")
                                    .put("description", "Dla kind=yandex_music: tryb, na przykład mybeat. " +
                                        "Dla kind=youtube: play (włączyć) lub search (otworzyć wyszukiwanie), po domyślnie play"))
                                .put("query", JSONObject().put("type", "string")
                                    .put("description", "Tylko dla kind=youtube: co szukać na YouTube"))
                                .put("on", JSONObject().put("type", "boolean")
                                    .put("description", "Dla kind=sentry: włączyć/wyłączyć ochronę. Dla kind=cluster_projection: true = wyświetlić projekcję na zestaw wskaźników, false = usuń. Dla kind=hotspot: włączyć/wyłączyć punkt dostępu Wi-Fi. Dla kind=screen: włączyć/wyłączyć centralny ekran"))
                                .put("app", JSONObject().put("type", "string")
                                    .put("description", "Dla kind=app_launch i kind=app_close: nazwa aplikacji taka jak na ekranie głównym. " +
                                        "app_close zamyka aplikację całkowicie"))
                                .put("key", JSONObject().put("type", "string")
                                    .put("enum", JSONArray(listOf("play", "pause")))
                                    .put("description", "Tylko dla kind=media_key: play - odtwarzaj, pause - pauza. " +
                                        "Odtwarzacz musi być już uruchomiony"))
                                .put("narrow_app", JSONObject().put("type", "string")
                                    .put("description", "Tylko dla kind=split_screen: aplikacja w wąskim panelu (1/3), nazwa jak na ekranie głównym"))
                                .put("wide_app", JSONObject().put("type", "string")
                                    .put("description", "Tylko dla kind=split_screen: aplikacja w szerokim panelu (2/3), nazwa jak na ekranie głównym"))
                                .put("side", JSONObject().put("type", "string")
                                    .put("enum", JSONArray(listOf("left", "right")))
                                    .put("description", "Tylko dla kind=split_screen: strona wąskim panelu, left lub right (po domyślnie right)"))
                                .put("prompt", JSONObject().put("type", "string")
                                    .put("description", "Tylko dla kind=agent_query: pytanie do asystenta, odpowiedź zostanie odczytana")))
                            .put("required", JSONArray(listOf("kind")))))
                    .put("cooldown_seconds", JSONObject()
                        .put("type", "integer")
                        .put("description", "Minimalny odstęp między uruchomieniami w sekundach, minimum 1, domyślnie 60"))
                    .put("play_sound", JSONObject()
                        .put("type", "boolean")
                        .put("description", "Odtwórz sygnał dźwiękowy przy uruchomieniu reguły, domyślnie false")),
                listOf("name", "trigger", "actions"),
            ))
        }
    }

    // runCatching swallows CancellationException (it is an Exception subtype), which would turn
    // a PTT-stop cancellation mid-suspend-call (e.g. ruleDao.getEnabled(), findByName()) into a
    // caught failure instead of letting it unwind to execute()'s top-level rethrow. Use this in
    // place of runCatching everywhere in this file so cancellation still propagates.
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: keeps runCatching's scope so device-only Errors
            // (e.g. NoSuchMethodError absent from JVM tests) still map to the site's
            // Russian error instead of escaping the tool call.
            Result.failure(e)
        }

    suspend fun execute(call: AgentToolCall, allowAutomationTools: Boolean = true): String {
        val args = runCatchingCancellable { JSONObject(call.arguments.ifBlank { "{}" }) }
            .getOrElse { return BAD_ARGS_ERROR }
        // Last-resort net: every branch already has its own runCatching guards for specific
        // Russian messages, but a future/overlooked throw must still never escape as a raw
        // exception. CancellationException must be rethrown, not swallowed here, or a PTT-stop
        // mid-tool-call would silently turn into an error JSON instead of cancelling the session.
        return try {
            if (!allowAutomationTools && call.name in AUTOMATION_TOOLS) {
                return """{"error":"narzędzie ${call.name} niedostępny w sesje, uruchomionej przez automatyzację"}"""
            }
            when (call.name) {
                "get_vehicle_state" -> vehicleState()
                "get_weather" -> getWeather(args)
                "where_am_i" -> whereAmI()
                "call_contact" -> callContact(args)
                "web_search" -> webSearch(args)
                "query_trips" -> queryTrips(args)
                "query_charges" -> queryCharges(args)
                "add_charge" -> addCharge(args)
                "get_stats_summary" -> statsSummary(args)
                "vehicle_control" -> vehicleControl(args)
                "media_volume" -> mediaVolume(args)
                "run_automation" -> runAutomation(args)
                "list_automations" -> listAutomations()
                "list_places" -> listPlaces()
                "create_place" -> createPlace(args)
                "remember_fact" -> rememberFact(args)
                "forget_fact" -> forgetFact(args)
                "navigate_to" -> navigateTo(args)
                "search_on_map" -> searchOnMap(args)
                "show_point_on_map" -> showPointOnMap(args)
                "find_chargers" -> findChargers(args)
                "range_to_destination" -> rangeToDestination(args)
                "get_route_info" -> routeInfo()
                "go_home" -> goHomeScreen()
                // The voice session closes itself on this outcome (VoiceController); nothing to do here.
                "end_conversation" -> """{"ok":true}"""
                "play_music" -> playMusic(args)
                "youtube" -> youtubeTool(args)
                "launch_app" -> launchAppTool(args)
                "set_cluster_projection" -> setClusterProjection(args)
                "set_sentry" -> setSentry(args)
                "set_hotspot" -> setHotspot(args)
                "set_screen" -> setScreen(args)
                "split_screen" -> splitScreen(args)
                "set_automation_enabled" -> setAutomationEnabled(args)
                "create_automation" -> createAutomation(args)
                else -> """{"error":"nieznane narzędzie ${call.name}"}"""
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            INTERNAL_ERROR
        }
    }

    // --- get_vehicle_state ---

    private suspend fun vehicleState(): String {
        val d = gate.vehicleSnapshot()
            ?: return """{"error":"brak danych z samochodu (brak połączenia lub samochód śpi)"}"""
        val o = JSONObject()
        fun putIf(key: String, v: Any?) { if (v != null) o.put(key, v) }
        // The snapshot is never cleared on transport loss, so the model needs its age to
        // tell a live reading from a stale one (see TrackingService.lastDataAtMs).
        putIf("age_s", gate.snapshotAgeMs()?.let { it / 1000L })
        putIf("soc_percent", d.soc)
        putIf("speed_kmh", d.speed)
        putIf("power_kw", d.power)
        putIf("gear", when (d.gear) { 1 -> "P"; 2 -> "R"; 3 -> "N"; 4 -> "D"; else -> null })
        putIf("mileage_km", d.mileage)
        putIf("inside_temp_c", d.insideTemp)
        putIf("outside_temp_c", d.exteriorTemp)
        putIf("ac_on", d.acStatus?.let { it == 1 })
        putIf("ac_temp_c", d.acTemp)
        putIf("fan_level", d.fanLevel)
        // Same 1..5 enum the blow-direction actions write (CommandTranslator ac_wind_mode):
        // the agent could set it but not read it back, which is worse than not having it.
        putIf("ac_wind_mode", when (d.acWindMode) {
            1 -> "na twarz"
            2 -> "na twarz i nogi"
            3 -> "na nogi"
            4 -> "na nogi i szybę"
            5 -> "na szybę"
            else -> null
        })
        putIf("recirculation_inner", d.acCirc?.let { it == 1 })
        putIf("defrost_front_on", d.acDefrostFront?.let { it == 1 })
        putIf("ac_auto_mode", d.acCtrlMode?.let { it == 0 })
        putIf("seat_heat_driver_level", d.seatHeatDriver)
        putIf("seat_vent_driver_level", d.seatVentDriver)
        putIf("seat_heat_passenger_level", d.seatHeatPassenger)
        putIf("seat_vent_passenger_level", d.seatVentPassenger)
        putIf("window_front_left_pct", d.windowFL)
        putIf("window_front_right_pct", d.windowFR)
        putIf("window_rear_left_pct", d.windowRL)
        putIf("window_rear_right_pct", d.windowRR)
        putIf("sunroof_pct", d.sunroof)
        putIf("trunk_open", d.trunk?.let { it == 1 })
        putIf("hood_open", d.hood?.let { it == 1 })
        putIf("locked", d.lockFL?.let { it == 2 })
        // Absent door sensors -> omit the key entirely: a hardcoded 0 would read as
        // "all doors closed" while the truth is "unknown" (e.g. reduced D+ payload).
        val doorStates = listOfNotNull(d.doorFL, d.doorFR, d.doorRL, d.doorRR)
        if (doorStates.isNotEmpty()) o.put("doors_open", doorStates.count { it == 1 })
        putIf("tire_press_fl_kpa", d.tirePressFL)
        putIf("tire_press_fr_kpa", d.tirePressFR)
        putIf("tire_press_rl_kpa", d.tirePressRL)
        putIf("tire_press_rr_kpa", d.tirePressRR)
        putIf("tire_temp_fl_c", d.tyreTempFL)
        putIf("tire_temp_fr_c", d.tyreTempFR)
        putIf("tire_temp_rl_c", d.tyreTempRL)
        putIf("tire_temp_rr_c", d.tyreTempRR)
        // Deterministic low-pressure flag so the model reliably warns by voice.
        // 210 kPa is ~16% below the Leopard 3 cold placard (~250 kPa).
        val tirePressures = listOfNotNull(d.tirePressFL, d.tirePressFR, d.tirePressRL, d.tirePressRR)
        if (tirePressures.isNotEmpty() && tirePressures.any { it < TIRE_WARN_MIN_KPA }) {
            o.put("tire_pressure_warning",
                "ciśnienie w jednym z kół jest poniżej normy, ostrzeż kierowcę")
        }
        // Power split between the motors, from the same helper the «Tekhnika» screen uses.
        // Null = single-motor car or no data; Idle = standing still, where the ratio is noise.
        when (val split = motorSplitPercent(d.motorCurrentFront, d.motorCurrentRear)) {
            is MotorSplit.Share -> {
                o.put("motor_share_front_pct", split.frontPercent)
                o.put("motor_share_rear_pct", split.rearPercent)
            }
            MotorSplit.Idle -> o.put("motor_share", "silniki obecnie nie napędzają samochodu")
            null -> Unit
        }
        // What the BMS allows right now; the peak on hard acceleration is higher.
        putIf("bms_max_charge_kw", d.bmsMaxChargeKw)
        putIf("bms_max_discharge_allow_kw", d.bmsMaxDischargeAllowKw)
        putIf("bms_max_discharge_kw", d.bmsMaxDischargeKw)
        putIf("voltage_12v", d.voltage12v)
        putIf("battery_temp_avg_c", d.avgBatTemp)
        putIf("battery_temp_max_c", d.maxBatTemp)
        putIf("battery_temp_min_c", d.minBatTemp)
        putIf("cell_voltage_min_v", d.minCellVoltage)
        putIf("cell_voltage_max_v", d.maxCellVoltage)
        // Cell delta in millivolts -- the unit battery diagnostics are discussed in.
        if (d.minCellVoltage != null && d.maxCellVoltage != null) {
            o.put("cell_voltage_delta_mv", ((d.maxCellVoltage - d.minCellVoltage) * 1000).roundToInt())
        }
        // Gun fid: 1=NONE, 2=AC, 3=DC, 4=AC_DC, 5=VTOL -- NONE is 1, not 0.
        putIf("charging_gun_connected", d.chargeGunState?.let { it >= 2 })
        // The old dev=1006 fid (d.driveMode) stays 3/NORMAL for sand/mud/mountain/smart (live L3
        // 2026-09-27); driveModeName reads the real target mode and falls back to it on failure.
        putIf("drive_mode", driveModeName(d.driveMode))
        putIf("work_mode", when (d.workMode) { 0 -> "STOP"; 1 -> "EV"; 2 -> "FORCED_EV"; 3 -> "HEV"; else -> null })
        putIf("light_low_beam_on", d.lightLow?.let { it == 1 })
        putIf("light_high_beam_on", d.lightHigh?.let { it == 1 })
        putIf("light_side_on", d.lightSide?.let { it == 1 })
        // DRL fid: 1=ON, 2=OFF, 0=invalid -- not a plain boolean.
        putIf("light_drl_on", when (d.drl) { 1 -> true; 2 -> false; else -> null })
        // Cabin sensors (sensors wave). Absent sensor -> omit the key: null must
        // read as "unknown", not as a fabricated false.
        putIf("seatbelt_driver_fastened", d.seatbeltFL?.let { it == 1 })
        putIf("seatbelt_front_passenger_fastened", d.seatbeltFR?.let { it == 1 })
        // Occupancy raw codes: 1=free, 2=occupied.
        putIf("seat_occupied_driver", d.occupancyFL?.let { it == 2 })
        putIf("seat_occupied_front_passenger", d.occupancyFR?.let { it == 2 })
        putIf("seat_occupied_rear_left", d.occupancyRL?.let { it == 2 })
        putIf("seat_occupied_rear_middle", d.occupancyRM?.let { it == 2 })
        putIf("seat_occupied_rear_right", d.occupancyRR?.let { it == 2 })
        putIf("ambient_light_level_1dark_5bright", d.lightLevel)
        putIf("key_fob_battery_ok", d.keyBatteryStatus?.let { it == 0 })
        putIf("rain_detected", d.rain?.let { it == 1 })
        // Blind-spot cameras are a BYDMate feature, not a car parameter: the driver asks why
        // the picture does not appear, and the answer is usually "off" or "below the threshold".
        runCatchingCancellable { BlindSpotPreferences(context) }.getOrNull()?.let { bs ->
            o.put("blind_spot_cameras_enabled", bs.enabled)
            if (bs.enabled) o.put("blind_spot_cameras_from_kmh", bs.thresholdKmh)
        }
        // Deterministic belt flag (same pattern as tire_pressure_warning): moving with
        // the driver unbuckled, or an occupied front passenger seat unbuckled.
        val moving = (d.speed ?: 0) > 0
        val driverUnbuckled = d.seatbeltFL == 0
        val passengerUnbuckled = d.occupancyFR == 2 && d.seatbeltFR == 0
        if (moving && (driverUnbuckled || passengerUnbuckled)) {
            o.put("seatbelt_warning", "ktoś nie ma zapiętego pasa podczas jazdy, ostrzeż kierowcę")
        }
        locationProvider()?.let { (lat, lon) ->
            o.put("gps_lat", lat)
            o.put("gps_lon", lon)
            runCatchingCancellable { placeRepository.getAllSnapshot() }.getOrNull()
                ?.firstOrNull { PlaceGeometry.isInside(lat, lon, it.lat, it.lon, it.radiusM) }
                ?.let { p -> o.put("place", p.name) }
        }
        var soh: Double? = null
        runCatchingCancellable { batteryStateRepository.refresh() }.getOrNull()?.let { b ->
            soh = b.sohPercent?.toDouble()
            // Float -> Double: Android org.json has no put(String, float) overload.
            putIf("soh_percent", soh)
            putIf("lifetime_km", b.lifetimeKm?.toDouble())
            putIf("lifetime_kwh", b.lifetimeKwh?.toDouble())
        }
        // A DM-i reports its own ranges; SOC x capacity would be an invented range there.
        val hybrid = vehicleKindMonitor?.kind?.value == VehicleKind.HYBRID
        if (hybrid) {
            putIf("range_km", d.evRangeKm)
            putIf("ev_range_km", d.evRangeKm)
            putIf("fuel_percent", d.fuelPercent)
            putIf("fuel_range_km", d.fuelRangeKm)
            putIf("engine_rpm", d.engineRpm)
            putIf("mileage_hev_km", d.mileageHevKm)
            putIf("mileage_ev_km", d.mileageEvKm)
        }
        // Every figure below is SOC x capacity, so a DM-i gets none of them.
        if (!hybrid) runCatchingCancellable { rangeCalculator.estimateDetailed(d.soc, d.totalElecConsumption, d.avgBatTemp) }
            .getOrNull()?.let { est ->
                o.put("range_km", est.rangeKm.roundToInt())
                o.put("battery_capacity_kwh", est.capacityKwh)
                o.put("battery_remaining_kwh", (est.remainingKwh * 10).roundToInt() / 10.0)
                o.put("avg_consumption_kwh_100km", (est.avgKwhPer100 * 10).roundToInt() / 10.0)
                val s = soh
                if (s != null && s in com.bydmate.app.data.charging.AutoserviceChargingDetector.SOH_SANITY_MIN..100.0) {
                    o.put("battery_effective_capacity_kwh",
                        ((est.capacityKwh * s / 100.0) * 10).roundToInt() / 10.0)
                }
            }
        return o.toString()
    }

    /**
     * Real drive mode name from SETTING_TARGET_DRIVING_MODE (dev=1023, the fid DriveModeChannel
     * writes and verifies), through the same HelperClient read DriveModeChannel uses -- unlike
     * the dev=1006 fid in [fallback], it also reflects sand/mud/mountain/rock/smart. Falls back
     * to the dev=1006 mapping when [helperClient] is unset, the read fails, or it is a sentinel.
     */
    private suspend fun driveModeName(fallback: Int?): String? {
        val target = helperClient?.let { hc ->
            runCatchingCancellable { hc.read(WriteAllowlist.DRIVE_MODE_DEV, WriteAllowlist.DRIVE_MODE_TARGET_FID)?.toInt() }
                .getOrNull()
        }?.let { SentinelDecoder.decodeInt(it) }
        val name = when (target) {
            null -> null
            DriveMode.TARGET_FLOTATION -> "FLOTATION"
            else -> DriveMode.entries.firstOrNull { it.value == target }?.name
        }
        return name ?: when (fallback) { 1 -> "ECO"; 2 -> "SPORT"; 3 -> "NORMAL"; 4 -> "SNOW"; else -> null }
    }

    // --- get_weather ---

    private suspend fun getWeather(args: JSONObject): String {
        val city = args.optString("city").trim().ifBlank { null }
        val lat: Double
        val lon: Double
        // Name of the place the forecast is for: the geocoder's name on the city path, the
        // saved Place on the GPS path (Open-Meteo has no reverse geocoding), null if neither.
        val location: String?
        if (city != null) {
            val geo = weatherCall("geocode") { weatherClient.geocode(city) }.getOrElse { return weatherErrorJson(it) }
            lat = geo.lat
            lon = geo.lon
            location = geo.name.ifBlank { null }
        } else {
            val gps = locationProvider() ?: return """{"error":"brak GPS i nie podano miasto"}"""
            lat = gps.first
            lon = gps.second
            location = placeNameAt(lat, lon)
        }
        val forecast = weatherCall("forecast") { weatherClient.forecast(lat, lon) }.getOrElse { return weatherErrorJson(it) }
        if (location == null) return forecast
        // location is a String, so no put(String, float) hazard here.
        return JSONObject(forecast).put("location", location).toString()
    }

    // Same saved-place lookup as get_vehicle_state: first Place whose radius contains the fix,
    // exception-safe so a DAO failure just omits the name instead of failing the weather call.
    private suspend fun placeNameAt(lat: Double, lon: Double): String? =
        runCatchingCancellable { placeRepository.getAllSnapshot() }.getOrNull()
            ?.firstOrNull { PlaceGeometry.isInside(lat, lon, it.lat, it.lon, it.radiusM) }
            ?.name

    // One line per failed weather request: exception class, HTTP code and time only. The message
    // and the URL are never logged, the forecast query carries the car's coordinates.
    private suspend fun <T> weatherCall(step: String, call: suspend () -> Result<T>): Result<T> {
        val startMs = nowMs()
        return call().onFailure { e ->
            Log.w(TAG, "get_weather: $step failed: ${e.javaClass.simpleName} " +
                "http=${(e as? WeatherClient.HttpError)?.code ?: "-"} took=${nowMs() - startMs}ms")
        }
    }

    // Raw Throwable messages are English ("timeout", "No value for current") and must not leak
    // into the error JSON the LLM reads back to the driver; only WeatherClient.UserError carries
    // deliberately user-facing Russian text.
    private fun weatherErrorJson(e: Throwable): String {
        val msg = (e as? WeatherClient.UserError)?.message ?: "pogoda jest niedostępna"
        return JSONObject().put("error", msg).toString()
    }

    // --- call_contact ---

    // Phone numbers never appear in the tool response: the single-match branch reports
    // only the contact's name, and the ambiguous branch reports only candidate names.
    private suspend fun callContact(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"nie podano nazwy"}"""
        val hasPermission = runCatchingCancellable { contactLookup.hasPermission() }.getOrDefault(false)
        if (!hasPermission) return """{"error":"brak dostępu do kontaktów, włącz go w ustawieniach"}"""
        val matches = runCatchingCancellable { contactLookup.findByName(name) }
            .getOrElse { return """{"error":"nie udało się odczytać kontaktów"}""" }
        return when {
            matches.isEmpty() -> """{"error":"nie znaleziono kontaktu"}"""
            matches.size == 1 -> {
                val m = matches.first()
                val action = ActionDef(
                    command = "", displayName = m.name, kind = "call",
                    payload = JSONObject().put("phone", m.phone).put("autoDial", true).toString(),
                )
                return confirmDangerous(action, "Połączenie: ${m.name}")
            }
            else -> JSONObject().put("matches", JSONArray(matches.map { it.name })).toString()
        }
    }

    // --- web_search ---

    // Exa failure falls through to the primary connection's native search; only when the
    // whole chain fails does it collapse to the fixed Russian string: raw Throwable
    // messages from search engines are English and must never reach the LLM.
    private suspend fun webSearch(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return """{"error":"nie podano zapytanie"}"""
        val exaKey = runCatchingCancellable {
            settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "")
        }.getOrDefault("")
        if (exaKey.isNotBlank()) {
            exaSearchClient.search(exaKey, query).onSuccess { return it }
            // Exa failed: fall through to the primary connection's native search.
        }
        return nativeSearch(query)
    }

    /** Native search of the PRIMARY connection; custom connections have none. */
    private suspend fun nativeSearch(query: String): String {
        val conn = runCatchingCancellable { llmConnections.primary() }.getOrNull() ?: return SEARCH_ERROR
        return when (conn.id) {
            LlmConnectionResolver.ID_OPENROUTER -> openRouterWebSearch(conn, query)
            LlmConnectionResolver.ID_ZAI ->
                zaiSearchClient.search(conn.apiKey, query).getOrElse { SEARCH_ERROR }
            else -> SEARCH_ERROR
        }
    }

    /** One-shot completion with the openrouter:web_search server tool doing the search server-side. */
    private suspend fun openRouterWebSearch(conn: LlmConnection, query: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "Znajdź w internecie aktualne informacje na prośbę użytkownika i przedstaw fakty krótko, po polsku, z datami i źródłami."))
            .put(JSONObject().put("role", "user").put("content", query))
        val tools = JSONArray().put(JSONObject().put("type", "openrouter:web_search"))
        val message = openRouterClient.chatRaw(conn.baseUrl, conn.apiKey, conn.model, messages, tools)
            .getOrElse { return SEARCH_ERROR }
        val content = if (message.isNull("content")) null
            else message.optString("content").takeIf { it.isNotBlank() }
        return content ?: SEARCH_ERROR
    }

    // --- query_trips / query_charges ---

    /** Absolute range from "from"/"to" (YYYY-MM-DD, device timezone, "to" inclusive) when given;
     *  otherwise the legacy relative "period" (day/week/month back from now). Null = bad args. */
    private fun periodRange(args: JSONObject): Pair<Long, Long>? {
        val fromStr = args.optString("from").trim()
        if (fromStr.isNotEmpty()) {
            // SimpleDateFormat.parse consumes a prefix and ignores trailing garbage even with
            // isLenient=false ("2026-06-01xyz" parses fine) -- require the exact shape first.
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            fun parseDay(s: String): Long? =
                if (!s.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) null
                else runCatching { fmt.parse(s)!!.time }.getOrNull()
            val from = parseDay(fromStr) ?: return null
            val toStr = args.optString("to").trim()
            val toDay = if (toStr.isEmpty()) from else (parseDay(toStr) ?: return null)
            if (toDay < from) return null
            // Half-open day boundary (AC-14): DAO compares start_ts <= :to, so stop
            // 1 ms short of next-day midnight — an exactly-00:00 record belongs to
            // the NEXT day, not this period.
            return from to toDay + DAY_MS - 1
        }
        val now = nowMs()
        val from = when (args.optString("period")) {
            "day" -> now - DAY_MS
            "week" -> now - 7 * DAY_MS
            "month" -> now - 30 * DAY_MS
            else -> return null
        }
        return from to now
    }

    private suspend fun queryTrips(args: JSONObject): String {
        val (from, to) = periodRange(args)
            ?: return PERIOD_ERROR
        val s = tripDao.getPeriodSummary(from, to)
        return JSONObject().apply {
            put("trips", s.tripCount)
            put("distance_km", round1(s.totalKm))
            put("energy_kwh", round1(s.totalKwh))
            if (s.totalKm > 0.5) put("avg_kwh_per_100km", round1(s.totalKwh / s.totalKm * 100))
            put("cost", round1(s.totalCost))
        }.toString()
    }

    private suspend fun queryCharges(args: JSONObject): String {
        val (from, to) = periodRange(args)
            ?: return PERIOD_ERROR
        val summary = chargeDao.getPeriodSummary(from, to)
        val split = InsightStatsAggregator.chargingWeek(
            // <= to matches getPeriodSummary's inclusive upper bound so the AC/DC split always
            // sums to the top-level totals, including a charge starting exactly at the boundary.
            chargeDao.getCompletedSince(from).filter { it.startTs <= to })
        return JSONObject().apply {
            put("sessions", summary.sessionCount)
            put("energy_kwh", round1(summary.totalKwh))
            put("cost", round1(summary.totalCost))
            put("ac_kwh", round1(split.acKwh))
            put("dc_kwh", round1(split.dcKwh))
            put("ac_sessions", split.acSessions)
            put("dc_sessions", split.dcSessions)
        }.toString()
    }

    // Mirrors the manual add path (ChargesViewModel.onCreateNewCharge + ChargeEditDialog):
    // kWh derived from SOC delta x battery capacity when both SOC given, otherwise explicit
    // kwh; cost = kWh x tariff (settings default by type unless spoken). detectionSource
    // stays "manual" so every existing screen/stat treats it like a hand-entered record.
    private suspend fun addCharge(args: JSONObject): String {
        val type = args.optString("type", "AC").uppercase(Locale.US)
        if (type != "AC" && type != "DC") return BAD_ARGS_ERROR
        val socStart = if (args.has("soc_start")) args.optInt("soc_start", -1) else null
        val socEnd = if (args.has("soc_end")) args.optInt("soc_end", -1) else null
        if (socStart != null && socStart !in 0..100) return BAD_ARGS_ERROR
        if (socEnd != null && socEnd !in 0..100) return BAD_ARGS_ERROR
        if (socStart != null && socEnd != null && socEnd <= socStart) {
            return """{"error":"końcowy procent musi być większy od początkowego"}"""
        }

        val kwhFromSoc = if (socStart != null && socEnd != null) {
            val capacity = runCatchingCancellable { settingsRepository.getBatteryCapacity() }
                .getOrDefault(72.9)
            (socEnd - socStart) / 100.0 * capacity
        } else null
        val kwhManual = if (args.has("kwh")) args.optDouble("kwh").takeIf { it > 0.0 } else null
        val kwh = kwhFromSoc ?: kwhManual
            ?: return """{"error":"wymagane jest albo procent naładowania z i po, albo liczba kWh"}"""

        val spokenTariff = if (args.has("tariff")) {
            args.optDouble("tariff").takeIf { it >= 0.0 } ?: return BAD_ARGS_ERROR
        } else null

        val startTs = args.optString("date").trim().let { dateStr ->
            if (dateStr.isEmpty()) System.currentTimeMillis()
            else parseDayToNoon(dateStr) ?: return """{"error":"data powinna być w formacie RRRR-MM-DD"}"""
        }

        val cost = chargeCost(spokenTariff, startTs, type, kwh)
        val tariff = if (kwh > 0.0) cost / kwh else 0.0

        val saved = saveManualCharge(
            ChargeEntity(
                id = 0L,
                startTs = startTs,
                endTs = startTs + 3_600_000L,
                type = type,
                gunState = 2,
                detectionSource = "manual",
                socStart = socStart,
                socEnd = socEnd,
                kwhCharged = kwh,
                kwhChargedSoc = kwhFromSoc,
                cost = cost,
                costManual = spokenTariff != null,
            )
        )
        if (!saved) return INTERNAL_ERROR

        val currency = runCatchingCancellable { settingsRepository.getCurrency() }.getOrNull()
        return JSONObject()
            .put("ok", true)
            .put("type", type)
            .apply {
                if (socStart != null) put("soc_start", socStart)
                if (socEnd != null) put("soc_end", socEnd)
            }
            .put("kwh", Math.round(kwh * 100) / 100.0)
            .put("cost", Math.round(cost * 100) / 100.0)
            .put("tariff", tariff)
            .apply { currency?.let { put("currency", it.symbol) } }
            .toString()
    }

    /**
     * Stores a hand-entered session and re-prices what it can affect: a backdated charge
     * changes what a kWh in the pack cost from that day on, so the trips already priced
     * after it have to be re-priced too. False when the row could not be written.
     */
    private suspend fun saveManualCharge(charge: ChargeEntity): Boolean {
        runCatchingCancellable { chargeDao.insert(charge) }.getOrElse { return false }
        runCatchingCancellable { costCalculator?.recalculate(charge.startTs, Long.MAX_VALUE) }
        return true
    }

    /**
     * A spoken rate is the user pricing the session by hand; without one the period covering
     * that date decides, losses included.
     */
    private suspend fun chargeCost(
        spokenTariff: Double?,
        startTs: Long,
        type: String,
        kwh: Double,
    ): Double {
        if (spokenTariff != null) return kwh * spokenTariff
        return runCatchingCancellable {
            costCalculator?.costForNewCharge(startTs = startTs, type = type, kwhCharged = kwh)
        }.getOrNull() ?: 0.0
    }

    private suspend fun statsSummary(args: JSONObject): String {
        val periodDays = if (args.optString("period") == "month") 30 else 7
        val metrics = runCatchingCancellable { insightsManager.dynamicsFor(periodDays) }
            .getOrElse { return INTERNAL_ERROR }
        if (metrics.isEmpty()) return """{"error":"za mało danych za ten okres"}"""
        val arr = JSONArray()
        for (m in metrics) {
            arr.put(JSONObject().apply {
                put("label", m.label)
                put("current", m.current)
                if (m.previous != null) put("previous", m.previous)
                if (m.changePct != null) put("change_pct", m.changePct)
                put("sentiment", m.sentiment)
            })
        }
        return JSONObject()
            .put("period_days", periodDays)
            .put("metrics", arr)
            .toString()
    }

    // Strict YYYY-MM-DD -> that local day at 12:00 (backdated entries need no exact time;
    // noon keeps the record inside the intended calendar day in any timezone math).
    private fun parseDayToNoon(s: String): Long? {
        if (!s.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) return null
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        val day = runCatching { fmt.parse(s)!!.time }.getOrNull() ?: return null
        return day + 12L * 3_600_000L
    }

    // --- control tools ---

    // P7 origin-based defense: a dangerous agent-initiated action never fires
    // directly. It shows a confirmation overlay and dispatches ONLY from the
    // user's "confirm" tap (via confirmScope, off the UI thread). Fail-closed:
    // if the overlay cannot be shown (SYSTEM_ALERT_WINDOW not granted), the
    // action is refused, never executed silently.
    private fun confirmDangerous(action: ActionDef, summary: String): String {
        val shown = confirmGate(
            context,
            "Agent głosowy",
            summary,
            // Re-read live vehicle data at confirm time (mirrors AutomationEngine ~line 573):
            // the overlay can sit open while the car accelerates, so the >30 km/h unlock
            // gate must see current speed, not the stale snapshot captured at tool-call time.
            { confirmScope.launch { actionDispatcher.dispatch(action, TrackingService.lastData.value) } },
            { },
        )
        return if (shown) {
            JSONObject().put("ok", true).put("status", "oczekuje potwierdzenia na ekranie").toString()
        } else {
            JSONObject().put("ok", false)
                .put("error", "wymagane jest potwierdzenie na ekranie, ale brak uprawnienia do nakładki")
                .toString()
        }
    }

    private suspend fun vehicleControl(args: JSONObject): String {
        val input = args.optString("command").trim()
        if (input.isEmpty()) return """{"error":"nie podano polecenie"}"""
        val valueOrNull = if (args.has("value")) args.optInt("value") else null
        // Documented surface is the readable id catalog; raw Chinese strings are
        // still accepted for backward compat (pre-catalog callers, T4 free string).
        val command = AgentCommandCatalog.resolve(input, valueOrNull)
            ?: input.takeIf { it.removePrefix("迪加") !in CommandTranslator.LOCAL_VOICE_ONLY && CommandTranslator.resolve(it).isNotEmpty() }
            ?: return unknownCommandError(input)
        val snapshot = gate.vehicleSnapshot()
        // Fail-closed, same invariant as VoiceController.execute: getBlockReason()
        // allows window-open when the whole snapshot is missing, so refuse here.
        if (snapshot == null && (ActionDispatcher.isWindowOpenCommand(command) || ActionDispatcher.isSunroofOpenCommand(command))) {
            return """{"error":"prędkość jest nieznana, nie otwieram okien ani szyberdachu"}"""
        }
        val actionDef = ActionDef(command = command, displayName = command, kind = "param")
        if (ActionDispatcher.isDangerousAction(actionDef)) {
            return confirmDangerous(actionDef, command)
        }
        val result = actionDispatcher.dispatch(actionDef, data = snapshot)
        return dispatchJson(result)
    }

    private fun unknownCommandError(input: String): String {
        val sample = AgentCommandCatalog.ALL.take(10).joinToString(", ") { it.id }
        return JSONObject().put("error", "nieznane polecenie: $input. Dostępne polecenia: $sample…").toString()
    }

    private suspend fun mediaVolume(args: JSONObject): String {
        val op = args.optString("op").trim()
        if (op.isEmpty()) return """{"error":"nie podano operacja"}"""
        val result = actionDispatcher.dispatch(
            ActionDef(command = "media_volume", displayName = "media_volume",
                kind = "media_volume", payload = op),
            data = gate.vehicleSnapshot(),
        )
        return dispatchJson(result)
    }

    private suspend fun runAutomation(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"nie podano nazwy"}"""
        val rule = ruleDao.getEnabled()
            .firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
            ?: return """{"error":"nie znaleziono automatyzacji"}"""
        return when (val r = automationEngine.fireVoiceRule(rule.id, gate.vehicleSnapshot())) {
            is VoiceFireResult.Fired ->
                if (r.success) OK else """{"error":"nie wykonano części działań"}"""
            VoiceFireResult.Confirming -> """{"ok":true,"status":"oczekuje potwierdzenia na ekranie"}"""
            VoiceFireResult.ParkRequired -> """{"error":"działa tylko na postoju"}"""
            VoiceFireResult.SpeedUnknown -> """{"error":"prędkość jest nieznana"}"""
            VoiceFireResult.NotFound -> """{"error":"nie znaleziono automatyzacji"}"""
        }
    }

    private suspend fun listAutomations(): String {
        val rules = runCatchingCancellable { ruleDao.getAllList() }
            .getOrElse { return """{"error":"nie udało się odczytać automatyzacji"}""" }
        val arr = JSONArray()
        rules.forEach { rule ->
            arr.put(JSONObject().apply {
                put("name", rule.name)
                put("enabled", rule.enabled)
                put("triggers", TriggerDef.listFromJson(rule.triggers).joinToString(", ") { it.displayName })
                put("actions", ActionDef.listFromJson(rule.actions).joinToString(", ") { it.displayName })
            })
        }
        return JSONObject().put("automations", arr).toString()
    }

    // --- places ---

    private suspend fun listPlaces(): String {
        val places = runCatchingCancellable { placeRepository.getAllSnapshot() }
            .getOrElse { return """{"error":"nie udało się odczytać miejsc"}""" }
        val arr = JSONArray()
        places.forEach { p ->
            arr.put(JSONObject()
                .put("name", p.name)
                .put("lat", p.lat)
                .put("lon", p.lon)
                .put("radius_m", p.radiusM))
        }
        return JSONObject().put("places", arr).toString()
    }

    private suspend fun createPlace(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"nie podano nazwy"}"""
        if (name.length > 40) return """{"error":"nazwa jest dłuższa niż 40 znaków"}"""
        val existing = runCatchingCancellable { placeRepository.getAllSnapshot() }
            .getOrElse { return """{"error":"nie udało się utwórz miejsce"}""" }
        if (existing.any { it.name.trim().equals(name, ignoreCase = true) }) {
            return JSONObject().put("error", "miejsce o nazwie «$name» już istnieje").toString()
        }
        if (existing.size >= MAX_PLACES) return """{"error":"osiągnięto limit w 50 miejsc"}"""
        val hasLat = args.has("lat") && !args.isNull("lat")
        val hasLon = args.has("lon") && !args.isNull("lon")
        val coords: Pair<Double, Double> = when {
            hasLat && hasLon -> {
                val la = args.optDouble("lat", Double.NaN)
                val lo = args.optDouble("lon", Double.NaN)
                if (!la.isFinite() || la !in -90.0..90.0 || !lo.isFinite() || lo !in -180.0..180.0) {
                    return """{"error":"nieprawidłowe współrzędne"}"""
                }
                la to lo
            }
            hasLat != hasLon -> return """{"error":"wymagane są oba współrzędne lat i lon"}"""
            else -> locationProvider()
                ?: return """{"error":"brak pozycji GPS; podaj współrzędne albo utwórz miejsce na ekranie Automatyzacje"}"""
        }
        // Same radius bounds as PlaceEditDialog (20..500 m).
        val radius = args.optInt("radius_m", 100).coerceIn(20, 500)
        runCatchingCancellable {
            placeRepository.insert(PlaceEntity(name = name, lat = coords.first, lon = coords.second, radiusM = radius))
        }.getOrElse { return """{"error":"nie udało się utwórz miejsce"}""" }
        return JSONObject().put("ok", true).put("name", name).put("radius_m", radius).toString()
    }

    // --- driver memory ---

    private fun rememberFact(args: JSONObject): String =
        when (val result = driverMemory.remember(args.optString("fact"))) {
            is RememberResult.Stored -> JSONObject().put("ok", true)
                .put("fact", result.fact)
                .apply { result.evicted?.let { put("evicted", it) } }
                .toString()
            RememberResult.Duplicate -> """{"ok":true,"duplicate":true}"""
            is RememberResult.Rejected -> JSONObject().put("error", result.reason).toString()
        }

    private fun forgetFact(args: JSONObject): String {
        if (requireBoolArg(args, "all") == true) {
            return JSONObject().put("ok", true).put("forgotten_all", driverMemory.forgetAll()).toString()
        }
        val query = args.optString("fact").trim()
        if (query.isEmpty()) return """{"error":"nie podano, co zapomnieć"}"""
        return JSONObject().put("ok", true)
            .put("forgotten", JSONArray(driverMemory.forget(query))).toString()
    }

    // --- navigation ---

    // app rides the navigate payload only when the driver named Maps explicitly: without it
    // the dispatcher's default (Navigator) path stays byte-identical. #200.
    private fun JSONObject.putApp(app: String): JSONObject = if (app.isEmpty()) this else put("app", app)

    private fun navApp(args: JSONObject): String =
        if (args.optString("app").trim().equals("maps", ignoreCase = true)) "maps" else ""

    /** Per-command options of a route: «jedźmy» vs «wyznacz trasa», and which map app. */
    private data class NavOptions(val go: Boolean = false, val app: String = "")

    private suspend fun navigateTo(args: JSONObject): String {
        val destination = args.optString("destination").trim()
        // «jedźmy tam» vs «wyznacz trasa»: only the first one lets us press «Jedź».
        val opts = NavOptions(args.optBoolean("go", false), navApp(args))
        if (args.has("lat") && args.has("lon")) {
            val label = destination.ifEmpty { "punkt" }
            return dispatchRoute(args.getDouble("lat"), args.getDouble("lon"), "destination", label, opts)
        }
        if (destination.isEmpty()) return """{"error":"nie podano, dokąd jechać"}"""
        homeWorkTarget(destination)?.let { return navigateHomeWork(it, opts) }
        val placesResult = runCatchingCancellable { placeRepository.getAllSnapshot() }
        val place = placesResult.getOrNull()?.firstOrNull { it.name.equals(destination, ignoreCase = true) }
        if (place != null) return dispatchRoute(place.lat, place.lon, "place", place.name, opts)
        // Free text: geocode (Open-Meteo, city-level) so the Navigator builds a real route from
        // the current position instead of just opening map search (field defect APK 336: «trasa
        // do Orshi» opened a search window). Street-level addresses do not geocode and fall back.
        val geo = runCatchingCancellable { weatherClient.geocode(destination) }
            .getOrNull()?.getOrNull()
        if (geo != null) return dispatchRoute(geo.lat, geo.lon, "destination", geo.name, opts)
        val result = dispatchNavigate("Nawigacja", JSONObject().put("query", destination).putApp(opts.app))
        if (!result.success) return JSONObject()
            .put("error", result.reason ?: "nie udało się otworzyć Nawigator").toString()
        routeDestination = null
        return JSONObject().put("ok", true).put("mode", "search")
            .put("note", "nie udało się automatycznie znaleźć punktu, otwarto wyszukiwanie na mapie").toString()
    }

    private suspend fun searchOnMap(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return """{"error":"nie podano, co szukać"}"""
        val result = dispatchNavigate("Szukaj na mapie", JSONObject().put("query", query).putApp(navApp(args)))
        if (!result.success) return JSONObject()
            .put("error", result.reason ?: "nie udało się otworzyć Nawigator").toString()
        return JSONObject().put("ok", true).put("mode", "search")
            .put("note", "otwarto wyniki wyszukiwania w Nawigatorze; użytkownik sam wybierze punkt").toString()
    }

    private suspend fun showPointOnMap(args: JSONObject): String {
        val destination = args.optString("destination").trim()
        if (destination.isEmpty() && !(args.has("lat") && args.has("lon"))) return BAD_ARGS_ERROR
        // Explicit coordinates take priority; otherwise resolve from saved places or geocode.
        val coords: Triple<Double, Double, String>? = if (args.has("lat") && args.has("lon")) {
            Triple(args.getDouble("lat"), args.getDouble("lon"), destination.ifEmpty { "punkt" })
        } else {
            val place = runCatchingCancellable { placeRepository.getAllSnapshot() }
                .getOrNull()?.firstOrNull { it.name.equals(destination, ignoreCase = true) }
            if (place != null) Triple(place.lat, place.lon, place.name)
            else runCatchingCancellable { weatherClient.geocode(destination) }
                .getOrNull()?.getOrNull()?.let { Triple(it.lat, it.lon, it.name) }
        }
        // No coordinates found (street-level address etc.): degrade to map search so the
        // user still sees the point in the Navigator's search results.
        if (coords == null) return searchOnMap(
            JSONObject().put("query", destination).putApp(navApp(args)))
        val result = dispatchNavigate("Punkt na mapie",
            JSONObject().put("show", true).put("lat", coords.first)
                .put("lon", coords.second).put("label", coords.third).putApp(navApp(args)))
        if (!result.success) return JSONObject()
            .put("error", result.reason ?: "nie udało się otworzyć Nawigatora").toString()
        return JSONObject().put("ok", true).put("mode", "show").put("point", coords.third).toString()
    }

    private data class HomeWork(
        val placeName: String,
        val shortcut: String,
        val legacyPlaceName: String? = null,
    )

    private fun homeWorkTarget(destination: String): HomeWork? =
        when (destination.lowercase()) {
            "dom", "do domu", "mój dom" -> HomeWork("Dom", "home", legacyPlaceName = "Дом")
            "дом", "домой", "до дома", "мой дом", "home" -> HomeWork("Дом", "home")
            "praca", "do pracy", "moja praca" -> HomeWork("Praca", "work", legacyPlaceName = "Работа")
            "работа", "работу", "на работу", "моя работа", "work" -> HomeWork("Работа", "work")
            else -> null
        }

    // Home/work aliases: the BYDMate Place wins (exact coordinates, range assessment works),
    // then the Navigator's own saved Home/Work via its exported shortcut actions.
    private suspend fun navigateHomeWork(target: HomeWork, opts: NavOptions): String {
        val places = runCatchingCancellable { placeRepository.getAllSnapshot() }.getOrNull()
        val place = places?.firstOrNull { it.name.equals(target.placeName, ignoreCase = true) }
            ?: target.legacyPlaceName?.let { legacyPlaceName ->
                places?.firstOrNull { it.name.equals(legacyPlaceName, ignoreCase = true) }
            }
        if (place != null) return dispatchRoute(place.lat, place.lon, "place", place.name, opts)
        val result = dispatchNavigate("Nawigacja",
            JSONObject().put("shortcut", target.shortcut).put("go", opts.go).putApp(opts.app))
        if (!result.success) return JSONObject().put("error",
            result.reason ?: ("nie znaleziono adresu: dodaj Miejsce \"${target.placeName}\" w BYDMate " +
                "lub zapisz punkt \"${target.placeName}\" w Nawigatorze")).toString()
        // A new route to a point we do not know: the previous destination is no longer the route.
        routeDestination = null
        return JSONObject().put("ok", true).put("mode", "route").put("target", target.placeName)
            .put("note", "jeśli adres \"${target.placeName}\" jest zapisany w Nawigatorze, " +
                "trasa zostanie do niego wyznaczona; zasięg nie został oceniony, współrzędne są nieznane").toString()
    }

    private suspend fun dispatchRoute(
        lat: Double, lon: Double, labelKey: String, label: String, opts: NavOptions = NavOptions(),
    ): String {
        val result = dispatchNavigate("Nawigacja",
            JSONObject().put("lat", lat).put("lon", lon).put("go", opts.go).putApp(opts.app))
        if (!result.success) return JSONObject()
            .put("error", result.reason ?: "nie udało się otworzyć Nawigatora").toString()
        rememberRoute(lat, lon)
        val json = JSONObject().put("ok", true).put("mode", "route").put(labelKey, label)
        rangeAssessment(lat, lon)?.let { ra ->
            ra.keys().forEach { k -> json.put(k, ra.get(k)) }
        }
        return json.toString()
    }

    // Same distance-vs-range math as rangeToDestination, but advisory: returns null when
    // any input is missing so route dispatch is never blocked by a failed assessment.
    private suspend fun rangeAssessment(lat: Double, lon: Double): JSONObject? {
        val gps = locationProvider() ?: return null
        val d = gate.vehicleSnapshot() ?: return null
        val rangeKm = runCatchingCancellable {
            rangeCalculator.estimate(d.soc, d.totalElecConsumption, d.avgBatTemp)
        }.getOrNull() ?: return null
        val straightKm = PlaceGeometry.distanceMeters(gps.first, gps.second, lat, lon) / 1000.0
        val distanceKm = (straightKm * ROAD_FACTOR).roundToInt()
        val range = rangeKm.roundToInt()
        val reserve = range - distanceKm
        val json = JSONObject()
            .put("distance_km", distanceKm)
            .put("range_km", range)
            .put("enough", range >= distanceKm)
            .put("reserve_km", reserve)
            .put("note", "distance_km jest przybliżone (w linii prostej ze współczynnikiem 1.25); nie nazywaj go " +
                "dokładnym pozostałym dystansem; dokładny dystans i czas podaje get_route_info podczas prowadzenia")
        if (range < distanceKm || reserve < distanceKm * 0.2) {
            json.put("charge_note",
                "ładunek jest na granicy lub go zabraknie; ostrzeż i zaproponuj znalezienie ładowarek po drodze")
        }
        return json
    }

    // --- find_chargers ---

    /** Where the agent's own navigate_to last built a route, for find_chargers ahead. In memory
     *  only: [session] ties it to the trip it was built in, [startKm] = straight km to it then. */
    private data class RouteDestination(val lat: Double, val lon: Double, val session: Long?, val startKm: Double?)

    @Volatile private var routeDestination: RouteDestination? = null

    /** Test seam - the GPS fixes of the last minutes, for the direction of travel. */
    internal var recentTrackProvider: () -> List<TrackPoint> =
        { TrackingService.recentTrack.snapshot(SystemClock.elapsedRealtime()) }

    /** Test seam - the ignition-on stamp of the current trip, null while the car is idle. */
    internal var sessionProvider: () -> Long? = { TrackingService.sessionStartedAt.value }

    private fun rememberRoute(lat: Double, lon: Double) {
        val startKm = locationProvider()?.let { PlaceGeometry.distanceMeters(it.first, it.second, lat, lon) / 1000.0 }
        routeDestination = RouteDestination(lat, lon, sessionProvider(), startKm)
    }

    /** What the car is doing, for find_chargers: course over the last kilometre, speed, the
     *  destination of the route built in this trip while still heading to it, range. */
    private data class TripContext(
        val course: Double? = null,
        val speedKmh: Int? = null,
        val destination: LatLon? = null,
        val rangeKm: Double? = null,
    )

    private suspend fun tripContext(car: LatLon): TripContext {
        val track = runCatchingCancellable { recentTrackProvider() }.getOrDefault(emptyList())
        val course = TravelCourse.of(track)
        val d = gate.vehicleSnapshot()
        val rangeKm = d?.let { runCatchingCancellable { rangeCalculator.estimate(it.soc, it.totalElecConsumption, it.avgBatTemp) }.getOrNull() }
        val session = sessionProvider()
        val destination = routeDestination
            ?.takeIf { session != null && it.session == session }
            ?.let { LatLon(it.lat, it.lon) to it.startKm }
            ?.takeIf { (point, startKm) -> ChargerSelection.stillApproaching(car, point, startKm, course) }
            ?.first
        return TripContext(course, d?.speed ?: track.lastOrNull()?.speedKmh?.roundToInt(), destination, rangeKm)
    }

    private suspend fun findChargers(args: JSONObject): String {
        val city = args.optString("city").trim()
        val origin = if (city.isNotEmpty()) {
            runCatchingCancellable { weatherClient.geocode(city) }.getOrNull()?.getOrNull()
                ?.let { LatLon(it.lat, it.lon) }
                ?: return """{"error":"nie znaleziono takiego miejsca, doprecyzuj nazwę"}"""
        } else locationProvider()?.let { LatLon(it.first, it.second) } ?: return """{"error":"brak GPS i nie podano miasta"}"""
        val connector = ChargeConnector.parse(args.optString("connector"))
            ?: runCatchingCancellable { settingsRepository.getChargeConnector() }.getOrDefault(ChargeConnector.GBT)
        // The direction of travel belongs to the car, not to a city asked by name.
        val trip = if (city.isEmpty()) tripContext(origin) else TripContext()
        val (where, radiusKm) = chargerScope(args, city.isNotEmpty(), trip)
        val found = runCatchingCancellable {
            chargerSearchClient.find(origin.lat, origin.lon, radiusKm * 1000, connector)
        }.getOrNull()?.getOrNull()
            ?: return """{"error":"usługa wyszukiwania ładowarek jest niedostępna, spróbuj później"}"""
        val choice = ChargerSelection.choose(origin, found.stations, connector, where, trip.course, trip.destination)
        // Counts and modes only: no place, name or coordinate.
        Log.i(TAG, "find_chargers: source=${found.source.code} found=${found.stations.size} picks=${choice.picks.size} " +
            "where=${where.code} ahead=${choice.ahead} course=${trip.course != null} dest=${trip.destination != null} " +
            "radius=${radiusKm}km connector=${connector.key} missing=${choice.connectorMissing} range=${trip.rangeKm != null}")
        Trace.event(TraceArea.AGENT, "chargers", "source" to found.source.code, "found" to found.stations.size,
            "picks" to choice.picks.size, "where" to where.code, "ahead" to choice.ahead.name.lowercase(),
            "radius_km" to radiusKm, "connector" to connector.key, by = Trace.cause())
        return chargersJson(found, choice, connector, radiusKm, trip)
    }

    /** Direction and radius of the search: a city is searched around it; `ahead` looks 30-100 km
     *  out by speed when the course or the route's end is known; otherwise 30 km around. */
    private fun chargerScope(args: JSONObject, city: Boolean, trip: TripContext): Pair<ChargerWhere, Int> {
        val asked = ChargerWhere.entries.firstOrNull { it.code.equals(args.optString("where").trim(), ignoreCase = true) }
        val where = if (city) ChargerWhere.AROUND else asked ?: ChargerSelection.defaultWhere(trip.course, trip.speedKmh)
        val canLookAhead = where == ChargerWhere.AHEAD && (trip.course != null || trip.destination != null)
        val radiusKm = when {
            args.has("radius_km") -> args.optInt("radius_km", CHARGER_RADIUS_KM).coerceIn(1, MAX_CHARGER_RADIUS_KM)
            canLookAhead -> ChargerSelection.aheadRadiusKm(trip.speedKmh)
            else -> CHARGER_RADIUS_KM
        }
        return where to radiusKm
    }

    private fun chargersJson(
        found: ChargerSearchClient.Found,
        choice: ChargerChoice,
        connector: ChargeConnector,
        radiusKm: Int,
        trip: TripContext,
    ): String {
        val connectorKnown = found.source == ChargerSource.BETA
        // Gateway: a whole-station status for some networks only, so known when any pick has one,
        // and it is the station's as a whole, not the car's connector's.
        val stationScope = !connectorKnown && choice.picks.any { it.station.status != null }
        val where = if (choice.ahead == AheadOutcome.NO_COURSE || choice.ahead == AheadOutcome.NOT_ASKED) {
            ChargerWhere.AROUND
        } else ChargerWhere.AHEAD
        return JSONObject()
            .put("status_source", found.source.code)
            .put("connector", connector.label)
            .put("connector_known", connectorKnown)
            .put("status_known", connectorKnown || stationScope)
            .putOpt("status_scope", if (connectorKnown) "connector" else "station".takeIf { stationScope })
            .put("where", where.code)
            .putOpt("course", trip.course?.let(::compassOf))
            .put("radius_km", radiusKm)
            .put("chargers", JSONArray().apply { choice.picks.forEach { put(chargerJson(it, trip.rangeKm)) } })
            .put("note", chargersNote(choice, connector, radiusKm, stationScope))
            .toString()
    }

    private fun chargerJson(pick: ChargerPick, rangeKm: Double?): JSONObject {
        val s = pick.station
        val json = JSONObject()
            .put("name", s.name)
            .putOpt("address", s.address)
            .putOpt("operator", s.operator)
            .put("distance_km", round1(pick.distanceKm))
            .put("direction_from_car", compassOf(pick.bearingDeg))
            .putOpt("position", pick.position?.code)
            .putOpt("detour_km", pick.detourKm?.let { round1(it.coerceAtLeast(0.0)) })
            .put("lat", s.lat)
            .put("lon", s.lon)
            // With the car's connectors known, a null status means unknown: no station-wide fallback.
            .putOpt("status", (if (pick.summary != null) pick.summary.status else s.status)?.code)
        pick.summary?.let { c ->
            json.put("connectors_total", c.total)
                .put("connectors_free", c.free)
                .putOpt("connectors_unknown", c.unknown.takeIf { it > 0 })
                .putOpt("max_power_kw", c.maxPowerKw)
                .putOpt("price_per_kwh", c.minPricePerKwh)
                .putOpt("price_max_per_kwh", c.maxPricePerKwh?.takeIf { it != c.minPricePerKwh })
                .putOpt("currency", c.currency)
        }
        // The car's type is missing here (listed only because no station has it): say what it has.
        if (pick.summary == null && s.connectors != null) {
            json.put("connector_types", JSONArray(s.connectors.map { c ->
                ChargeConnector.entries.firstOrNull { it.matches(c.standard) }?.label ?: c.standard
            }.distinct()))
        }
        s.statusAtMs?.let { at ->
            val ageMin = ((nowMs() - at) / 60_000L).coerceAtLeast(0L)
            json.put("status_age_min", ageMin)
            if (ageMin > STALE_STATUS_MIN) json.put("status_stale", true)
        }
        // Road distance is longer than the straight line: the same fudge as range_to_destination.
        if (rangeKm != null && pick.distanceKm * ROAD_FACTOR > rangeKm) json.put("beyond_range", true)
        return json
    }

    private fun chargersNote(choice: ChargerChoice, connector: ChargeConnector, radiusKm: Int, stationScope: Boolean): String {
        if (choice.picks.isEmpty()) return "nie znaleziono ładowarek w promieniu $radiusKm km"
        val parts = mutableListOf<String>()
        when (choice.ahead) {
            AheadOutcome.NOTHING_AHEAD ->
                parts += "nie znaleziono stacji na trasie; pokazano najbliższe w pobliżu: powiedz o tym"
            AheadOutcome.NO_COURSE ->
                parts += "pokazano stacje w pobliżu: kierunek jazdy jest nieznany, samochód stoi albo przejechał niewiele"
            AheadOutcome.DESTINATION ->
                parts += "stacje są na drodze do celu trasy, detour_km oznacza dodatkowy objazd w linii prostej"
            AheadOutcome.CORRIDOR, AheadOutcome.NOT_ASKED -> Unit
        }
        if (choice.connectorMissing) {
            parts += "w promieniu nie ma stacji ze złączem ${connector.label}; pokazano stacje z innymi " +
                "złączami (connector_types): powiedz o tym"
        }
        if (stationScope) parts += "zajętość dotyczy całej stacji, stan złącza samochodu jest nieznany"
        if (choice.picks.any { (it.summary?.unknown ?: 0) > 0 }) {
            parts += "dla części złączy (connectors_unknown) brak danych o zajętości: powiedz to, nie nazywaj ich zajętymi"
        }
        parts += "distance_km jest w linii prostej, drogą będzie dalej: powiedz „w linii prostej”"
        parts += "gdy kierowca wybierze stację, wywołaj navigate_to z jej lat i lon"
        return parts.joinToString("; ")
    }

    // --- where_am_i ---

    private suspend fun whereAmI(): String {
        val fix = gpsFixProvider()
            ?: return """{"error":"brak pozycji GPS samochodu, nie mogę określić, gdzie jesteśmy"}"""
        val lat = fix.lat
        val lon = fix.lon
        val client = settlementSearchClient
            ?: return """{"error":"wyszukiwanie miejscowości jest niedostępne"}"""
        Log.i(TAG, "where_am_i: request, fix age=${fix.ageMs / 1000}s live=${fix.live}")
        val searchStartMs = nowMs()
        val answer = runCatchingCancellable {
            client.search(lat, lon, SETTLEMENT_RADIUS_M, TOWN_RADIUS_M)
        }.getOrNull()?.getOrElse {
            Log.w(TAG, "where_am_i: search failed: ${it.message}")
            null
        } ?: return """{"error":"usługa map jest niedostępna, nie mogę określić, gdzie jesteśmy"}"""
        val latencyMs = nowMs() - searchStartMs
        return when (answer) {
            is SettlementSearchClient.Surroundings.Nearby -> nearbyJson(fix, answer.settlements, latencyMs, client.lastEndpoint)
            is SettlementSearchClient.Surroundings.Address -> addressJson(fix, answer, latencyMs, client.lastEndpoint)
        }
    }

    /** where_am_i from Overpass: settlement centres around the car, nearest first. */
    private fun nearbyJson(
        fix: GpsFix,
        found: List<SettlementSearchClient.Settlement>,
        latencyMs: Long,
        endpoint: String?,
    ): String {
        val lat = fix.lat
        val lon = fix.lon
        fun km(s: SettlementSearchClient.Settlement) = PlaceGeometry.distanceMeters(lat, lon, s.lat, s.lon) / 1000.0
        // A lone farmstead is a poor answer to "where am I" while any real settlement is around.
        val regular = found.filter { it.place != "isolated_dwelling" }
        val nearest = (regular.ifEmpty { found }).sortedBy { km(it) }.take(MAX_SETTLEMENTS)
        val town = found.filter { it.place == "city" || it.place == "town" }.minByOrNull { km(it) }
        // No place names in this log: user logs land in public GitHub issues and a name+distance
        // pair would localize the car. Counts, the rounded nearest distance, fix age/origin,
        // latency and the answering endpoint are enough to diagnose a "wrong answer" report.
        val nearestKm = nearest.firstOrNull()?.let { round1(km(it)) }?.toString() ?: "-"
        Log.i(TAG, "where_am_i: found=${found.size} nearest_km=$nearestKm town=${town != null} " +
            "fix_age=${fix.ageMs / 1000}s live=${fix.live} latency=${latencyMs}ms endpoint=${endpoint ?: "-"}")
        val json = JSONObject().put("fix_age_min", fix.ageMs / 60_000L)
        staleFixNote(fix)?.let { json.put("fix_note", it) }
        if (nearest.isEmpty()) {
            return json.put("settlements", JSONArray())
                .put("note", "w pobliżu nie znaleziono miejscowości w OpenStreetMap; nie zgaduj lokalizacji")
                .toString()
        }
        json.put("settlements", JSONArray().apply {
            nearest.forEach { put(settlementJson(lat, lon, it)) }
        })
        if (town != null) json.put("nearest_town", settlementJson(lat, lon, town))
        return json.put("note", "distance_km to odległość w linii prostej do centrum miejscowości, direction_from_car to " +
            "strona względem samochodu. Dane nie zawierają granic: nawet przy małej odległości nie mów, że samochód " +
            "jest w niej; powiedz „około N km od X, X na północ od nas”. Wymieniaj tylko te miejscowości").toString()
    }

    /** where_am_i when only Nominatim answered: the address of the point itself, no centres, so
     *  no distance or direction. The same no-place-names rule for the log line as above. */
    private fun addressJson(
        fix: GpsFix,
        address: SettlementSearchClient.Surroundings.Address,
        latencyMs: Long,
        endpoint: String?,
    ): String {
        Log.i(TAG, "where_am_i: address settlement=${address.settlement != null} street=${address.road != null} " +
            "fix_age=${fix.ageMs / 1000}s live=${fix.live} latency=${latencyMs}ms endpoint=${endpoint ?: "-"}")
        val json = JSONObject().put("fix_age_min", fix.ageMs / 60_000L)
        staleFixNote(fix)?.let { json.put("fix_note", it) }
        address.settlement?.let { name ->
            json.put("settlements", JSONArray().put(JSONObject().put("name", name)
                .putOpt("type", address.place?.let { SETTLEMENT_TYPES[it] ?: it })))
        }
        JSONObject()
            .putOpt("street", address.road)
            .putOpt("district", address.district)
            .putOpt("region", address.region)
            .putOpt("country", address.country)
            .takeIf { it.length() > 0 }
            ?.let { json.put("address", it) }
        return json.put("note", "ta odpowiedź nie zawiera odległości: to adres punktu samochodu z OpenStreetMap. " +
            "settlements to miejscowość z adresu, samochód jest w niej lub obok niej; address to ulica, dzielnica, " +
            "region i kraj. Powiedz „jesteśmy w X lub obok, ulica Y”. Wymieniaj tylko te miejsca").toString()
    }

    /** Null for a fresh fix. A stale fix always gets a hedge, live or seed: the 8 m GPS filter
     *  explains why a live fix stops updating while genuinely parked, but the car's current
     *  speed proves nothing about whether it moved after the signal was lost - so the hedge
     *  cannot be made conditional on it. A start-time seed may predate the drive. */
    private fun staleFixNote(fix: GpsFix): String? {
        if (fix.ageMs <= FRESH_FIX_MS) return null
        val min = fix.ageMs / 60_000L
        return if (fix.live) {
            "pozycja GPS nie była aktualizowana przez $min min: samochód mógł stać albo sygnał zniknął — " +
                "zawsze mów „według ostatnich danych”"
        } else {
            "po uruchomieniu nie było świeżego sygnału GPS, pozycję zapisano $min min temu — mów " +
                "„według ostatnich danych”"
        }
    }

    private fun settlementJson(lat: Double, lon: Double, s: SettlementSearchClient.Settlement): JSONObject =
        JSONObject()
            .put("name", s.name)
            .put("type", SETTLEMENT_TYPES[s.place] ?: s.place)
            .put("distance_km", round1(PlaceGeometry.distanceMeters(lat, lon, s.lat, s.lon) / 1000.0))
            .put("direction_from_car", compassRu(lat, lon, s.lat, s.lon))

    /** Eight-point compass direction from the car to the point, in Russian. */
    private fun compassRu(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): String {
        val phi1 = Math.toRadians(fromLat)
        val phi2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val y = Math.sin(dLon) * Math.cos(phi2)
        val x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon)
        val bearing = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        return compassOf(bearing)
    }

    /** Eight-point compass name of a bearing in degrees from north, in Russian. */
    private fun compassOf(bearing: Double): String = COMPASS_RU[((bearing + 22.5) / 45.0).toInt() % 8]

    // Same saved-place-then-geocode lookup as navigateTo, plus a straight-line distance
    // (with a road-factor fudge) against the current range estimate.
    private suspend fun rangeToDestination(args: JSONObject): String {
        val destination = args.optString("destination").trim()
        if (destination.isEmpty()) return BAD_ARGS_ERROR
        val gps = locationProvider()
            ?: return """{"error":"brak pozycji GPS samochodu"}"""
        val place = runCatchingCancellable { placeRepository.getAllSnapshot() }
            .getOrNull()?.firstOrNull { it.name.equals(destination, ignoreCase = true) }
        val target = if (place != null) Triple(place.lat, place.lon, place.name)
            else runCatchingCancellable { weatherClient.geocode(destination) }
                .getOrNull()?.getOrNull()?.let { Triple(it.lat, it.lon, it.name) }
                ?: return """{"error":"nie znaleziono takiego miejsca, doprecyzuj nazwę"}"""
        val straightKm = PlaceGeometry.distanceMeters(
            gps.first, gps.second, target.first, target.second) / 1000.0
        val distanceKm = (straightKm * ROAD_FACTOR).roundToInt()
        val d = gate.vehicleSnapshot()
            ?: return """{"error":"brak danych z samochodu (brak połączenia lub samochód śpi)"}"""
        val rangeKm = runCatchingCancellable {
            rangeCalculator.estimate(d.soc, d.totalElecConsumption, d.avgBatTemp)
        }.getOrNull()
            ?: return """{"error":"brakuje danych do oceny zasięgu (potrzebny jest przebieg z uwzględnieniem zużycia)"}"""
        return JSONObject()
            .put("destination", target.third)
            .put("distance_km", distanceKm)
            .put("range_km", rangeKm.roundToInt())
            .put("enough", rangeKm.roundToInt() >= distanceKm)
            .put("reserve_km", rangeKm.roundToInt() - distanceKm)
            .put("note", "odległość w linii prostej ze współczynnikiem drogowym 1.25; ocena jest przybliżona")
            .toString()
    }

    private suspend fun routeInfo(): String {
        val snap = NaviRouteHolder.latest
        // Screen is windows-aware: findNavigatorRoot() sees the Navigator minimized or
        // projected to the cluster. The hub keeps the last 90 s of guidance from the
        // a11y feed / notification channel for moments when no window read succeeds.
        val screen = runCatching { naviScreenProvider() }.getOrNull()
        val hub = NavGuidanceHub.snapshot(nowMs())
        // The direct holder and window are Yandex-specific. A live foreign owner must hide
        // all of them, including SOC/range and energy fallback, rather than mixing two trips.
        if (hub.active && hub.owner != NavAppId.YANDEX) {
            return """{"error":"Brak danych trasy Yandex: inne prowadzenie jest aktywne"}"""
        }
        if (snap == null && screen == null && !hub.active)
            return """{"error":"Nawigator nie prowadzi trasy albo brak danych: nie ma powiadomienia, nie znaleziono okna Nawigatora ani świeżych danych prowadzenia"}"""
        return JSONObject().apply {
            snap?.maneuver?.let { put("maneuver", it) }
            if (snap?.maneuver == null) snap?.maneuverIcon?.let { put("maneuver_icon", it) }
            (snap?.maneuverDistance ?: screen?.maneuverDistance)?.let { put("maneuver_distance", it) }
            (snap?.street ?: screen?.street)?.let { put("street", it) }
            if (snap != null && snap.bigTexts.isNotEmpty()) put("route_lines", JSONArray(snap.bigTexts))
            snap?.title?.let { put("raw_title", it) }
            snap?.text?.let { put("raw_text", it) }
            snap?.subText?.let { put("raw_sub_text", it) }
            screen?.remainingDistance?.let { put("remaining_distance", it) }
            screen?.remainingTime?.let { put("remaining_time", it) }
            screen?.arrivalTime?.let { put("arrival_time", it) }
            screen?.speedLimit?.let { put("speed_limit", it) }
            screen?.exitNumber?.let { put("exit_number", it) }
            // Unified hub fallback: numerics captured by the HUD a11y feed or the
            // notification channel fill gaps left by both direct sources.
            if (hub.active && hub.owner == NavAppId.YANDEX) {
                if (!has("maneuver")) {
                    com.bydmate.app.navdata.NavManeuverCodes.gaodePhrase(hub.maneuverGaode)
                        ?.let { put("maneuver", it) }
                }
                if (!has("maneuver_distance") && hub.distanceMeters > 0)
                    put("maneuver_distance", formatMeters(hub.distanceMeters))
                if (!has("street") && hub.road.isNotEmpty()) put("street", hub.road)
                if (!has("speed_limit") && hub.speedLimit > 0)
                    put("speed_limit", hub.speedLimit.toString())
                if (!has("remaining_distance") && hub.totalDistMeters > 0)
                    put("remaining_distance", formatMeters(hub.totalDistMeters))
                if (!has("remaining_time") && hub.etaSeconds > 0)
                    put("remaining_time", formatEtaSeconds(hub.etaSeconds))
                put("hub_age_sec", (nowMs() - hub.lastUpdateMs) / 1000L)
            }
            // Capture SOC, range, and detailed estimate for energy projection.
            val routeEst: RangeEstimate? = gate.vehicleSnapshot()?.let { d ->
                put("soc", d.soc)
                val e = runCatchingCancellable {
                    rangeCalculator.estimateDetailed(d.soc, d.totalElecConsumption, d.avgBatTemp)
                }.getOrNull()
                e?.let { put("range_km", it.rangeKm.roundToInt()) }
                e
            }
            // Remaining route length: hub numeric first, else the Navigator screen string.
            val remainingRouteKm: Double? =
                if (hub.active && hub.owner == NavAppId.YANDEX && hub.totalDistMeters > 0) hub.totalDistMeters / 1000.0
                else parseDistanceKm(screen?.remainingDistance)
            // Guard capacityKwh > 0 so the division for soc_at_arrival never produces NaN.
            if (routeEst != null && routeEst.capacityKwh > 0 && routeEst.avgKwhPer100 > 0
                && remainingRouteKm != null && remainingRouteKm > 0) {
                val needed = remainingRouteKm * routeEst.avgKwhPer100 / 100.0
                val socAtArrival = (routeEst.remainingKwh - needed) / routeEst.capacityKwh * 100.0
                put("energy_estimate", org.json.JSONObject().apply {
                    put("remaining_km", (remainingRouteKm * 10).roundToInt() / 10.0)
                    put("avg_consumption_kwh_100km", (routeEst.avgKwhPer100 * 10).roundToInt() / 10.0)
                    put("energy_needed_kwh", (needed * 10).roundToInt() / 10.0)
                    put("battery_now_kwh", (routeEst.remainingKwh * 10).roundToInt() / 10.0)
                    put("soc_at_arrival_percent", socAtArrival.roundToInt().coerceIn(0, 100))
                    put("enough", routeEst.remainingKwh >= needed)
                    if (socAtArrival < 10.0) put("warning",
                        "na mecie pozostanie mniej niż 10 procent baterii; ostrzeż kierowcę")
                    put("note", "zużycie jest średnią z danych aplikacji; ocena jest przybliżona")
                })
            }
            // Screen values are live; notification age matters only when it is the source.
            put("age_min", if (snap != null) ((nowMs() - snap.postedAtMs) / 60_000L) else 0L)
            put("note", "dane z powiadomienia Nawigatora, jego okna (w tym zestawu wskaźników i " +
                "trybu zminimalizowanego) oraz zgromadzonych danych prowadzenia (hub_age_sec = ich wiek)")
        }.toString()
    }

    private fun formatMeters(meters: Int): String =
        if (meters >= 1000) String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
        else "$meters m"

    // Strict full-string match for a distance with an optional decimal or thousands separator (thousands
    // separators: space or NBSP). Signs, latin units or extra text -> null.
    private fun parseDistanceKm(text: String?): Double? {
        if (text == null) return null
        val m = Regex("""^\s*(\d{1,3}(?:[  ]\d{3})+|\d+)(?:[.,](\d+))?\s*(км|м)\.?\s*$""")
            .find(text) ?: return null
        val intPart = m.groupValues[1].replace(" ", "").replace(" ", "")
        val frac = m.groupValues[2]
        val v = (intPart + if (frac.isNotEmpty()) ".$frac" else "").toDoubleOrNull() ?: return null
        return if (m.groupValues[3] == "м") v / 1000.0 else v
    }

    private fun formatEtaSeconds(seconds: Int): String {
        val min = seconds / 60
        return if (min >= 60)
            String.format(java.util.Locale.US, "%d godz. %02d min", min / 60, min % 60)
        else "$min min"
    }

    private suspend fun goHomeScreen(): String {
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = "Strona główna", kind = "go_home"), data = null)
        return if (result.success) """{"ok":true}"""
        else JSONObject().put("error", result.reason ?: "nie udało się").toString()
    }

    private suspend fun playMusic(args: JSONObject): String {
        val query = args.optString("query").trim()
        val mode = args.optString("mode").takeIf { it == "search" } ?: "play"
        val payload = if (query.isEmpty()) JSONObject().put("mode", "mybeat")
            else JSONObject().put("mode", mode).put("query", query)
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = "Muzyka", kind = "yandex_music",
                payload = payload.toString()), data = null)
        return if (result.success) JSONObject().put("ok", true)
            .put("playing", if (query.isEmpty()) "Moja fala" else query).toString()
        else JSONObject().put("error", result.reason ?: "nie udało się włączyć muzyki").toString()
    }

    private suspend fun youtubeTool(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return BAD_ARGS_ERROR
        val mode = args.optString("mode").takeIf { it == "search" } ?: "play"
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = "YouTube", kind = "youtube",
                payload = JSONObject().put("mode", mode).put("query", query).toString()), data = null)
        return if (result.success) JSONObject().put("ok", true).put(
            if (mode == "play") "playing" else "searching", query).toString()
        else JSONObject().put("error", result.reason ?: "nie udało się otworzyć YouTube").toString()
    }

    /** Resolve a human app name to (label, packageName) via aliases + launcher labels. */
    private suspend fun resolveLauncherApp(name: String): Built<Pair<String, String>> {
        val apps = runCatchingCancellable { launcherAppsProvider() }.getOrNull()
            ?: return Built.Error("lista aplikacji jest niedostępna")
        val needle = normalizedLauncherName(name)
        // An exact alias keeps its declared package priority. It still resolves through the
        // launcher list, so a fleet-specific alias cannot launch a package that is absent.
        val exactAliasMatch = APP_ALIASES.entries.firstOrNull { (alias, _) ->
            normalizedLauncherName(alias) == needle
        }?.value?.firstNotNullOfOrNull { pkg ->
            apps.firstOrNull { it.second == pkg }
        }
        if (exactAliasMatch != null) return Built.Value(exactAliasMatch)

        // A partial Polish alias is useful when the installed launcher label is external data
        // (for example, Chinese or Russian). Keep every installed target so a short request
        // remains ambiguous when it can mean more than one app.
        val aliasMatches = APP_ALIASES.entries
            .asSequence()
            .filter { (alias, _) -> normalizedLauncherName(alias).contains(needle) }
            .flatMap { (_, packages) -> packages.asSequence() }
            .mapNotNull { pkg -> apps.firstOrNull { it.second == pkg } }
            .distinctBy { it.second }
            .toList()
        if (aliasMatches.size == 1) return Built.Value(aliasMatches.single())

        val labelExact = apps.filter { normalizedLauncherName(it.first) == needle }
        val labelMatches = labelExact.ifEmpty {
            apps.filter { normalizedLauncherName(it.first).contains(needle) }
        }
        val matches = (aliasMatches + labelMatches).distinctBy { it.second }
        return when {
            matches.isEmpty() -> Built.Error("nie znaleziono aplikacji: $name")
            matches.size > 1 -> Built.Error(
                "kilka dopasowań: ${matches.take(5).joinToString(", ") { it.first }}. Doprecyzuj nazwę")
            else -> Built.Value(matches.single())
        }
    }

    private fun normalizedLauncherName(value: String): String = buildString {
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .lowercase(Locale.ROOT)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .forEach { append(CYRILLIC_LATIN[it] ?: it) }
    }

    private suspend fun launchAppTool(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"nie podano nazwy aplikacji"}"""
        val (label, pkg) = when (val r = resolveLauncherApp(name)) {
            is Built.Error -> return JSONObject().put("error", r.message).toString()
            is Built.Value -> r.value
        }
        val result = actionDispatcher.dispatch(
            ActionDef(command = "", displayName = "Uruchomienie $label", kind = "app_launch",
                payload = JSONObject().put("packageName", pkg).toString()), data = null)
        return if (result.success) JSONObject().put("ok", true).put("app", label).toString()
        else JSONObject().put("error", result.reason ?: "nie udało się uruchomić $label").toString()
    }

    // --- cluster projection ---

    // Drive the projection through the SAME ActionDispatcher path as a "cluster_projection"
    // automation action. The dispatcher already applies the mode and polls it back, so the
    // verdict in DispatchResult is the honest one and the voice turn waits for exactly one
    // poll (5 s budget) instead of a second 15 s poll of its own.
    private suspend fun setClusterProjection(args: JSONObject): String {
        if (!args.has("on")) return """{"error":"nie podano, czy włączyć czy wyłączyć projekcję"}"""
        val on = args.optBoolean("on")
        val want = if (on) ClusterMode.FULLSCREEN else ClusterMode.OFF
        // Treat an unreadable state as OFF: for on=true we then still actuate (worst case a
        // no-op inside the manager's mutex), never lie that the projection is already up.
        val before = runCatchingCancellable { clusterVoiceControl.projectionMode() }
            .getOrDefault(ClusterMode.OFF)
        val label = runCatchingCancellable { clusterVoiceControl.projectedAppLabel() }
            .getOrNull() ?: "Nawigator"
        if (before == want) return JSONObject().put("ok", true)
            .put("note", if (on) "$label jest już na zestawie wskaźników" else "projekcji nie ma już na zestawie wskaźników")
            .toString()
        val result = actionDispatcher.dispatch(
            ActionDef(command = "cluster_projection", displayName = "Wyświetlenie na zestawie wskaźników",
                kind = "cluster_projection", payload = if (on) "1" else "0"), data = null)
        return when {
            result.success -> JSONObject().put("ok", true).put("app", label).toString()
            // The daemon restart is a "try again in a minute", not a failed command.
            on && result.daemonRestarting -> JSONObject().put("ok", false)
                .put("note", "proces usługi uruchamia się ponownie, spróbuj ponownie za minutę").toString()
            on -> JSONObject().put("error",
                "projekcja nie została włączona. Spróbuj ponownie za kilka sekund").toString()
            else -> JSONObject().put("error", "nie udało się usunąć projekcji z zestawu wskaźników").toString()
        }
    }

    // Immediate sentry toggle: routes through the SAME ActionDispatcher path as a "sentry"
    // automation action (dispatchSentry -> helper.putGlobalSetting), so the write is honest —
    // DispatchResult carries the real success/failure, unlike a fire-and-forget projection.
    private suspend fun setSentry(args: JSONObject): String {
        val on = requireBoolArg(args, "on")
            ?: return """{"error":"nie podano, czy włączyć czy wyłączyć ochronę"}"""
        val action = ActionDef(command = "sentry",
            displayName = if (on) "Włącz ochronę" else "Wyłącz ochronę",
            kind = "sentry", payload = if (on) "1" else "0")
        if (ActionDispatcher.isDangerousAction(action)) {
            return confirmDangerous(action, action.displayName)
        }
        val result = actionDispatcher.dispatch(action, data = null)
        return dispatchJson(result)
    }

    // Immediate Wi-Fi hotspot toggle: same ActionDispatcher path as a "hotspot" automation
    // action, so the reported result is the real one.
    private suspend fun setHotspot(args: JSONObject): String {
        val on = requireBoolArg(args, "on")
            ?: return """{"error":"nie podano, czy włączyć czy wyłączyć punkt dostępu"}"""
        val action = ActionDef(command = "hotspot",
            displayName = if (on) "Włącz punkt dostępu" else "Wyłącz punkt dostępu",
            kind = "hotspot", payload = if (on) "1" else "0")
        if (ActionDispatcher.isDangerousAction(action)) {
            return confirmDangerous(action, action.displayName)
        }
        val result = actionDispatcher.dispatch(action, data = null)
        return dispatchJson(result)
    }

    // Immediate screen backlight off/on (#275): same ActionDispatcher path as a "screen"
    // automation action, so the reported result is the real one.
    private suspend fun setScreen(args: JSONObject): String {
        val on = requireBoolArg(args, "on")
            ?: return """{"error":"nie podano, czy włączyć czy wyłączyć ekran"}"""
        val action = ActionDef(command = "screen",
            displayName = if (on) "Włącz ekran" else "Wyłącz ekran",
            kind = "screen", payload = if (on) "1" else "0")
        val result = actionDispatcher.dispatch(action, data = null)
        return dispatchJson(result)
    }

    // Accepts a JSON boolean or the strings "true"/"false" (some LLMs emit stringified
    // booleans); anything else (missing key, null, number) is treated as unspecified so a
    // malformed call can never silently disable a rule.
    private fun requireEnabledArg(args: JSONObject): Boolean? {
        if (!args.has("enabled") || args.isNull("enabled")) return null
        return when (val raw = args.get("enabled")) {
            is Boolean -> raw
            is String -> when (raw.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    // Same acceptance rule as requireEnabledArg, generalized to an arbitrary key (used by
    // create_automation's sentry action "on" field).
    private fun requireBoolArg(args: JSONObject, key: String): Boolean? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val raw = args.get(key)) {
            is Boolean -> raw
            is String -> when (raw.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    // --- split_screen ---

    private suspend fun splitScreen(args: JSONObject): String {
        if (!splitPrefs.isFeatureEnabled()) {
            return """{"error":"podział ekranu jest wyłączony w ustawieniach"}"""
        }
        val mgr = splitMgr
            ?: return """{"error":"podział ekranu jest niedostępny"}"""
        return when (val action = args.optString("action").trim()) {
            "start" -> {
                val narrowName = args.optString("narrow_app").trim().takeIf { it.isNotEmpty() }
                val wideName = args.optString("wide_app").trim().takeIf { it.isNotEmpty() }
                when {
                    narrowName == null && wideName == null -> {
                        // No apps specified — restore the last saved pair.
                        val result = mgr.startLastPair()
                            ?: return """{"error":"brak zapisanej pary aplikacji do przywrócenia"}"""
                        splitStartResultJson(result)
                    }
                    narrowName != null && wideName != null -> {
                        val (_, narrowPkg) = when (val r = resolveLauncherApp(narrowName)) {
                            is Built.Error -> return JSONObject().put("error", r.message).toString()
                            is Built.Value -> r.value
                        }
                        val (_, widePkg) = when (val r = resolveLauncherApp(wideName)) {
                            is Built.Error -> return JSONObject().put("error", r.message).toString()
                            is Built.Value -> r.value
                        }
                        if (narrowPkg == widePkg) {
                            return """{"error":"obie aplikacje są takie same, wybierz różne"}"""
                        }
                        val side = parseSplitSide(args.optString("side"))
                        splitStartResultJson(mgr.start(SplitPair(narrowPkg, widePkg, side)))
                    }
                    else -> """{"error":"podaj obie aplikacje (narrow_app i wide_app) albo żadnej"}"""
                }
            }
            "mirror" -> {
                if (mgr.state.value is SplitSessionState.Idle) {
                    return """{"error":"podział ekranu nie jest uruchomiony"}"""
                }
                // mirror() is a silent no-op in half a dozen states (pane on the cluster,
                // move in progress): report the reason instead of an empty "done".
                mgr.mirror()?.let { return JSONObject().put("error", it).toString() }
                OK
            }
            "swap" -> {
                if (mgr.state.value is SplitSessionState.Idle) {
                    return """{"error":"podział ekranu nie jest uruchomiony"}"""
                }
                mgr.swapApps()
                OK
            }
            "change" -> {
                val active = mgr.state.value as? SplitSessionState.Active
                    ?: return """{"error":"podział ekranu nie jest uruchomiony"}"""
                val sideStr = args.optString("side").trim()
                if (sideStr.isEmpty()) {
                    return """{"error":"nie podano strony (pole side: left albo right)"}"""
                }
                val appName = args.optString("app").trim()
                if (appName.isEmpty()) {
                    return """{"error":"nie podano aplikacji (pole app)"}"""
                }
                val (_, newPkg) = when (val r = resolveLauncherApp(appName)) {
                    is Built.Error -> return JSONObject().put("error", r.message).toString()
                    is Built.Value -> r.value
                }
                val sideEnum = parseSplitSide(sideStr)
                // Determine which Pane corresponds to the requested screen side.
                val pane = if (sideEnum == active.pair.narrowSide) Pane.NARROW else Pane.WIDE
                // Reject if the replacement would make both panes show the same app.
                val otherPkg = if (pane == Pane.NARROW) active.pair.widePkg else active.pair.narrowPkg
                if (newPkg == otherPkg) {
                    return """{"error":"obie aplikacje są takie same, wybierz różne"}"""
                }
                splitStartResultJson(mgr.changeApp(pane, newPkg))
            }
            "exit" -> {
                if (mgr.state.value is SplitSessionState.Idle) {
                    return """{"error":"podział ekranu nie jest uruchomiony"}"""
                }
                mgr.exit()
                OK
            }
            else -> JSONObject().put("error", "nieznane działanie split_screen: $action").toString()
        }
    }

    /** Converts a side string ("left" / anything else → right) to [SplitSide]. */
    private fun parseSplitSide(s: String): SplitSide =
        if (s.trim().lowercase() == "left") SplitSide.LEFT else SplitSide.RIGHT

    /** Maps [SplitStartResult] to a terse tool-result JSON string. */
    private fun splitStartResultJson(result: SplitStartResult): String = when (result) {
        SplitStartResult.OK -> OK
        // #139: a reboot never enables freeform on firmwares that ignore the flag.
        SplitStartResult.FREEFORM_UNAVAILABLE ->
            if (splitMgr?.freeformUnsupported() == true) {
                """{"error":"podział ekranu jest niedostępny w oprogramowaniu tego samochodu"}"""
            } else {
                """{"error":"podział ekranu będzie dostępny po ponownym uruchomieniu samochodu"}"""
            }
        SplitStartResult.LAUNCH_FAILED ->
            """{"error":"nie udało się uruchomić aplikacji"}"""
        SplitStartResult.DISABLED ->
            """{"error":"podział ekranu jest wyłączony w ustawieniach"}"""
    }

    private suspend fun setAutomationEnabled(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"nie podano nazwy"}"""
        val enabled = requireEnabledArg(args) ?: return """{"error":"nie podano wartości enabled"}"""
        val matches = runCatchingCancellable { ruleDao.getAllList() }
            .getOrElse { return """{"error":"nie udało się zmienić automatyzacji"}""" }
            .filter { it.name.trim().equals(name, ignoreCase = true) }
        return when {
            matches.isEmpty() -> JSONObject().put("error", "nie znaleziono automatyzacji: $name").toString()
            matches.size > 1 ->
                JSONObject().put("error", "kilka automatyzacji ma nazwę $name, zmień nazwę").toString()
            else -> {
                runCatchingCancellable { ruleDao.setEnabled(matches.first().id, enabled) }
                    .getOrElse { return """{"error":"nie udało się zmienić automatyzacji"}""" }
                JSONObject().put("ok", true).put("name", name).put("enabled", enabled).toString()
            }
        }
    }

    // --- create_automation ---

    /** Result of parsing one trigger/action draft: either a built value or a fixed validation error. */
    private sealed class Built<out T> {
        data class Value<T>(val value: T) : Built<T>()
        data class Error(val message: String) : Built<Nothing>()
    }

    private suspend fun createAutomation(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return """{"error":"nie podano nazwy"}"""
        val triggerArg = args.optJSONObject("trigger")
            ?: return """{"error":"nie podano wyzwalacza"}"""
        val actionsArg = args.optJSONArray("actions")
        if (actionsArg == null || actionsArg.length() == 0) return """{"error":"nie podano działań"}"""

        val existing = runCatchingCancellable { ruleDao.getAllList() }
            .getOrElse { return """{"error":"nie udało się utworzyć automatyzacji"}""" }
        if (existing.size >= MAX_AUTOMATIONS) return """{"error":"osiągnięto limit 50 automatyzacji"}"""
        if (existing.any { it.name.trim().equals(name, ignoreCase = true) }) {
            return JSONObject().put("error", "automatyzacja o nazwie «$name» już istnieje").toString()
        }

        val trigger = when (val r = buildTrigger(triggerArg)) {
            is Built.Error -> return JSONObject().put("error", r.message).toString()
            is Built.Value -> r.value
        }

        // Validate voice-phrase collisions (the user's phrases for built-in commands, duplicates
        // across rules). editingId=0L because this is a new rule with no persisted id yet.
        RuleDraftValidator.validateTriggers(
            listOf(trigger), editingId = 0L, existingRules = existing, userCommandPhrases = userPhrases.owners(),
        )?.let { return JSONObject().put("error", triggerErrorText(it)).toString() }

        val actions = mutableListOf<ActionDef>()
        for (i in 0 until actionsArg.length()) {
            val obj = actionsArg.optJSONObject(i)
                ?: return """{"error":"nieprawidłowy opis działania"}"""
            when (val r = buildAction(obj)) {
                is Built.Error -> return JSONObject().put("error", r.message).toString()
                is Built.Value -> actions += r.value
            }
        }
        RuleDraftValidator.validateActions(actions)?.let { return actionErrorJson(it) }

        val cooldown = args.optInt("cooldown_seconds", 60).coerceAtLeast(1)
        val playSound = args.optBoolean("play_sound", false)
        val rule = RuleEntity(
            name = name,
            enabled = true,
            triggers = TriggerDef.listToJson(listOf(trigger)),
            actions = ActionDef.listToJson(actions),
            cooldownSeconds = cooldown,
            playSound = playSound,
            confirmBeforeExecute = actions.any { ActionDispatcher.isDangerousAction(it) },
        )
        runCatchingCancellable { RuleInserts.insertWithinLimit(ruleDao, rule, MAX_AUTOMATIONS) }
            .getOrElse { return """{"error":"nie udało się utworzyć automatyzacji"}""" }
            ?: return """{"error":"osiągnięto limit 50 automatyzacji"}"""
        // One compact Polish line the model is told to read back: the driver hears exactly
        // what was created and catches a wrong threshold or action on the spot.
        return JSONObject()
            .put("ok", true)
            .put("name", name)
            .put("rule", ruleSummary(name, trigger, actions))
            .put("hint", "powiedz „wyłącz automatyzację $name”, aby ją wyłączyć")
            .toString()
    }

    /** One spoken line describing the rule that was just created, so the model can repeat it
     *  back instead of an unverifiable "done". Both display names are already Polish. */
    private fun ruleSummary(name: String, trigger: TriggerDef, actions: List<ActionDef>): String =
        "«$name»: jeśli ${trigger.displayName}, to " + actions.joinToString(", ") { it.displayName }

    /** Enum trigger params persist a numeric code; the agent may pass the code ("4") or the
     *  human label it saw in get_vehicle_state ("D"/"d"). Returns the code, or null when the
     *  raw value matches neither (caller rejects with [enumValuesHint]). */
    private fun resolveEnumCode(option: TriggerParamOption, raw: String): String? {
        val enums = option.enumValues ?: return raw
        enums.firstOrNull { it.first == raw }?.let { return it.first }
        return enums.firstOrNull {
            option.localizedEnumLabel(it.first, context).equals(raw, ignoreCase = true)
        }?.first
    }

    /** "label (code)" list of one enum param's valid values, for the rejection message. */
    private fun enumValuesHint(option: TriggerParamOption): String =
        option.enumValues.orEmpty().joinToString(", ") {
            "${option.localizedEnumLabel(it.first, context)} (${it.first})"
        }

    /** Compact "Param:label(code)/..." guide across every enum trigger param, injected into
     *  the create_automation value-field description so the LLM emits codes, not raw labels. */
    private fun enumValueGuide(): String =
        TRIGGER_PARAMS.filter { it.enumValues != null }.joinToString("; ") { p ->
            p.param + ":" + p.enumValues!!.joinToString("/") {
                "${p.localizedEnumLabel(it.first, context)}(${it.first})"
            }
        }

    private suspend fun buildTrigger(t: JSONObject): Built<TriggerDef> {
        val kind = t.optString("kind")
        return when (kind) {
            "param" -> {
                val paramArg = t.optString("param").trim()
                if (paramArg.equals(PowerStateRuleMigration.PARAM, ignoreCase = true)) return powerStateTrigger(t)
                val option = TRIGGER_PARAMS.firstOrNull { it.param.equals(paramArg, ignoreCase = true) }
                    ?: return Built.Error("nieznany parametr wyzwalacza: $paramArg")
                val operator = t.optString("operator").trim()
                if (operator !in OPERATORS) return Built.Error("niedozwolony operator: $operator")
                val rawValue = t.optString("value").trim()
                if (rawValue.isEmpty()) return Built.Error("nie podano wartości wyzwalacza")
                // Enum params persist a numeric code ("4"=Drive), but the LLM naturally writes
                // the human label it saw in get_vehicle_state ("D"). The engine parses
                // value.toDoubleOrNull(), so a raw label silently makes the predicate false
                // forever (visible + enabled rule that never fires). Normalize label -> code;
                // reject an unknown enum value loudly instead of persisting a dead rule.
                // Numeric params pass through unchanged.
                val value = if (option.enumValues != null) {
                    resolveEnumCode(option, rawValue)
                        ?: return Built.Error(
                            "niedozwolona wartość «$rawValue» dla «${option.param}»; dozwolone: ${enumValuesHint(option)}")
                } else rawValue
                val displayValue = option.localizedEnumLabel(value, context)
                Built.Value(TriggerDef(
                    param = option.param,
                    chineseName = option.chineseName,
                    operator = operator,
                    value = value,
                    displayName = "${option.param} $operator $displayValue",
                ))
            }
            "place_enter", "place_exit" -> {
                val placeName = t.optString("place_name").trim()
                if (placeName.isEmpty()) return Built.Error("nie podano nazwy miejsca")
                val places = runCatchingCancellable { placeRepository.getAllSnapshot() }
                    .getOrElse { return Built.Error("nie udało się utworzyć automatyzacji") }
                val matches = places.filter { it.name.trim().equals(placeName, ignoreCase = true) }
                when {
                    matches.isEmpty() -> Built.Error("nie znaleziono miejsca: $placeName")
                    matches.size > 1 -> Built.Error("kilka miejsc ma nazwę $placeName, zmień nazwę")
                    else -> {
                        val place = matches.first()
                        val verb = if (kind == "place_enter") "Wjazd do" else "Wyjazd z"
                        Built.Value(TriggerDef(
                            param = "Place", chineseName = "位置", operator = "==", value = "enter",
                            displayName = "$verb «${place.name}»",
                            kind = kind, placeId = place.id, placeName = place.name,
                        ))
                    }
                }
            }
            "time_of_day" -> {
                val value = t.optString("value").trim().lowercase()
                if (value !in setOf("dawn", "day", "dusk", "night")) {
                    return Built.Error("niedozwolona pora dnia: $value")
                }
                Built.Value(TriggerDef(
                    param = "TimeOfDay", chineseName = "时间段", operator = "==",
                    value = value.uppercase(), displayName = value, kind = "time_of_day",
                ))
            }
            "time_range" -> {
                val raw = t.optString("value").trim()
                val parts = raw.split("-")
                val from = parts.getOrNull(0)?.let { hhmmToMinute(it.trim()) }
                val to = parts.getOrNull(1)?.let { hhmmToMinute(it.trim()) }
                if (parts.size != 2 || from == null || to == null) {
                    return Built.Error("nieprawidłowy format czasu, oczekiwano HH:MM-HH:MM")
                }
                val days = mutableSetOf<Int>()
                t.optJSONArray("weekdays")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val day = arr.optInt(i, -1)
                        if (day !in 1..7) return Built.Error("dzień tygodnia musi być liczbą 1-7 (1=poniedziałek, 7=niedziela)")
                        days += day
                    }
                }
                val spec = ScheduleSpec(from, to, days)
                Built.Value(TriggerDef(
                    param = "Schedule", chineseName = "时间表", operator = "==",
                    value = spec.toJson(), displayName = raw, kind = "time_range",
                ))
            }
            "service_start" -> Built.Value(TriggerDef(
                param = "ServiceStart", chineseName = "服务启动", operator = "==", value = "true",
                displayName = "Uruchomienie aplikacji", kind = "service_start",
            ))
            "network_available" -> Built.Value(TriggerDef(
                param = "NetworkAvailable", chineseName = "网络可用", operator = "==", value = "true",
                displayName = "Sieć jest dostępna", kind = "network_available",
            ))
            // Mirrors newVoiceTrigger in AutomationScreen.kt: phrase is stored as both value
            // and displayName; collision validation (builtin / taken) runs in createAutomation.
            "voice" -> {
                val phrase = t.optString("phrase").trim()
                if (phrase.isEmpty()) return Built.Error("nie podano frazy głosowej (pole phrase)")
                Built.Value(TriggerDef(
                    param = "Voice", chineseName = "语音", operator = "==",
                    value = phrase, displayName = phrase, kind = "voice",
                ))
            }
            // Mirrors newButtonPressTrigger in AutomationViewModel.kt: value is button number
            // as string; displayName is "Przycisk N".
            "button_press" -> {
                val button = t.optInt("button", -1)
                if (button !in 1..4) return Built.Error("numer przycisku widżetu musi być od 1 do 4 (pole button)")
                Built.Value(TriggerDef(
                    param = "button", chineseName = "", operator = "==",
                    value = button.toString(), displayName = "Przycisk $button", kind = "button_press",
                ))
            }
            else -> Built.Error("niedozwolony typ wyzwalacza: $kind")
        }
    }

    /** The removed PowerState condition, converted the way a saved rule is: ON and DRIVE are the
     *  app start, anything else could fire only with the car off and is refused. */
    private fun powerStateTrigger(t: JSONObject): Built<TriggerDef> {
        val value = when (val raw = t.optString("value").trim().uppercase()) {
            "ON" -> "1"
            "DRIVE" -> "2"
            "OFF" -> "0"
            else -> raw
        }
        val legacy = TriggerDef(
            param = PowerStateRuleMigration.PARAM, chineseName = "", operator = t.optString("operator").trim(),
            value = value, displayName = "",
        )
        val converted = PowerStateRuleMigration.convert("AND", listOf(legacy), AGENT_POWER_LABELS, driveIsGear = false)
            ?: return Built.Error(
                "brak warunku PowerState: reguła nie zadziała przy wyłączonym samochodzie. " +
                    "Przy uruchomieniu samochodu: kind=service_start; podczas jazdy: param=Gear, value=4")
        return Built.Value(converted.single())
    }

    private suspend fun buildAction(a: JSONObject): Built<ActionDef> {
        val kind = a.optString("kind")
        return when (kind) {
            "param" -> {
                val id = a.optString("command_id").trim()
                val cmd = AgentCommandCatalog.ALL.firstOrNull { it.id == id }
                    ?: return Built.Error("nieznane polecenie: $id")
                val value = if (a.has("value") && !a.isNull("value")) a.optInt("value") else null
                val chinese = AgentCommandCatalog.resolve(id, value)
                    ?: return Built.Error("nieprawidłowa wartość dla polecenia $id")
                Built.Value(ActionDef(command = chinese, displayName = cmd.label, kind = "param"))
            }
            "delay" -> {
                val hasMs = a.has("ms") && !a.isNull("ms")
                val ms = if (hasMs) a.optInt("ms", -1) else -1
                if (!hasMs || ms !in 0..MAX_DELAY_MS) {
                    return Built.Error("nie podano czasu pauzy (ms, 0..$MAX_DELAY_MS)")
                }
                Built.Value(ActionDef(command = "delay", displayName = "Pauza $ms ms",
                    kind = "delay", payload = ms.toString()))
            }
            "media_volume" -> {
                val op = a.optString("op").trim()
                Built.Value(ActionDef(command = "media_volume", displayName = "Głośność $op",
                    kind = "media_volume", payload = op))
            }
            // Accept legacy kind names and normalize all three to the canonical "notification".
            "notification", "notification_sound", "notification_silent" -> {
                val title = a.optString("title").trim()
                val text = a.optString("text").trim()
                val payload = JSONObject().put("title", title).put("text", text).toString()
                Built.Value(ActionDef(command = "notification", displayName = title.ifBlank { "Powiadomienie" },
                    kind = "notification", payload = payload))
            }
            "call" -> {
                val phone = a.optString("phone").trim()
                // displayName has no phone number: list_automations serializes it straight back
                // to the LLM (the payload, which does carry the phone for the dispatcher, never is).
                Built.Value(ActionDef(command = "call", displayName = "Połączenie", kind = "call",
                    payload = JSONObject().put("phone", phone).put("autoDial", true).toString()))
            }
            "navigate" -> {
                val hasLat = a.has("lat") && !a.isNull("lat")
                val hasLon = a.has("lon") && !a.isNull("lon")
                val lat = if (hasLat) a.optDouble("lat", Double.NaN) else Double.NaN
                val lon = if (hasLon) a.optDouble("lon", Double.NaN) else Double.NaN
                if (!hasLat || !hasLon || !lat.isFinite() || !lon.isFinite()) {
                    return Built.Error("nie podano współrzędnych lat/lon")
                }
                Built.Value(ActionDef(command = "navigate", displayName = "Trasa", kind = "navigate",
                    payload = JSONObject().put("lat", lat).put("lon", lon)
                        .put("go", a.optBoolean("go", false)).toString()))
            }
            "url" -> {
                val url = a.optString("url").trim()
                Built.Value(ActionDef(command = "url", displayName = url.ifBlank { "Łącze" }, kind = "url",
                    payload = JSONObject().put("url", url).toString()))
            }
            "yandex_music" -> {
                val mode = a.optString("mode").trim()
                if (mode.isEmpty()) return Built.Error("nie podano trybu Yandex Muzyki")
                Built.Value(ActionDef(command = "yandex_music", displayName = "Yandex Muzyka",
                    kind = "yandex_music", payload = JSONObject().put("mode", mode).toString()))
            }
            "youtube" -> {
                val query = a.optString("query").trim()
                if (query.isEmpty()) return Built.Error("nie podano, co włączyć na YouTube (pole query)")
                val mode = a.optString("mode").trim().takeIf { it == "search" } ?: "play"
                Built.Value(ActionDef(command = "", displayName = "YouTube: $query", kind = "youtube",
                    payload = JSONObject().put("mode", mode).put("query", query).toString()))
            }
            "go_home" -> Built.Value(
                ActionDef(command = "", displayName = "Strona główna", kind = "go_home"))
            "sentry" -> {
                val on = requireBoolArg(a, "on") ?: return Built.Error("nie podano stanu ochrony")
                Built.Value(ActionDef(command = "sentry",
                    displayName = if (on) "Włącz ochronę" else "Wyłącz ochronę",
                    kind = "sentry", payload = if (on) "1" else "0"))
            }
            "hotspot" -> {
                val on = requireBoolArg(a, "on") ?: return Built.Error("dla hotspot podaj on: true albo false")
                Built.Value(ActionDef(command = "hotspot",
                    displayName = if (on) "Włącz punkt dostępu" else "Wyłącz punkt dostępu",
                    kind = "hotspot", payload = if (on) "1" else "0"))
            }
            "screen" -> {
                val on = requireBoolArg(a, "on") ?: return Built.Error("dla screen podaj on: true albo false")
                Built.Value(ActionDef(command = "screen",
                    displayName = if (on) "Włącz ekran" else "Wyłącz ekran",
                    kind = "screen", payload = if (on) "1" else "0"))
            }
            "app_launch", "app_close" -> {
                val name = a.optString("app").trim()
                if (name.isEmpty()) return Built.Error("nie podano aplikacji (pole app)")
                val (label, pkg) = when (val r = resolveLauncherApp(name)) {
                    is Built.Error -> return r
                    is Built.Value -> r.value
                }
                val payload = JSONObject().put("packageName", pkg).put("appLabel", label)
                Built.Value(if (kind == "app_launch") ActionDef(
                    command = "", displayName = "Uruchomienie $label", kind = "app_launch",
                    payload = payload.put("minimize", false).toString(),
                ) else ActionDef(command = "", displayName = "Zamknij $label", kind = "app_close",
                    payload = payload.toString()))
            }
            "media_key" -> {
                val key = a.optString("key").trim()
                if (ActionDispatcher.mediaKeyCode(key) == null) return Built.Error("dla media_key podaj key: play albo pause")
                Built.Value(ActionDef(command = "", displayName = if (key == "play") "Multimedia: odtwarzaj" else "Multimedia: pauza",
                    kind = "media_key", payload = key))
            }
            "cluster_projection" -> {
                val on = requireBoolArg(a, "on")
                    ?: return Built.Error("nie podano stanu projekcji na zestaw wskaźników")
                Built.Value(ActionDef(command = "cluster_projection", displayName = "Wyświetlenie na zestawie wskaźników",
                    kind = "cluster_projection", payload = if (on) "1" else "0"))
            }
            "speak" -> {
                val text = a.optString("text").trim()
                if (text.isEmpty()) return Built.Error("nie podano tekstu do odczytania")
                Built.Value(ActionDef(command = "", displayName = "Odczytaj tekst", kind = "speak",
                    payload = JSONObject().put("text", text).toString()))
            }
            "agent_query" -> {
                val prompt = a.optString("prompt").trim()
                if (prompt.isEmpty()) return Built.Error("nie podano zapytania do agenta")
                Built.Value(ActionDef(command = "", displayName = "Zapytanie do agenta", kind = "agent_query",
                    payload = JSONObject().put("prompt", prompt).toString()))
            }
            "split_screen" -> {
                val narrowName = a.optString("narrow_app").trim()
                if (narrowName.isEmpty()) return Built.Error("nie podano aplikacji 1/3 (pole narrow_app)")
                val wideName = a.optString("wide_app").trim()
                if (wideName.isEmpty()) return Built.Error("nie podano aplikacji 2/3 (pole wide_app)")
                val (narrowLabel, narrowPkg) = when (val r = resolveLauncherApp(narrowName)) {
                    is Built.Error -> return r
                    is Built.Value -> r.value
                }
                val (wideLabel, widePkg) = when (val r = resolveLauncherApp(wideName)) {
                    is Built.Error -> return r
                    is Built.Value -> r.value
                }
                val side = if (a.optString("side").trim().lowercase() == "left") "left" else "right"
                Built.Value(ActionDef(
                    command = "", displayName = "Podział ekranu: $narrowLabel + $wideLabel",
                    kind = "split_screen",
                    payload = JSONObject().put("narrow", narrowPkg).put("wide", widePkg)
                        .put("side", side).toString(),
                ))
            }
            // Both target the running session, so they carry no payload (mirrors
            // newSplitScreenCloseAction/newSplitScreenToggleAction in AutomationViewModel).
            "split_screen_close" -> Built.Value(ActionDef(
                command = "", displayName = "Zamknij podzielony ekran",
                kind = "split_screen_close", payload = null,
            ))
            "split_screen_toggle" -> Built.Value(ActionDef(
                command = "", displayName = "Przełącz podzielony ekran",
                kind = "split_screen_toggle", payload = null,
            ))
            else -> Built.Error("niedozwolony typ działania: $kind")
        }
    }

    private fun triggerErrorText(err: TriggerValidationError): String = when (err) {
        is TriggerValidationError.ValueNotNumber -> "wartość dla «${err.param}» musi być liczbą"
        TriggerValidationError.SteeringKeyUnassigned -> "nie przypisano przycisku kierownicy"
        TriggerValidationError.OneShotInvalid,
        TriggerValidationError.OneShotWithOr,
        TriggerValidationError.OneShotWithEvent,
        TriggerValidationError.OneShotTwice -> "to narzędzie nie tworzy jednorazowej reguły według daty"
        TriggerValidationError.VoicePhraseEmpty -> "nie podano frazy głosowej"
        is TriggerValidationError.VoicePhraseBuiltin ->
            "tej frazy używa już wbudowane polecenie «${err.command}», wybierz inną"
        is TriggerValidationError.VoicePhraseTaken -> "tej frazy używa już automatyzacja «${err.rule}»"
    }

    private fun actionErrorJson(err: ActionValidationError): String {
        val msg = when (err) {
            is ActionValidationError.CommandMissing -> "nie podano polecenia (działanie ${err.index})"
            is ActionValidationError.NotifTitleEmpty -> "nie podano tytułu powiadomienia (działanie ${err.index})"
            is ActionValidationError.AppNotSelected -> "nie podano aplikacji (działanie ${err.index})"
            is ActionValidationError.PhoneInvalid -> "nieprawidłowy numer telefonu (działanie ${err.index})"
            is ActionValidationError.NavDestMissing -> "nie podano celu trasy (działanie ${err.index})"
            is ActionValidationError.UrlEmpty -> "nie podano łącza (działanie ${err.index})"
            is ActionValidationError.UrlNoScheme ->
                "łącze musi zaczynać się od schematu, na przykład https:// (działanie ${err.index})"
            is ActionValidationError.YandexMusicModeMissing -> "nie podano trybu Yandex Muzyki (działanie ${err.index})"
            is ActionValidationError.MediaVolumeMissing -> "nie podano poziomu głośności (działanie ${err.index})"
            is ActionValidationError.SentryInvalid ->
                "nieprawidłowy stan ochrony (działanie ${err.index})"
            is ActionValidationError.HotspotInvalid ->
                "nieprawidłowy stan punktu dostępu (działanie ${err.index})"
            is ActionValidationError.ScreenInvalid ->
                "nieprawidłowy stan ekranu (działanie ${err.index})"
            is ActionValidationError.MediaKeyInvalid ->
                "dla media_key potrzebny jest key: play albo pause (działanie ${err.index})"
            is ActionValidationError.SpeakTextEmpty ->
                "nie podano tekstu do odczytania (działanie ${err.index})"
            is ActionValidationError.AgentQueryPromptEmpty ->
                "nie podano zapytania do agenta (działanie ${err.index})"
            is ActionValidationError.SplitScreenNarrowEmpty ->
                "nie wybrano aplikacji 1/3 (działanie ${err.index})"
            is ActionValidationError.SplitScreenWideEmpty ->
                "nie wybrano aplikacji 2/3 (działanie ${err.index})"
            is ActionValidationError.SplitScreenSamePackage ->
                "obie aplikacje muszą być różne (działanie ${err.index})"
            is ActionValidationError.SplitScreenInvalidSide ->
                "nieprawidłowa strona split_screen (działanie ${err.index})"
            is ActionValidationError.TelegramReportEmpty ->
                "w raporcie Telegram nie wybrano żadnej pozycji (działanie ${err.index})"
        }
        return JSONObject().put("error", msg).toString()
    }

    // Only a local safety-gate reason explicitly marked by ActionDispatcher may reach the LLM.
    // Unmarked reasons can contain raw exception data and must use the generic reply.
    private fun dispatchJson(result: DispatchResult): String {
        if (result.success) return OK
        val safeReason = result.reason?.takeIf { result.trustedLocalizedReason }
        return JSONObject().put("error", safeReason ?: "nie wykonano").toString()
    }

    // --- helpers ---

    private fun periodParam(): JSONObject = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray(listOf("day", "week", "month")))
        .put("description", "Okres od bieżącej chwili: day = 24 godziny, week = 7 dni, month = 30 dni. " +
            "Dla konkretnego miesiąca lub dat użyj from/to zamiast period")


    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", JSONArray(required))
            })
        })
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    companion object {
        /** Tools withheld from automation-origin sessions. run_automation reaches fireVoiceRule
         *  (which bypasses cooldown by design), so a rule whose agent_query action calls it could
         *  recurse into itself with no engine-level brake; the memory tools are withheld because
         *  such a session talks to a rule, not to the driver, and must not rewrite driver facts. */
        internal val AUTOMATION_TOOLS = setOf(
            "run_automation", "create_automation", "set_automation_enabled",
            "remember_fact", "forget_fact")

        private const val DAY_MS = 24L * 3600_000
        private const val PERIOD_ERROR =
            """{"error":"podaj period (day/week/month) lub daty from i to w formacie RRRR-MM-DD"}"""
        private const val OK = """{"ok":true}"""
        private const val SEARCH_ERROR = """{"error":"wyszukiwanie jest niedostępne"}"""
        private const val MAX_AUTOMATIONS = 50
        private const val MAX_DELAY_MS = 60_000
        // The display names buildTrigger gives a service_start and a Gear == 4 trigger.
        private val AGENT_POWER_LABELS = PowerStateRuleMigration.Labels("Uruchomienie aplikacji", "Gear == D")
        private const val MAX_PLACES = 50
        private const val BAD_ARGS_ERROR = """{"error":"nieprawidłowe argumenty"}"""
        private const val INTERNAL_ERROR = """{"error":"błąd wewnętrzny narzędzia"}"""
        // Straight-line distance underestimates real road distance; this fudge factor
        // brings the estimate closer to typical highway/road routing.
        private const val ROAD_FACTOR = 1.25

        private const val TAG = "AgentTools"
        // where_am_i: villages within 15 km, towns within 50 km, at most five answers.
        private const val SETTLEMENT_RADIUS_M = 15_000
        private const val TOWN_RADIUS_M = 50_000
        private const val MAX_SETTLEMENTS = 5
        // where_am_i: a fix older than this gets fix_note so the model hedges.
        private const val FRESH_FIX_MS = 120_000L
        // find_chargers: the default radius around the car and the cap on the asked one.
        private const val CHARGER_RADIUS_KM = 30
        private const val MAX_CHARGER_RADIUS_KM = 100
        // find_chargers: occupancy imported longer ago than this is not "free now".
        private const val STALE_STATUS_MIN = 60L
        private val SETTLEMENT_TYPES = mapOf(
            "city" to "miasto", "town" to "miasto", "village" to "wieś lub osada",
            "hamlet" to "wieś", "suburb" to "dzielnica miasta", "isolated_dwelling" to "przysiółek",
        )
        private val COMPASS_RU = listOf(
            "północ", "północno-wschód", "wschód", "południowo-wschód", "południe", "południowo-zachód", "zachód", "północno-zachód",
        )

        // Any wheel below this is clearly deflated (Leopard 3 cold placard ~250 kPa).
        private const val TIRE_WARN_MIN_KPA = 210

        // Yandex Maps ships under several package names; reuse the navigation list so the
        // alias never drifts from the guidance-source set.
        private val YANDEX_MAPS_PACKAGES = NavPackages.YANDEX_MAPS.toList()

        // Polish aliases for stock DiLink apps whose launcher labels are Chinese/English and thus
        // unreachable by label match. Values are candidate packages in priority order; an alias
        // fires only when one of them is actually installed on this car (fleet cars differ).
        // App store and fridge app are excluded deliberately (user decision, 2026-07-05).
        internal val APP_ALIASES: Map<String, List<String>> = mapOf(
            "nawigator" to listOf("ru.yandex.yandexnavi"),
            "yandex nawigator" to listOf("ru.yandex.yandexnavi"),
            "yandex mapy" to YANDEX_MAPS_PACKAGES,
            "mapy" to YANDEX_MAPS_PACKAGES,
            "muzyka" to listOf("ru.yandex.music"),
            "yandex muzyka" to listOf("ru.yandex.music"),
            "kamera" to listOf("com.byd.avc"),
            "kamery" to listOf("com.byd.avc"),
            "przeglądarka" to listOf("com.android.chrome"),
            "chrome" to listOf("com.android.chrome"),
            "youtube" to listOf("anddea.youtube", "com.google.android.youtube"),
            "ustawienia" to listOf("com.android.settings"),
            "ustawienia samochodu" to listOf("com.byd.carsettings"),
            "ustawienia auta" to listOf("com.byd.carsettings"),
            "ustawienia pojazdu" to listOf("com.byd.carsettings"),
            "pliki" to listOf("com.byd.filemanager"),
            "menedżer plików" to listOf("com.byd.filemanager"),
            "tryb jazdy" to listOf("com.byd.drivemode"),
            "tryby jazdy" to listOf("com.byd.drivemode"),
            "tryb ochrony" to listOf("com.byd.sentrymode"),
            "ochrona" to listOf("com.byd.sentrymode"),
            "ochronny tryb" to listOf("com.byd.sentrymode"),
            "abrp" to listOf("com.iternio.abrpapp"),
            "planer tras" to listOf("com.iternio.abrpapp"),
            "centrum multimediów" to listOf("com.byd.mediacenter"),
            "centrum medialne" to listOf("com.byd.mediacenter"),
            "odtwarzacz" to listOf("com.byd.mediacenter"),
            "telefon" to listOf("com.byd.bluetoothcall"),
            "połączenia" to listOf("com.byd.bluetoothcall"),
            "wideorejestrator" to listOf("com.byd.cdr"),
            "rejestrator" to listOf("com.byd.cdr"),
        )

        // Launcher labels are external data. Transliterate them only for matching; the returned
        // label remains unchanged, so the user sees the device's own app name in the result.
        private val CYRILLIC_LATIN = mapOf(
            '\u0430' to "a", '\u0431' to "b", '\u0432' to "v", '\u0433' to "g",
            '\u0434' to "d", '\u0435' to "e", '\u0451' to "e", '\u0436' to "zh",
            '\u0437' to "z", '\u0438' to "i", '\u0439' to "i", '\u043a' to "k",
            '\u043b' to "l", '\u043c' to "m", '\u043d' to "n", '\u043e' to "o",
            '\u043f' to "p", '\u0440' to "r", '\u0441' to "s", '\u0442' to "t",
            '\u0443' to "u", '\u0444' to "f", '\u0445' to "h", '\u0446' to "ts",
            '\u0447' to "ch", '\u0448' to "sh", '\u0449' to "shch", '\u044a' to "",
            '\u044b' to "y", '\u044c' to "", '\u044d' to "e", '\u044e' to "yu",
            '\u044f' to "ya",
        )
    }
}
