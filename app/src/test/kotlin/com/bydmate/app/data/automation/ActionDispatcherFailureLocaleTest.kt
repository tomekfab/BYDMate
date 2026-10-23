package com.bydmate.app.data.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterMode
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.vehicle.VehicleWriteError
import com.bydmate.app.data.vehicle.WindowPane
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitSessionState
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #242 (English UI, BYD e2): a window that did not move was reported in the wrong language.
 * Every step failure reason comes from app strings: a Russian locale falls back to Polish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherFailureLocaleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
    private val split = mockk<SplitSessionManager>(relaxed = true)
    private val dispatcher = ActionDispatcher(vehicleApi, mockk<HelperClient>(relaxed = true), app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        cluster,
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        split,
        AppStrings(app), dagger.Lazy { io.mockk.mockk(relaxed = true) },
    ).also {
        it.clusterPollIntervalMs = 1L
        it.readSpeedNow = { 0 } // a standing car unless a test says otherwise
    }

    init {
        every { split.state } returns MutableStateFlow(SplitSessionState.Idle)
        coEvery { split.startLastPair() } returns null
    }

    private fun lang(tag: String) = LocalePreferences(app).setLanguage(tag)

    @After fun restoreLanguage() = lang("pl")

    private fun String.hasCyrillic() = any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }

    private val driverWindowOpen = ActionDef(command = "主驾打开100", displayName = "x", kind = "param")

    private fun stuck(vararg panes: WindowPane): Result<Unit> = Result.failure(
        VehicleWriteError.ReadbackMismatch("window_driver_open", "log line", panes.toList()))

    private suspend fun reason(action: ActionDef): String {
        val r = dispatcher.dispatch(action, null)
        assertFalse(action.toString(), r.success)
        return r.reason!!
    }

    // ── window readback (#242) ──────────────────────────────────────────────────

    @Test fun `a driver window that did not move is reported in English on an English UI`() = runTest {
        lang("en")
        coEvery { vehicleApi.dispatch(any()) } returns stuck(WindowPane.DRIVER)

        val text = reason(driverWindowOpen)

        assertEquals("driver window did not move, the command did not work", text)
        assertFalse(text, text.hasCyrillic())
    }

    @Test fun `a driver window that did not move uses Polish fallback on a Russian UI`() = runTest {
        lang("ru")
        coEvery { vehicleApi.dispatch(any()) } returns stuck(WindowPane.DRIVER)

        assertEquals("szyba kierowcy nie ruszyła się z miejsca, polecenie nie zadziałało", reason(driverWindowOpen))
    }

    @Test fun `several stuck windows are listed in the app language`() = runTest {
        coEvery { vehicleApi.dispatch(any()) } returns stuck(WindowPane.REAR_LEFT, WindowPane.REAR_RIGHT)

        lang("en")
        assertEquals("did not move: rear left window, rear right window", reason(driverWindowOpen))
        lang("ru")
        assertEquals("nie ruszyły się z miejsca: tylna lewa szyba, tylna prawa szyba", reason(driverWindowOpen))
    }

    // Audit 3.19 item 7: the journal shows a phrase in the app language, never the raw error.
    @Test fun `a readback mismatch that names no window is worded in the app language`() = runTest {
        coEvery { vehicleApi.dispatch(any()) } returns
            Result.failure(VehicleWriteError.ReadbackMismatch("doors_lock", "expected=2 got=1"))

        lang("en")
        assertEquals("The car did not carry out the command", reason(driverWindowOpen))
        lang("ru")
        assertEquals("Samochód nie wykonał polecenia", reason(driverWindowOpen))
    }

    @Test fun `write errors are worded per cause in the app language`() = runTest {
        val cases = listOf(
            VehicleWriteError.Sentinel("doors_lock") to "Samochód nie potwierdził polecenia",
            VehicleWriteError.AllowlistMiss("doors_lock") to "To polecenie nie jest obsługiwane",
            VehicleWriteError.OutOfRange("doors_lock", "value=7") to "Niedozwolona wartość polecenia",
            VehicleWriteError.HelperUnreachable("doors_lock", "binder dead") to "Polecenie nie dotarło do samochodu",
            VehicleWriteError.Unsupported("doors_lock") to "Polecenie nie jest sprawdzone w tym samochodzie i nie zadziałało",
            IllegalStateException("boom") to "Nie udało się wykonać polecenia",
        )
        lang("ru")
        for ((err, text) in cases) {
            coEvery { vehicleApi.dispatch(any()) } returns Result.failure(err)
            assertEquals(err.toString(), text, reason(driverWindowOpen))
        }
        lang("en")
        coEvery { vehicleApi.dispatch(any()) } returns Result.failure(VehicleWriteError.Sentinel("doors_lock"))
        assertEquals("The car did not confirm the command", reason(driverWindowOpen))
    }

    // The per-action run button writes through VehicleApi directly and words the error here.
    @Test fun `the run button gets the same text as a rule step`() {
        lang("en")
        val err = VehicleWriteError.ReadbackMismatch("window_driver_open", "log line", listOf(WindowPane.DRIVER))

        assertEquals("driver window did not move, the command did not work", dispatcher.vehicleFailureReason(err))
    }

    // ── drive mode (#253) ──────────────────────────────────────────────────────

    @Test fun `drive mode failures are worded per cause in the app language`() = runTest {
        val eco = ActionDef(command = "ECO模式", displayName = "x", kind = "param")
        val cases = listOf(
            VehicleWriteError.NotEquipped("drive_mode_eco") to
                ("Ten samochód nie obsługuje tego trybu jazdy" to "This car does not support this drive mode"),
            VehicleWriteError.StateBlocked("drive_mode_eco", "emergency flotation mode") to
                ("Samochód jest w awaryjnym trybie wysokiej wody, tryb jazdy pozostaje bez zmian" to
                    "The car is in the emergency high-water mode, the drive mode stays as it is"),
            VehicleWriteError.ReadbackMismatch("drive_mode_eco", "mode did not change") to
                ("Tryb jazdy się nie zmienił" to "The drive mode did not change"),
            VehicleWriteError.HelperUnreachable("drive_mode_eco", "unreachable") to
                ("Nie udało się zmienić trybu jazdy" to "Could not change the drive mode"),
            VehicleWriteError.SpeedBlocked("drive_mode_eco", "too fast", 40) to
                ("Ten tryb jazdy włącza się tylko do 15 km/h (teraz 40 km/h)" to
                    "This drive mode switches on only up to 15 km/h (now 40 km/h)"),
            VehicleWriteError.SpeedBlocked("drive_mode_eco", "speed unknown", null) to
                ("Prędkość nieznana, ten tryb jazdy nie zostanie włączony" to
                    "Speed unknown, this drive mode is not switched on"),
        )
        for ((err, texts) in cases) {
            coEvery { vehicleApi.dispatch(any()) } returns Result.failure(err)
            lang("ru")
            assertEquals(texts.first, reason(eco))
            lang("en")
            assertEquals(texts.second, reason(eco))
        }
    }

    // ── HUD (#292) ─────────────────────────────────────────────────────────────

    @Test fun `a car without a HUD says so in the app language`() = runTest {
        coEvery { vehicleApi.dispatch(any()) } returns Result.failure(VehicleWriteError.NotEquipped("hud_off"))
        val off = ActionDef(command = "关闭抬头显示", displayName = "x", kind = "param")
        lang("ru")
        assertEquals("Ten samochód nie ma HUD", reason(off))
        lang("en")
        assertEquals("This car has no HUD", reason(off))
    }

    // ── Battery warm-up (#311) ─────────────────────────────────────────────────

    @Test fun `a refused battery warm-up names the reason in the app language`() = runTest {
        val on = ActionDef(command = "打开电池预热", displayName = "x", kind = "param")
        val cases = listOf(
            VehicleWriteError.NotEquipped("battery_preheat_on") to
                ("Ten samochód nie ma podgrzewania baterii" to "This car has no battery warm-up"),
            VehicleWriteError.NotNeeded("battery_preheat_on", "permit=1") to
                ("Samochód zgłasza, że podgrzewanie baterii nie jest teraz potrzebne" to "The car reports that battery warm-up is not needed right now"),
            VehicleWriteError.StateBlocked("battery_preheat_on", "charge gun connected") to
                ("Podgrzewanie baterii jest niedostępne, gdy podłączony jest kabel ładowania" to
                    "Battery warm-up is unavailable while the charging cable is connected"),
            VehicleWriteError.ReadbackMismatch("battery_preheat_on", "no effect read=0") to
                ("Samochód nie wykonał polecenia" to "The car did not carry out the command"),
        )
        for ((err, texts) in cases) {
            coEvery { vehicleApi.dispatch(any()) } returns Result.failure(err)
            lang("ru")
            assertEquals(texts.first, reason(on))
            lang("en")
            assertEquals(texts.second, reason(on))
        }
    }

    @Test fun `a terrain mode at unknown speed is refused before the car is asked`() = runTest {
        lang("ru")
        dispatcher.readSpeedNow = { null }
        val snow = ActionDef(command = "雪地模式", displayName = "x", kind = "param")
        assertEquals("Prędkość nieznana, ten tryb jazdy nie zostanie włączony", reason(snow))
        io.mockk.coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    // ── every other step reason ────────────────────────────────────────────────

    private fun action(kind: String, payload: String?) =
        ActionDef(command = kind, displayName = "x", kind = kind, payload = payload)

    /** Steps that fail before touching the car, with Polish fallback text. */
    private val failingSteps = listOf(
        action("sentry", "7") to "Nieprawidłowy stan trybu ochrony",
        action("sentry", "1") to "Nie udało się przełączyć trybu ochrony",
        action("hotspot", "7") to "Nieprawidłowy stan hotspotu Wi-Fi",
        action("hotspot", "1") to "Nie udało się przełączyć hotspotu Wi-Fi",
        action("screen", "7") to "Nieprawidłowy stan ekranu",
        action("cluster_projection", "7") to "Nieprawidłowy stan wyświetlania na wskaźnikach",
        action("speak", null) to "nie podano tekstu",
        action("agent_query", null) to "nie podano zapytania",
        action("split_screen", null) to "nie podano payload",
        action("split_screen", """{"wide":"b","side":"left"}""") to "nie podano narrow",
        action("split_screen", """{"narrow":"a","side":"left"}""") to "nie podano wide",
        action("split_screen", """{"narrow":"a","wide":"b","side":"up"}""") to "nieprawidłowa strona",
        action("split_screen_toggle", null) to "nie zapisano pary do podziału ekranu",
        action("delay", null) to "Nie podano czasu pauzy",
        action("delay", "70000") to "Czas pauzy poza zakresem (0..60000 ms)",
        action("media_volume", null) to "Nie podano poziomu głośności",
        action("media_volume", "loud") to "Nieprawidłowy poziom głośności: loud",
        action("app_launch", null) to "nie podano packageName",
        action("app_launch", """{"packageName":"com.example.absent"}""") to
            "Aplikacja nie jest zainstalowana: com.example.absent",
        action("call", null) to "nie podano phone",
        action("navigate", null) to "nie podano payload",
        action("url", null) to "nie podano url",
        action("yandex_music", """{"mode":"search"}""") to "nie podano query",
        action("yandex_music", """{"mode":"radio"}""") to "Nieznany tryb Yandex Music: radio",
        action("youtube", null) to "nie podano query",
        action("youtube", """{"query":"x"}""") to "Aplikacja YouTube nie jest zainstalowana",
    )

    @Test fun `step reasons use Polish fallback on a Russian UI`() = runTest {
        lang("ru")
        for ((action, expected) in failingSteps) assertEquals(action.toString(), expected, reason(action))
    }

    @Test fun `step reasons carry no Cyrillic on an English UI`() = runTest {
        lang("en")
        for ((action, _) in failingSteps) {
            val text = reason(action)
            assertTrue(action.toString(), text.isNotBlank() && !text.hasCyrillic())
        }
    }

    @Test fun `cluster projection failures follow the app language`() = runTest {
        every { cluster.lastFailure() } returns null
        every { cluster.projectionMode() } returns ClusterMode.OFF
        lang("ru")
        assertEquals("wyświetlanie na wskaźnikach nie włączyło się", reason(action("cluster_projection", "1")))
        lang("en")
        assertEquals("projection to the cluster did not turn on", reason(action("cluster_projection", "1")))

        every { cluster.projectionMode() } returns ClusterMode.FULLSCREEN
        lang("ru")
        assertEquals("wyświetlanie nie zniknęło ze wskaźników", reason(action("cluster_projection", "0")))
        lang("en")
        assertEquals("projection did not leave the cluster", reason(action("cluster_projection", "0")))
    }

    // The agent tells a restarting daemon from a broken projection by the flag, not by the text.
    @Test fun `a restarting cluster daemon is flagged whatever the language`() = runTest {
        every { cluster.lastFailure() } returns "daemon"
        every { cluster.projectionMode() } returns ClusterMode.OFF

        lang("ru")
        val ruFallback = dispatcher.dispatch(action("cluster_projection", "1"), null)
        assertEquals("proces usługi uruchamia się ponownie", ruFallback.reason)
        assertTrue(ruFallback.daemonRestarting)
        lang("en")
        val en = dispatcher.dispatch(action("cluster_projection", "1"), null)
        assertEquals("the service process is restarting", en.reason)
        assertTrue(en.daemonRestarting)
    }

    // ── locale parity ──────────────────────────────────────────────────────────

    @Test fun `every failure reason key exists in all six locales`() {
        val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val locales = listOf("values", "values-en", "values-be", "values-pl", "values-pt", "values-zh")
        val keyPattern = Regex("""<string name="((?:dispatch_|window_pane_|window_stuck_)[a-z_]+)">([^<]*)</string>""")
        val byLocale = locales.associateWith { dir ->
            keyPattern.findAll(File(res, "$dir/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        }
        val all = byLocale.values.flatMap { it.keys }.toSet()
        assertTrue("the key pattern matched nothing", "window_stuck_one" in all)
        for ((dir, strings) in byLocale) {
            assertEquals("$dir is missing keys", all, strings.keys)
            strings.forEach { (key, text) -> assertFalse("$dir/$key has an em dash", text.contains('—')) }
        }
    }
}
