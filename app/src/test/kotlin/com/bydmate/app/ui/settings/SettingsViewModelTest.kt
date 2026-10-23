package com.bydmate.app.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.automation.RouteNavigatorUris
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.data.autoservice.AdbConnectFailure
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.backup.BackupManager
import com.bydmate.app.data.charging.ChargeConnector
import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.dao.TariffPeriodDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripCounterStats
import com.bydmate.app.data.local.dao.TripSummary
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.util.appLocalizedContext
import java.io.File
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.service.UpdateChecker
import com.bydmate.app.voice.openclaw.OpenClawConnectionCoordinator
import com.bydmate.app.voice.openclaw.OpenClawRecoveryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.bydmate.app.data.vehicle.DumpFidsResult
import com.bydmate.app.data.vehicle.SeatChannel
import com.bydmate.app.data.vehicle.SeatChannelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.agent.LlmConnectionResolver
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsViewModelTest {

    @get:Rule val trace = TraceRecorder()

    private val testDispatcher = StandardTestDispatcher()
    private val seatChannelStore: SeatChannelStore = mockk(relaxed = true)
    private val windowChannelStore: com.bydmate.app.data.vehicle.WindowChannelStore = mockk(relaxed = true)
    private val helperClient: HelperClient = mockk(relaxed = true)
    // relaxed → ensureRunning() defaults to false; tests that need the daemon "up" stub it true.
    private val helperBootstrap: HelperBootstrap = mockk(relaxed = true)
    private val agentOrchestrator: AgentOrchestrator = mockk(relaxed = true)
    private val energyDataDeadDetector: EnergyDataDeadDetector = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // The bare test Application does not run the app's locale bootstrap: pin the language the assertions read.
        LocalePreferences(ApplicationProvider.getApplicationContext<Context>()).setLanguage("en")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Fake DAOs ---

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun set(entity: SettingEntity) { map[entity.key] = entity.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private open class StubTripDao : TripDao {
        override suspend fun insert(trip: TripEntity): Long = 0L
        override suspend fun update(trip: TripEntity) {}
        override fun getAll(): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): TripEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getTodaySummary(dayStart: Long, dayEnd: Long): TripSummary = TripSummary(0.0, 0.0)
        override fun getLastTrip(): Flow<TripEntity?> = flowOf(null)
        override fun getRecent(limit: Int): Flow<List<TripEntity>> = flowOf(emptyList())
        override suspend fun getCount(): Int = 0
        override suspend fun getByBydId(bydId: Long): TripEntity? = null
        override suspend fun getTripsWithoutSoc(): List<TripEntity> = emptyList()
        override suspend fun getTripsWithoutCost(): List<TripEntity> = emptyList()
        override suspend fun getEnergyDataTripsWithoutCounters(sinceTs: Long): List<TripEntity> = emptyList()
        override suspend fun getHybridTripsWithoutCost(): List<TripEntity> = emptyList()
        override suspend fun getPeriodSummary(from: Long, to: Long): TripSummary = TripSummary(0.0, 0.0)
        override suspend fun getLiveTrips(): List<TripEntity> = emptyList()
        override suspend fun getByStartTsRange(minTs: Long, maxTs: Long): TripEntity? = null
        override suspend fun getAllSnapshot(): List<TripEntity> = emptyList()
        override suspend fun getWithEnergyInRange(from: Long, to: Long): List<TripEntity> = emptyList()
        override suspend fun deleteById(id: Long) {}
        override suspend fun deleteZeroKmTrips(): Int = 0
        override suspend fun fillOdometerIfEmpty(id: Long, startKm: Double?, endKm: Double?): Int = 0
        override suspend fun getTripsForCapacityEstimate(minSocDelta: Int, limit: Int): List<TripEntity> = emptyList()
        override suspend fun getRecentSummary(maxTrips: Int): TripSummary = TripSummary(0.0, 0.0)
        override suspend fun getRecentForEma(limit: Int): List<TripEntity> = emptyList()
        override suspend fun getForEmaSince(fromTs: Long): List<TripEntity> = emptyList()
        override suspend fun getRecentForEmaFiltered(minKm: Double, limit: Int): List<TripEntity> = emptyList()
        override fun observeCounterStats(from: Long, sessionStart: Long): Flow<TripCounterStats> = flowOf(TripCounterStats(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0L, 0.0, 0.0, 0L, 0, 0.0, 0.0, 0L))
    }

    private class StubTripPointDao : TripPointDao {
        override suspend fun insertAll(points: List<TripPointEntity>) {}
        override suspend fun getByTripId(tripId: Long): List<TripPointEntity> = emptyList()
        override suspend fun getByTripIds(tripIds: List<Long>): List<TripPointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun getAll(): List<TripPointEntity> = emptyList()
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
        override suspend fun getByTimeRange(from: Long, to: Long): List<TripPointEntity> = emptyList()
        override suspend fun attachToTrip(tripId: Long, from: Long, to: Long): Int = 0
        override suspend fun insert(point: TripPointEntity): Long = 0L
        override suspend fun deleteByTripId(tripId: Long): Int = 0
    }

    private open class StubChargeDao : ChargeDao {
        override suspend fun insert(charge: ChargeEntity): Long = 0L
        override suspend fun update(charge: ChargeEntity) {}
        override fun getAll(): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getById(id: Long): ChargeEntity? = null
        override fun getByDateRange(from: Long, to: Long): Flow<List<ChargeEntity>> = flowOf(emptyList())
        override suspend fun getPeriodSummary(from: Long, to: Long): ChargeSummary = ChargeSummary(0, 0.0, 0.0)
        override fun getLastCharge(): Flow<ChargeEntity?> = flowOf(null)
        override suspend fun getLastSuspendedCharge(): ChargeEntity? = null
        override suspend fun getStaleSessions(cutoffTs: Long): List<ChargeEntity> = emptyList()
        override suspend fun getLastChargeSync(): ChargeEntity? = null
        override suspend fun getRecentChargesWithBatteryData(): List<ChargeEntity> = emptyList()
        override suspend fun getMaxLifetimeKwhAtFinish(): Double? = null
        override suspend fun getAllAutoserviceCharges(): List<ChargeEntity> = emptyList()
        override suspend fun hasLegacyCharges(): Boolean = false
        override suspend fun deleteEmpty(): Int = 0
        override suspend fun getCompletedSince(since: Long): List<ChargeEntity> = emptyList()
        override suspend fun getInRangeAsc(from: Long, to: Long): List<ChargeEntity> = emptyList()
        override suspend fun getCompletedForPricing(until: Long): List<ChargeEntity> = emptyList()
        override suspend fun getWithMeterReading(): List<ChargeEntity> = emptyList()
        override suspend fun deletePhantomAutoserviceRows(): Int = 0
        override suspend fun delete(charge: ChargeEntity) {}
    }

    private class StubChargePointDao : ChargePointDao {
        override suspend fun insertAll(points: List<ChargePointEntity>) {}
        override suspend fun getByChargeId(chargeId: Long): List<ChargePointEntity> = emptyList()
        override suspend fun getCount(): Int = 0
        override suspend fun thinOldPoints(cutoff: Long, intervalMs: Long): Int = 0
    }

    private class StubIdleDrainDao : IdleDrainDao {
        override suspend fun insert(drain: IdleDrainEntity): Long = 0L
        override suspend fun update(drain: IdleDrainEntity) {}
        override fun getByDateRange(from: Long, to: Long): Flow<List<IdleDrainEntity>> = flowOf(emptyList())
        override suspend fun getTodayDrainKwh(dayStart: Long, dayEnd: Long): Double = 0.0
        override suspend fun getCount(): Int = 0
        override suspend fun getAll(): List<IdleDrainEntity> = emptyList()
        override suspend fun getTotalKwh(): Double = 0.0
        override suspend fun deleteAll() {}
        override suspend fun getTodayDrainHours(dayStart: Long, dayEnd: Long): Double = 0.0
        override suspend fun getKwhSince(since: Long): Double = 0.0
        override suspend fun getHoursSince(since: Long): Double = 0.0
        override suspend fun getKwhBetween(from: Long, to: Long): Double = 0.0
        override suspend fun getSince(since: Long): List<IdleDrainEntity> = emptyList()
    }

    private class StubBatterySnapshotDao : BatterySnapshotDao {
        override fun getAll(): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override fun getRecent(limit: Int): Flow<List<BatterySnapshotEntity>> = flowOf(emptyList())
        override suspend fun insert(snapshot: BatterySnapshotEntity): Long = 0L
        override suspend fun getLast(): BatterySnapshotEntity? = null
        override suspend fun getCount(): Int = 0
    }

    private class FakeAdbClient : AdbOnDeviceClient {
        override suspend fun connect(): Result<Unit> = Result.success(Unit)
        override suspend fun isConnected(): Boolean = false
        override fun lastConnectFailure(): AdbConnectFailure? = null
        override suspend fun exec(cmd: String): String? = null
        override suspend fun grantUsageStatsAppop(packageName: String): Boolean = false
        override suspend fun grantWriteSecureSettings(packageName: String): Boolean = false
        override suspend fun spawnHelper(token: String): Boolean = false
        override suspend fun killHelper(): Boolean = false
        override suspend fun readHelperLog(): String? = null
        override suspend fun helperHeartbeat(): Boolean = false
        override suspend fun shutdown() {}
    }

    // --- Factory ---

    private lateinit var settingsDao: FakeSettingsDao

    // Tariff fixture: fields rather than factory parameters, the factory is long enough.
    private var exportCostCalculator: com.bydmate.app.domain.cost.CostCalculator? = null

    // Car type handed to the next built VM; null keeps the relaxed monitor.
    private var vehicleKind: com.bydmate.app.data.nativestack.VehicleKind? = null
    private var tariffPeriodDaoOverride: TariffPeriodDao? = null
    // The log sender of the last built VM, to play the automatic upload (#324).
    private lateinit var logTelegramSender: com.bydmate.app.diagnostics.LogTelegramSender
    // Recorder state handed to the next built VM; null keeps the real recorder that never records.
    private var logRecorderState: kotlinx.coroutines.flow.MutableStateFlow<com.bydmate.app.diagnostics.LogRecorder.State>? = null

    /** In-memory stand-in for the Room DAO, including the unique `start_ts` constraint. */
    private class FakeTariffPeriodDao(initial: List<TariffPeriodEntity>) : TariffPeriodDao {
        val rows = initial.toMutableList()
        private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L
        override suspend fun insert(period: TariffPeriodEntity): Long {
            val id = if (period.id == 0L) nextId++ else period.id
            rows.removeAll { it.id == id || it.startTs == period.startTs }
            rows.add(period.copy(id = id))
            return id
        }
        override suspend fun update(period: TariffPeriodEntity) {
            check(rows.none { it.id != period.id && it.startTs == period.startTs }) {
                "UNIQUE constraint failed: tariff_periods.start_ts"
            }
            rows.replaceAll { if (it.id == period.id) period else it }
        }
        override suspend fun delete(period: TariffPeriodEntity) { rows.removeAll { it.id == period.id } }
        override suspend fun getAllAsc(): List<TariffPeriodEntity> = rows.sortedBy { it.startTs }
        override fun observeAll(): Flow<List<TariffPeriodEntity>> = flowOf(rows.sortedByDescending { it.startTs })
        override suspend fun getByStartTs(startTs: Long): TariffPeriodEntity? = rows.find { it.startTs == startTs }
        override suspend fun count(): Int = rows.size
    }

    // Stub BackupManager: no AppDatabase available in unit tests, use mockk
    private var backupManager: BackupManager = mockk(relaxed = true)

// Telegram Bot API stand-in for the auto-backup Test flow (#237).
    private var telegramBackupSink: com.bydmate.app.data.backup.TelegramBackupSink = mockk(relaxed = true)

    private fun testVoiceLanguageSetup(): com.bydmate.app.voice.VoiceLanguageSetup =
        mockk(relaxed = true) {
            every { state } returns kotlinx.coroutines.flow.MutableStateFlow(
                com.bydmate.app.voice.VoiceLanguageState(),
            )
        }

    private inner class ViewModelOptions {
        var updateChecker: UpdateChecker? = null
        var ttsModelManager: com.bydmate.app.voice.TtsModelManager? = null
        var ttsEngine: com.bydmate.app.voice.TtsEngine? = null
        var voiceLanguageSetup: com.bydmate.app.voice.VoiceLanguageSetup = testVoiceLanguageSetup()
        var openClawAssistant: com.bydmate.app.voice.openclaw.OpenClawAssistant = mockk(relaxed = true)
        var openClawConnectionCoordinator: OpenClawConnectionCoordinator = mockk(relaxed = true) {
            every { recovery } returns MutableStateFlow(OpenClawRecoveryState.Idle)
        }
        var assistantProbe: com.bydmate.app.voice.openclaw.AssistantConnectionProbe = mockk(relaxed = true)
        var llmConnectionResolver: LlmConnectionResolver? = null
        var voiceJournal: com.bydmate.app.voice.VoiceJournal = com.bydmate.app.voice.VoiceJournal()
    }

    private fun buildViewModel(configure: ViewModelOptions.() -> Unit = {}): SettingsViewModel =
        ViewModelFixture(ViewModelOptions().apply(configure)).viewModel

    private inner class ViewModelFixture(private val options: ViewModelOptions) {

        private val ctx: Context = ApplicationProvider.getApplicationContext()

        init { settingsDao = FakeSettingsDao() }
        private val settingsRepo = SettingsRepository(settingsDao, mockk<LocalePreferences>(relaxed = true))

        private val tripDao = object : StubTripDao() {
            override fun getAll(): Flow<List<TripEntity>> = flowOf(emptyList())
        }
        private val tripPointDao = StubTripPointDao()
        private val tripRepo = TripRepository(tripDao, tripPointDao, mockk<TripTombstoneDao>(relaxed = true), mockk<AppDatabase>(relaxed = true))

        private val chargeDao = object : StubChargeDao() {
            override fun getAll(): Flow<List<ChargeEntity>> = flowOf(emptyList())
        }
        private val chargeRepo = ChargeRepository(chargeDao, StubChargePointDao())
        private val idleDrainDao = StubIdleDrainDao()

        private val httpClient = OkHttpClient()
        private val resolvedUpdateChecker = options.updateChecker ?: UpdateChecker(httpClient, com.bydmate.app.util.AppStrings(ctx))

        private val energyReader = EnergyDataReader(ctx)
        private val historyImporter = HistoryImporter(
            ctx, energyReader, tripRepo, tripDao, tripPointDao, idleDrainDao,
            settingsRepo, com.bydmate.app.data.repository.LastSessionRepository(ctx),
            mockk<TripTombstoneDao>(relaxed = true), mockk(relaxed = true),
            com.bydmate.app.data.repository.OdometerMarks(ctx),
            com.bydmate.app.data.nativestack.kindMonitor(), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true),
        )

        private val openRouterClient = OpenRouterClient(httpClient)
        private val insightsManager = InsightsManager(ctx, openRouterClient, tripDao, idleDrainDao, chargeDao, settingsRepo)

        // Voice deps are not exercised by most settings tests; relaxed mocks keep them inert.
        private val resolvedTtsModelManager = options.ttsModelManager ?: mockk(relaxed = true)
        private val resolvedTtsEngine = options.ttsEngine ?: mockk<com.bydmate.app.voice.TtsEngine>(relaxed = true)
        private val continuousAsr = mockk<com.bydmate.app.voice.ContinuousAsr>(relaxed = true)
        private val voiceController = mockk<com.bydmate.app.voice.VoiceController>(relaxed = true)

        // Real recorder with an exec seam that would blow up: these tests never record.
        private val logRecorder = logRecorderState?.let { flow ->
            mockk<com.bydmate.app.diagnostics.LogRecorder>(relaxed = true) { every { state } returns flow }
        } ?: com.bydmate.app.diagnostics.LogRecorder(ctx) {
            throw UnsupportedOperationException("no logcat in tests")
        }
        private val resolvedLogTelegramSender = com.bydmate.app.diagnostics.LogTelegramSender(
            logRecorder, settingsRepo, telegramBackupSink, com.bydmate.app.util.AppStrings(ctx),
        )

        private val driverMemory = com.bydmate.app.agent.DriverMemory(
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
        )

        private val dayMemory = com.bydmate.app.agent.DayMemory(
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
        )

        private val adbRestoreManager = com.bydmate.app.data.autoservice.AdbRestoreManager(
            com.bydmate.app.data.autoservice.AdbRestorePreferencesImpl(ctx),
            mockk(relaxed = true),
            kotlinx.coroutines.test.TestScope(),
        )

        private val adbVerdictMonitor = mockk<com.bydmate.app.data.autoservice.AdbVerdictMonitor>(relaxed = true) {
            io.mockk.every { verdict } returns kotlinx.coroutines.flow.MutableStateFlow(null)
            io.mockk.every { checking } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        }


        init { logTelegramSender = resolvedLogTelegramSender }

        private val resolvedVehicleKind = vehicleKind?.let { com.bydmate.app.data.nativestack.kindMonitor(it) }
            ?: mockk<com.bydmate.app.data.nativestack.VehicleKindMonitor>(relaxed = true)

        val viewModel = SettingsViewModel(
            appContext = ctx,
            settingsRepository = settingsRepo,
            tripRepository = tripRepo,
            chargeRepository = chargeRepo,
            updateChecker = resolvedUpdateChecker,
            historyImporter = historyImporter,
            energyDataReader = energyReader,
            idleDrainDao = idleDrainDao,
            insightsManager = insightsManager,
            adbOnDeviceClient = FakeAdbClient(),
            localePreferences = LocalePreferences(ctx),
            backupManager = backupManager,
            chargingStateStore = com.bydmate.app.data.charging.ChargingStateStore(settingsRepo),
            catchUpJournal = com.bydmate.app.data.charging.CatchUpJournal(settingsRepo),
            ttsModelManager = resolvedTtsModelManager,
            continuousAsr = continuousAsr,
            ttsEngine = resolvedTtsEngine,
            voiceController = voiceController,
            seatChannelStore = seatChannelStore,
            windowChannelStore = windowChannelStore,
            helperClient = helperClient,
            helperBootstrap = helperBootstrap,
            agentOrchestrator = agentOrchestrator,
            llmConnectionResolver = options.llmConnectionResolver ?: mockk(relaxed = true),
            openRouterClient = openRouterClient,
            placeRepository = mockk(relaxed = true),
            energyDataDeadDetector = energyDataDeadDetector,
            hudController = mockk(relaxed = true),
            logRecorder = logRecorder,
            fidPushChannel = mockk(relaxed = true),
            splitPreferences = mockk(relaxed = true),
            splitSessionManager = mockk(relaxed = true),
            splitJournal = com.bydmate.app.split.NoSplitJournal,
            driverMemory = driverMemory,
            dayMemory = dayMemory,
            adbRestoreManager = adbRestoreManager,
            cloudOverWifiManager = mockk(relaxed = true),
            fidCatalogManager = mockk(relaxed = true),
            writeAllowlist = com.bydmate.app.data.vehicle.WriteAllowlist.EMPTY,
            ruleDao = mockk(relaxed = true),
            automationEngine = mockk(relaxed = true),
            voiceJournal = options.voiceJournal,
            tariffPeriodDao = tariffPeriodDaoOverride ?: mockk(relaxed = true),
            costCalculator = exportCostCalculator ?: mockk(relaxed = true),
            blindSpotController = mockk(relaxed = true),
            adbVerdictMonitor = adbVerdictMonitor,
            telegramBackupSink = telegramBackupSink,
            autoBackupScheduler = mockk(relaxed = true),
            appStrings = com.bydmate.app.util.AppStrings(ctx),
            logTelegramSender = resolvedLogTelegramSender,
            telegramReporter = mockk(relaxed = true),
            clusterMusicBridge = mockk(relaxed = true),
            vehicleWriteLogDao = mockk(relaxed = true),
            vehicleKindMonitor = resolvedVehicleKind,
            voiceLanguageSetup = options.voiceLanguageSetup,
            openClawAssistant = options.openClawAssistant,
            openClawConnectionCoordinator = options.openClawConnectionCoordinator,
            remoteDiagnostics = mockk(relaxed = true),
            assistantProbe = options.assistantProbe,
        )
    }

    // --- Tests ---

    @Test
    fun `setFontScale writes through to LocalePreferences and updates the flow`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1.0f, vm.fontScale.value)

        vm.setFontScale(1.3f)

        assertEquals(1.3f, vm.fontScale.value)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(1.3f, LocalePreferences(ctx).getFontScale())
    }

    @Test
    fun `initial state has default battery capacity`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SettingsRepository.DEFAULT_BATTERY_CAPACITY, vm.uiState.value.batteryCapacity)
    }

    @Test
    fun `initial state has default home tariff`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SettingsRepository.DEFAULT_HOME_TARIFF, vm.uiState.value.homeTariff)
    }

// Charging connector: GB/T until the driver picks another, the pick is saved.
    @Test
    fun `charge connector starts as GB-T and a pick is saved`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ChargeConnector.GBT, vm.uiState.value.chargeConnector)

        vm.saveChargeConnector(ChargeConnector.CHADEMO)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ChargeConnector.CHADEMO, vm.uiState.value.chargeConnector)
        assertEquals("chademo", settingsDao.map[SettingsRepository.KEY_CHARGE_CONNECTOR])
    }

    // Issue #23: pressing "Close" while an update is downloading must cancel the
    // download coroutine and clear the Downloading state. Without cancellation the
    // progress callback keeps re-emitting Downloading (re-opening the dialog) and
    // the download eventually fires the system install prompt unexpectedly.
    @Test
    fun `closing update dialog during download cancels job and clears downloading state`() = runTest {
        val uc = mockk<UpdateChecker>(relaxed = true)
        val info = UpdateChecker.UpdateInfo(
            version = "9.9.9",
            downloadUrl = "http://example.invalid/BYDMate.apk",
            releaseNotes = ""
        )
        coEvery { uc.checkForUpdate(any(), any()) } returns info
        val onProgressSlot = slot<(String) -> Unit>()
        coEvery { uc.downloadAndInstall(any(), any(), capture(onProgressSlot)) } coAnswers {
            onProgressSlot.captured.invoke("10%")
            delay(10_000)                       // still downloading...
            onProgressSlot.captured.invoke("100%") // must NOT run after the dialog is closed
        }

        val vm = buildViewModel { this.updateChecker = uc }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadUpdate()
        testDispatcher.scheduler.runCurrent() // start download, emit "10%", suspend on delay

        vm.hideUpdateDialog()
        testDispatcher.scheduler.advanceUntilIdle() // cancelled job must not resurrect state

        assertEquals(UpdateState.Idle, vm.uiState.value.updateDialogState)
    }

    @Test fun `resetSeatChannel sets winner to UNKNOWN`() = runTest {
        val vm = buildViewModel()
        vm.resetSeatChannel()
        verify { seatChannelStore.setWinner(SeatChannel.UNKNOWN) }
    }

    @Test fun `setDisableNativeAssistant updates state and applies via helper`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { helperClient.setAppHidden("com.byd.autovoice", true) } returns true

        vm.setDisableNativeAssistant(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.disableNativeAssistant)
        coVerify { helperClient.setAppHidden("com.byd.autovoice", true) }
    }

    @Test fun `switching the native assistant toggle on asks first and touches nothing yet`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleDisableNativeAssistant(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.showDisableNativeAssistantDialog)
        assertFalse(vm.uiState.value.disableNativeAssistant)
        coVerify(exactly = 0) { helperClient.setAppHidden(any(), any()) }
        assertNull(settingsDao.map[SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT])
    }

    @Test fun `confirming the native assistant warning disables it`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleDisableNativeAssistant(true)
        vm.confirmDisableNativeAssistant()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.showDisableNativeAssistantDialog)
        assertTrue(vm.uiState.value.disableNativeAssistant)
        assertEquals("true", settingsDao.map[SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT])
        coVerify { helperClient.setAppHidden("com.byd.autovoice", true) }
    }

    @Test fun `cancelling the native assistant warning leaves the toggle off and calls nothing`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleDisableNativeAssistant(true)
        vm.dismissDisableNativeAssistantDialog()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.showDisableNativeAssistantDialog)
        assertFalse(vm.uiState.value.disableNativeAssistant)
        coVerify(exactly = 0) { helperClient.setAppHidden(any(), any()) }
        assertNull(settingsDao.map[SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT])
    }

    // --- No saved choice (a reinstall after an uninstall that left the assistant disabled): the
    // toggle shows what the system has; a saved choice keeps ruling as before. ---

    private fun installAssistantPackage(pkg: String, state: Int) {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(ctx.packageManager).installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = pkg }
            },
        )
        ctx.packageManager.setApplicationEnabledSetting(pkg, state, 0)
    }

    @Test fun `no saved choice and a user-disabled autovoice show the toggle on`() = runTest {
        installAssistantPackage("com.byd.autovoice", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        installAssistantPackage("com.byd.vrassistant", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.disableNativeAssistant)
        coVerify(exactly = 0) { helperClient.setAppHidden(any(), any()) }
        assertNull(settingsDao.map[SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT])
    }

    @Test fun `no saved choice and only a user-disabled vrassistant show the toggle on`() = runTest {
        installAssistantPackage("com.byd.autovoice", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        installAssistantPackage("com.byd.vrassistant", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.disableNativeAssistant)
    }

    @Test fun `no saved choice and only a user-disabled autovoice engine show the toggle on`() = runTest {
        installAssistantPackage("com.byd.autovoice", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        installAssistantPackage("com.byd.autovoice.engine", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        installAssistantPackage("com.byd.vrassistant", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.disableNativeAssistant)
    }

    @Test fun `no saved choice and both packages enabled show the toggle off`() = runTest {
        installAssistantPackage("com.byd.autovoice", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        installAssistantPackage("com.byd.vrassistant", PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.disableNativeAssistant)
    }

    @Test fun `a saved choice rules over the system state`() = runTest {
        installAssistantPackage("com.byd.autovoice", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)

        val vm = buildViewModel()
        settingsDao.map[SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT] = "false"
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.disableNativeAssistant)
        assertTrue(trace.events().none { "native-assistant-from-system" in it })
    }

    @Test fun `switching off a toggle shown on from the system enables the assistant without the warning`() = runTest {
        installAssistantPackage("com.byd.autovoice", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        installAssistantPackage("com.byd.vrassistant", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.disableNativeAssistant)

        vm.toggleDisableNativeAssistant(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.showDisableNativeAssistantDialog)
        assertFalse(vm.uiState.value.disableNativeAssistant)
        assertEquals("false", settingsDao.map[SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT])
        coVerify(exactly = 1) { helperClient.setAppHidden("com.byd.autovoice", false) }
        coVerify(exactly = 1) { helperClient.setAppHidden("com.byd.vrassistant", false) }
    }

    @Test fun `switching the native assistant toggle off goes without the warning`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setDisableNativeAssistant(true)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleDisableNativeAssistant(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.showDisableNativeAssistantDialog)
        assertFalse(vm.uiState.value.disableNativeAssistant)
        assertEquals("false", settingsDao.map[SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT])
        coVerify { helperClient.setAppHidden("com.byd.autovoice", false) }
    }

    @Test fun `setVoiceEnabled persists and mirrors into voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setVoiceEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.voiceEnabled)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertTrue(
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getBoolean(SettingsRepository.KEY_VOICE_ENABLED, false)
        )
    }

    /**
     * Fix (Finding 3b): enabling Voice must best-effort re-bind the a11y key service (the same
     * daemon call ClusterProjectionManager.enableStarControl uses) — otherwise the PTT key filter
     * (SteeringWheelKeyService) is never bound on a fresh install with no cluster-projection toggle.
     * The daemon must be bootstrapped FIRST: HelperClient only resolves an existing binder, so
     * without ensureRunning() the re-bind silently no-ops when the daemon is not up.
     */
    @Test fun `setVoiceEnabled true bootstraps daemon then triggers accessibility re-bind`() = runTest {
        coEvery { helperBootstrap.ensureRunning() } returns true
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setVoiceEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerifyOrder {
            helperBootstrap.ensureRunning()
            helperClient.enableAccessibilityService()
        }
    }

    @Test fun `setVoiceEnabled true skips a11y re-bind when daemon bootstrap fails`() = runTest {
        coEvery { helperBootstrap.ensureRunning() } returns false
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setVoiceEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { helperClient.enableAccessibilityService() }
    }

    @Test fun `setVoiceEnabled false does not touch daemon or accessibility re-bind`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setVoiceEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { helperBootstrap.ensureRunning() }
        coVerify(exactly = 0) { helperClient.enableAccessibilityService() }
    }

    @Test fun `agent identity state flows initialize from voice prefs`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
            .edit()
            .putString("agent_name", "Zosia")
            .putString("agent_persona", "snarky")
            .apply()

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Zosia", vm.agentName.value)
        assertEquals("snarky", vm.agentPersona.value)
        assertEquals("Zosia", vm.uiState.value.agentName)
        assertEquals("snarky", vm.uiState.value.agentPersona)
    }

    @Test fun `driver memory facts land in state and forgetAgentMemory clears storage`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
        val memory = com.bydmate.app.agent.DriverMemory(prefs)
        memory.remember("mam na imię Ania")
        memory.remember("lubi 22 stopnie w kabinie")

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.uiState.value.agentMemoryFacts.size)

        vm.forgetAgentMemory()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<String>(), vm.uiState.value.agentMemoryFacts)
        assertEquals(emptyList<String>(), com.bydmate.app.agent.DriverMemory(prefs).facts())
    }

    // Final review fix, finding 3: a legacy "denis"/"dmitri" id read verbatim into state left no
    // radio row selected in Settings, even though playback already resolves it via byId(). State
    // must resolve through the same catalog lookup so the migrated voice shows selected.
    @Test fun `setAgentName and setAgentPersona mirror to voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentName("Marek")
        vm.setAgentPersona("engineer")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Marek", vm.agentName.value)
        assertEquals("engineer", vm.agentPersona.value)
        assertEquals("Marek", vm.uiState.value.agentName)
        assertEquals("engineer", vm.uiState.value.agentPersona)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
        assertEquals("Marek", prefs.getString("agent_name", null))
        assertEquals("engineer", prefs.getString("agent_persona", null))
    }

    @Test fun `setAgentEnabled persists and updates state`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.agentEnabled)
    }

    @Test fun `setAgentName persists and mirrors into voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentName("Zosia")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Zosia", vm.uiState.value.agentName)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            "Zosia",
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("agent_name", "")
        )
    }

    @Test fun `setAgentPersona persists and mirrors into voice prefs`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentPersona("engineer")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("engineer", vm.uiState.value.agentPersona)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            "engineer",
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("agent_persona", "")
        )
    }

    /** #190: default is Yandex, the setter mirrors into the same voice prefs the dispatcher reads. */
    @Test fun `route navigator defaults to yandex and persists the choice`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(RouteNavigatorUris.YANDEX, vm.uiState.value.routeNavigator)

        vm.setRouteNavigator(RouteNavigatorUris.DGIS)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(RouteNavigatorUris.DGIS, vm.uiState.value.routeNavigator)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            RouteNavigatorUris.DGIS,
            ctx.getSharedPreferences(RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, "")
        )
    }

    /** #305: Waze persists and a fresh view model reads it back. */
    @Test fun `route navigator waze persists and reads back`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setRouteNavigator("waze")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("waze", vm.uiState.value.routeNavigator)
        val ctx: Context = ApplicationProvider.getApplicationContext()
        assertEquals(
            "waze",
            ctx.getSharedPreferences(RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, "")
        )
        val reread = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("waze", reread.uiState.value.routeNavigator)
    }

    /** The dump header must carry the choice: it is the only place a user log shows it. */
    @Test fun `the diagnostic header reports the chosen route navigator`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.setRouteNavigator(RouteNavigatorUris.DGIS)
        testDispatcher.scheduler.advanceUntilIdle()

        // The header is written before the logcat pipe (which the test seam blows up on),
        // so the started file keeps the settings block even though the start itself fails.
        vm.startLogRecording()
        testDispatcher.scheduler.advanceUntilIdle()
        val header = awaitDiagnosticHeader()

        assertTrue(
            "the settings block must report the navigator, was:\n$header",
            header.contains("route_navigator=dgis"),
        )
    }

    /** Wave 1: the dump carries the agent section — tools and answer of the last turns. */
    @Test fun `the diagnostic header reports the agent journal`() = runTest {
        val journal = com.bydmate.app.voice.VoiceJournal()
        journal.add(
            com.bydmate.app.voice.VoiceJournalEntry(
                timestampMs = System.currentTimeMillis(),
                transcript = "zamknij okna",
                route = com.bydmate.app.voice.VoiceJournalEntry.Route.AGENT,
                detail = "Okna zamknięte",
                outcome = com.bydmate.app.voice.VoiceJournalEntry.Outcome.OK,
                tools = listOf(com.bydmate.app.agent.AgentToolOutcome("vehicle_control", true)),
                answer = "Okna zamknięte",
            )
        )
        val vm = buildViewModel { this.voiceJournal = journal }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startLogRecording()
        testDispatcher.scheduler.advanceUntilIdle()
        val header = awaitDiagnosticHeader()

        assertTrue("no agent section, was:\n$header", header.contains("--- agent ---"))
        assertTrue("no tool line, was:\n$header", header.contains("vehicle_control:ok"))
        assertTrue("no answer line, was:\n$header", header.contains("answer: Okna zamknięte"))
        assertTrue("no voice section, was:\n$header", header.contains("--- voice ---"))
        assertTrue("no counters, was:\n$header", header.contains("sessions=1 nlu_ok=0 automation_ok=0 agent=1 refused=0"))
        assertTrue("no session line, was:\n$header", header.contains("route=agent heard=\"zamknij okna\" cmd=- reason=- result=ok"))
    }

    /** #237: the backup section names the bot and the chat tail, never the token. */
    @Test fun `the diagnostic header never contains the telegram token`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN] = "123456:SECRET-token-value"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID] = "987654321"

        vm.startLogRecording()
        testDispatcher.scheduler.advanceUntilIdle()
        val header = awaitDiagnosticHeader()

        assertTrue("no auto backup section, was:\n$header", header.contains("--- auto backup ---"))
        assertTrue(header, header.contains("telegram: configured=yes bot=- chat=…4321"))
        assertFalse("token leaked into the dump", header.contains("SECRET-token-value"))
    }

    /** #238: the backup section prints the parts of the automatic and of the manual save. */
    @Test fun `the diagnostic header reports the backup parts`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        settingsDao.map[SettingsRepository.KEY_AUTO_BACKUP_PARTS] = "keys,tables"

        vm.startLogRecording()
        testDispatcher.scheduler.advanceUntilIdle()
        val header = awaitDiagnosticHeader()

        assertTrue(header, header.contains("parts: auto=tables,keys manual=tables,settings"))
    }

    /** The trace closes the header: what the app did before the recording, newest event last. */
    @Test fun `the diagnostic header ends with the trace, newest event last`() = runTest {
        val ptt = Trace.event(TraceArea.USER, "ptt", "listening" to false)
        Trace.event(TraceArea.VOICE, "session-start", by = ptt)
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startLogRecording()
        testDispatcher.scheduler.advanceUntilIdle()
        val header = awaitDiagnosticHeader(until = "voice  session-start #2 by=#1")

        val section = header.substringAfter("--- trace ---\n").substringBefore("===============================")
        val events = section.lines().filter { it.isNotBlank() && !it.startsWith("-- ") }.map { it.substringAfter(' ') }
        assertEquals(listOf("user   ptt listening=false #1", "voice  session-start #2 by=#1"), events)
        assertTrue(header, header.indexOf("--- last crash ---") < header.indexOf("--- trace ---"))
    }

    /** The header lands on the real Dispatchers.IO, which the test scheduler cannot advance.
     *  [until] is a text the finished header contains. */
    private fun awaitDiagnosticHeader(timeoutMs: Long = 10_000, until: String = "--- settings ---"): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val file = listOf(publicDumpDir(), fallbackDumpDir())
                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                .filter { it.name.startsWith("bydmate_logs_") }
                .maxByOrNull { it.lastModified() }
            val text = file?.readText().orEmpty()
            if (text.contains(until)) return text
            Thread.sleep(10)
        }
        throw AssertionError("no diagnostic header was written within $timeoutMs ms")
    }

    /** A value written by an older build (or garbage) must not leak into the UI or the dispatcher. */
    @Test fun `an unknown stored navigator reads back as yandex`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, "2gis").commit()

        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(RouteNavigatorUris.YANDEX, vm.uiState.value.routeNavigator)
    }

    @Test fun `testAgentModel on Answer sets result with seconds and answer prefix`() = runTest {
        coEvery { agentOrchestrator.ask(any()) } returns AgentResult.Answer("Poziom baterii wynosi 62 procent.")
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.testAgentModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val result = vm.uiState.value.modelTestResult
        assertTrue(result != null)
        assertTrue(result!!.contains(Regex("""\d+\.\d""")))
        assertTrue(result.contains("Poziom baterii wynosi 62 procent."))
        assertTrue(!vm.uiState.value.modelTestRunning)
    }

    @Test fun `testAgentModel on Error sets result with error prefix`() = runTest {
        coEvery { agentOrchestrator.ask(any()) } returns AgentResult.Error("Nie udało się połączyć z serwerem")
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.testAgentModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val ctx: Context = ApplicationProvider.getApplicationContext()
        val expected = ctx.getString(
            com.bydmate.app.R.string.settings_error_with_message, "Nie udało się połączyć z serwerem"
        )
        assertEquals(expected, vm.uiState.value.modelTestResult)
        assertTrue(!vm.uiState.value.modelTestRunning)
    }

    @Test fun `testAgentModel second tap while running is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { agentOrchestrator.ask(any()) } coAnswers {
            gate.await()
            AgentResult.Answer("ok")
        }
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.testAgentModel()
        testDispatcher.scheduler.runCurrent() // in flight, suspended on gate
        assertTrue(vm.uiState.value.modelTestRunning)

        vm.testAgentModel() // second tap while running — must be ignored
        testDispatcher.scheduler.runCurrent()

        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { agentOrchestrator.ask(any()) }
    }

    // Wave O T3: stored "openai" tts_source must be migrated to "offline" on first load,
    // because the OpenAI TTS backend was removed (model not available on OpenRouter).
    @Test
    fun `resetTripSourceDetection delegates to detector`() {
        val vm = buildViewModel()
        vm.resetTripSourceDetection()
        verify(exactly = 1) { energyDataDeadDetector.reset() }
    }

    @Test
    fun `dumpFids calls ensureRunning before helperClient dumpFids (C-3)`() = runTest {
        // C-3: ensureRunning must be called before dumpFids so a stale old-format daemon is
        // replaced before the TX_DUMP_FIDS call is attempted.
        //
        // Anti-vacuity: removing helperBootstrap.ensureRunning() from SettingsViewModel.dumpFids()
        // makes "ensureRunning" absent from callOrder, failing the assertEquals assertion.
        val callOrder = mutableListOf<String>()
        coEvery { helperBootstrap.ensureRunning() } answers { callOrder += "ensureRunning"; true }
        coEvery { helperClient.dumpFids() } answers { callOrder += "dumpFids"; DumpFidsResult.BinderAbsent }
        val vm = buildViewModel()
        vm.dumpFids()
        awaitFidDumpDone(vm)
        assertEquals(
            "ensureRunning must be called before dumpFids",
            listOf("ensureRunning", "dumpFids"),
            callOrder,
        )
    }

    @Test
    fun `dumpFids sets unavailable error status when helperClient returns BinderAbsent`() = runTest {
        coEvery { helperClient.dumpFids() } returns DumpFidsResult.BinderAbsent
        val vm = buildViewModel()
        vm.dumpFids()
        // dumpFids() runs on the real Dispatchers.IO, which the test scheduler cannot advance.
        val status = awaitFidDumpDone(vm)
// settings_fid_dump_error_unavailable renders as the local daemon-unavailable message.
        assertTrue(
            "BinderAbsent must map to unavailable string, was: $status",
            status.contains("daemon"),
        )
    }

    @Test
    fun `dumpFids sets read-error status when helperClient returns ReadError`() = runTest {
        coEvery { helperClient.dumpFids() } returns DumpFidsResult.ReadError("status=-1 at chunk 0")
        val vm = buildViewModel()
        vm.dumpFids()
        // dumpFids() runs on the real Dispatchers.IO, which the test scheduler cannot advance.
        val status = awaitFidDumpDone(vm)
        val context: Context = ApplicationProvider.getApplicationContext()
        val readError = context.getString(
            com.bydmate.app.R.string.settings_fid_dump_error_read,
            "status=-1 at chunk 0",
        )
        assertEquals(
            context.getString(com.bydmate.app.R.string.settings_error_with_message, readError),
            status,
        )
    }

    @Test
    fun `dumpFids sets empty-firmware status when helperClient returns Success with blank string`() = runTest {
        coEvery { helperClient.dumpFids() } returns DumpFidsResult.Success("")
        val vm = buildViewModel()
        vm.dumpFids()
        // dumpFids() runs on the real Dispatchers.IO, which the test scheduler cannot advance.
        val status = awaitFidDumpDone(vm)
        val context: Context = ApplicationProvider.getApplicationContext()
        assertEquals(context.getString(com.bydmate.app.R.string.settings_fid_dump_empty), status)
    }

    // ---------------------------------------------------------------------------
    // W6-F4: the dump must land straight in the public Download folder so the user
    // can reach it with a file manager or adb pull (filesDir was unreachable). No
    // subfolder: CSV export, backup, APK update and the log export all write there too.
    // ---------------------------------------------------------------------------

    private fun publicDumpDir(): File =
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )

    private fun fallbackDumpDir(): File =
        ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null)!!

    private fun dumpFilesIn(dir: File): List<File> =
        dir.listFiles { _, name -> name.startsWith("fid-dump-") && name.endsWith(".txt") }
            ?.toList() ?: emptyList()

    /** Download is shared with other exports — clear only our own dumps, never the folder. */
    private fun clearDumpsIn(dir: File) = dumpFilesIn(dir).forEach { it.delete() }

    @Test
    fun `openRestorePicker lists backups into the state, close clears it`() = runTest {
        val files = listOf(File("/sdcard/Download/bydmate_backup_2.zip"), File("/sdcard/Download/bydmate_backup_1.zip"))
        backupManager = mockk { every { listBackups() } returns files }
        val vm = buildViewModel().apply { ioDispatcher = testDispatcher }
        assertNull(vm.uiState.value.restoreCandidates)

        vm.openRestorePicker()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(files, vm.uiState.value.restoreCandidates)

        vm.closeRestorePicker()
        assertNull(vm.uiState.value.restoreCandidates)
    }

    @Test
    fun `a picked backup opens the parts dialog with the parts its manifest lists`() = runTest {
        val file = File("/sdcard/Download/bydmate_backup_1.zip")
        val parts = setOf(com.bydmate.app.data.backup.BackupPart.TABLES)
        backupManager = mockk { every { archiveParts(file) } returns parts }
        val vm = buildViewModel().apply { ioDispatcher = testDispatcher }

        vm.pickRestoreFile(file)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(RestoreChoice(file, parts), vm.uiState.value.restoreChoice)
        vm.dismissRestoreChoice()
        assertNull(vm.uiState.value.restoreChoice)
    }

    @Test
    fun `restoreConfig ignores a second restore while the first one runs`() = runTest {
        val file = File("/sdcard/Download/bydmate_backup_1.zip")
        val parts = setOf(com.bydmate.app.data.backup.BackupPart.TABLES)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        backupManager = mockk { every { restore(file, parts) } answers { calls.incrementAndGet(); error("broken") } }
        val vm = buildViewModel().apply { ioDispatcher = testDispatcher }

        vm.restoreConfig(file, parts)
        assertTrue(vm.uiState.value.restoreInProgress)
        vm.restoreConfig(file, parts)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, calls.get())
        assertFalse(vm.uiState.value.restoreInProgress)
        assertTrue(vm.uiState.value.configStatus!!.contains("broken"))
    }

    @Test
    fun `openRestorePicker ignores repeated taps while a scan runs`() = runTest {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        backupManager = mockk { every { listBackups() } answers { calls.incrementAndGet(); emptyList() } }
        val vm = buildViewModel().apply { ioDispatcher = testDispatcher }

        vm.openRestorePicker()          // job created, not yet run on StandardTestDispatcher
        vm.openRestorePicker()
        vm.openRestorePicker()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, calls.get())
    }

    @Test
    fun `closeRestorePicker during a scan drops the scan result`() = runTest {
        val files = listOf(File("/sdcard/Download/bydmate_backup_1.zip"))
        lateinit var vm: SettingsViewModel
        // The scan closes the picker from inside listBackups(): ensureActive() after it must drop the result.
        backupManager = mockk { every { listBackups() } answers { vm.closeRestorePicker(); files } }
        vm = buildViewModel().apply { ioDispatcher = testDispatcher }

        vm.openRestorePicker()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.restoreCandidates)
    }

    /**
     * dumpFids() runs on the real [Dispatchers.IO], so the test scheduler cannot advance it —
     * poll the status until it leaves the "in progress" state.
     */
    private fun awaitFidDumpDone(vm: SettingsViewModel, timeoutMs: Long = 10_000): String {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val inProgress = ctx.appLocalizedContext()
            .getString(com.bydmate.app.R.string.settings_fid_dump_in_progress)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = vm.uiState.value.fidDumpStatus
            if (status != null && status != inProgress) return status
            Thread.sleep(10)
        }
        throw AssertionError("dumpFids did not finish within $timeoutMs ms")
    }

    @Test
    fun `dumpFids writes into the public Download folder keeping the 3-line header (W6-F4)`() = runTest {
        // Anti-vacuity: any other target (filesDir, or a BYDMate/ subfolder) leaves Download
        // itself empty and the size assertion fails.
        clearDumpsIn(publicDumpDir())
        val dump = "FID_A=1\nFID_B=2\n"
        coEvery { helperClient.dumpFids() } returns DumpFidsResult.Success(dump)
        val vm = buildViewModel()

        vm.dumpFids()
        val status = awaitFidDumpDone(vm)

        val files = dumpFilesIn(publicDumpDir())
        assertEquals("dump must be written straight to Download, status was: $status", 1, files.size)
        val file = files.single()
        assertTrue(
            "file name must stay fid-dump-<yyyyMMdd-HHmmss>.txt, was ${file.name}",
            Regex("""fid-dump-\d{8}-\d{6}\.txt""").matches(file.name),
        )
        val lines = file.readText().lines()
        // Full structure, not just the prefix: dropping the version code, the timestamp, the
        // build id or the double space between model and build must fail the assertions.
        assertTrue(
            "line 1 must be 'BYDMate <name> (<code>), <yyyy-MM-dd HH:mm>', was: ${lines[0]}",
            Regex("""^BYDMate .+ \(\d+\), \d{4}-\d{2}-\d{2} \d{2}:\d{2}$""").matches(lines[0]),
        )
        assertTrue(
            "line 2 must be 'model: <model>  build: <build>' (double space), was: ${lines[1]}",
            Regex("""^model: .+ {2}build: .+$""").matches(lines[1]),
        )
        assertEquals("line 3 must be the separator", "---", lines[2])
        assertEquals("body must be the raw dump", dump, file.readText().substringAfter("---\n"))
        // Path shape only. The share sheet cannot be asserted here: Robolectric puts
        // getExternalStorageDirectory() under external-cache/ while the public Download lives
        // under external-files/, so <external-path path="Download/"> never matches in tests.
        // The fallback branch, whose root IS resolvable, carries the share assertion instead.
        assertTrue(
            "status must end with the visible path without a subfolder, was: $status",
            status.endsWith("Download/${file.name}"),
        )
    }

    @Test
    fun `dumpFids keeps a two-file rolling window in the public folder (W6-F4)`() = runTest {
        // Anti-vacuity: pointing the cleanup at any other folder leaves all 3 files here.
        val dir = publicDumpDir().apply { mkdirs() }
        clearDumpsIn(dir)
        val oldest = File(dir, "fid-dump-20200101-000000.txt").apply {
            writeText("oldest"); setLastModified(1_000_000L)
        }
        val newer = File(dir, "fid-dump-20200102-000000.txt").apply {
            writeText("newer"); setLastModified(2_000_000L)
        }
        coEvery { helperClient.dumpFids() } returns DumpFidsResult.Success("FID_A=1\n")
        val vm = buildViewModel()

        vm.dumpFids()
        val status = awaitFidDumpDone(vm)

        val names = dumpFilesIn(dir).map { it.name }
        assertEquals("rolling window must keep exactly 2 dumps, was $names (status: $status)", 2, names.size)
        assertFalse("oldest dump must be deleted", oldest.exists())
        assertTrue("most recent previous dump must survive", newer.exists())
    }

    @Test
    fun `dumpFids falls back to app external files dir when public folder is unavailable (W6-F4)`() = runTest {
        // Force the public candidate to fail by making Download read-only. (Replacing it with a
        // regular file does not work: the Robolectric shadow re-creates the public dir on every
        // getExternalStoragePublicDirectory() call.)
        val publicDir = publicDumpDir().apply { mkdirs(); setWritable(false) }
        assertFalse(
            "precondition: Download must be non-writable for this test (are you running as root?)",
            publicDir.canWrite(),
        )
        clearDumpsIn(fallbackDumpDir())
        try {
            coEvery { helperClient.dumpFids() } returns DumpFidsResult.Success("FID_A=1\n")
            val vm = buildViewModel()

            vm.dumpFids()
            val status = awaitFidDumpDone(vm)

            val files = dumpFilesIn(fallbackDumpDir())
            assertEquals("dump must land in the getExternalFilesDir fallback, status: $status", 1, files.size)
            assertFalse(
                "fallback must not surface an error, status: $status",
                status.contains("Błąd") || status.contains("Error"),
            )
            assertTrue("status must name the saved file, was: $status", status.contains(files.single().name))
        } finally {
            publicDir.setWritable(true)
        }
    }

    // --- Tariff periods: the unique start date and the reach of a recalculation ---

    private val augustStart = 1_754_006_400_000L   // 01.08.2026
    private val septemberStart = 1_756_684_800_000L // 01.09.2026

    private fun twoPeriods() = FakeTariffPeriodDao(
        listOf(
            TariffPeriodEntity(id = 1, startTs = augustStart, homeRate = 0.10, dcRate = 0.50),
            TariffPeriodEntity(id = 2, startTs = septemberStart, homeRate = 0.20, dcRate = 0.60),
        )
    )

    @Test
    fun `saving a period onto a date another one holds is refused instead of crashing`() = runTest {
        val dao = twoPeriods()
        tariffPeriodDaoOverride = dao
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveTariffPeriod(dao.rows.first { it.id == 2L }.copy(startTs = augustStart))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("the dialog must say why", vm.uiState.value.tariffPeriodError != null)
        assertEquals(septemberStart, dao.rows.first { it.id == 2L }.startTs)
        assertEquals(2, dao.rows.size)
    }

    // The earliest period also prices everything before its own date, so editing or deleting
    // it has to re-price the whole history, not just the span from its start.
    @Test
    fun `editing the earliest period recalculates the whole history`() = runTest {
        val dao = twoPeriods()
        tariffPeriodDaoOverride = dao
        val calculator = mockk<com.bydmate.app.domain.cost.CostCalculator>(relaxed = true)
        exportCostCalculator = calculator
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveTariffPeriod(dao.rows.first { it.id == 1L }.copy(homeRate = 0.99))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { calculator.recalculate(0L, Long.MAX_VALUE) }
    }

    @Test
    fun `deleting the earliest period recalculates the whole history`() = runTest {
        val dao = twoPeriods()
        tariffPeriodDaoOverride = dao
        val calculator = mockk<com.bydmate.app.domain.cost.CostCalculator>(relaxed = true)
        exportCostCalculator = calculator
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.deleteTariffPeriod(dao.rows.first { it.id == 1L })
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { calculator.recalculate(0L, Long.MAX_VALUE) }
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `editing a later period recalculates only from its own date`() = runTest {
        val dao = twoPeriods()
        tariffPeriodDaoOverride = dao
        val calculator = mockk<com.bydmate.app.domain.cost.CostCalculator>(relaxed = true)
        exportCostCalculator = calculator
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.saveTariffPeriod(dao.rows.first { it.id == 2L }.copy(homeRate = 0.42))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { calculator.recalculate(septemberStart, Long.MAX_VALUE) }
    }

    // DM-i (#184): the litre price is part of a period, so a new price re-prices from its date only.
    @Test
    fun `a new period with a litre price recalculates only from its own date`() = runTest {
        val dao = twoPeriods()
        tariffPeriodDaoOverride = dao
        val calculator = mockk<com.bydmate.app.domain.cost.CostCalculator>(relaxed = true)
        exportCostCalculator = calculator
        vehicleKind = com.bydmate.app.data.nativestack.VehicleKind.HYBRID
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        val octoberStart = 1_759_276_800_000L // 01.10.2026

        vm.saveTariffPeriod(
            TariffPeriodEntity(startTs = octoberStart, homeRate = 0.20, dcRate = 0.60, fuelPricePerLiter = 2.5)
        )
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { calculator.recalculate(octoberStart, Long.MAX_VALUE) }
        coVerify(exactly = 0) { calculator.recalculate(0L, any()) }
        assertEquals(2.5, dao.rows.single { it.startTs == octoberStart }.fuelPricePerLiter!!, 0.0)
        assertNull(dao.rows.single { it.startTs == septemberStart }.fuelPricePerLiter)
    }

// --- Telegram backup bot Test (#237) ---

    private fun tgSinkFake(): com.bydmate.app.data.backup.TelegramBackupSink = mockk<com.bydmate.app.data.backup.TelegramBackupSink>().also {
        telegramBackupSink = it
    }

    private fun checkToken(vm: SettingsViewModel, token: String) {
        vm.updateTgBackupToken(token)
        vm.checkTelegramBackup()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `telegram check with a rejected token shows the reason and stores nothing`() = runTest {
        val sink = tgSinkFake()
        coEvery { sink.getMe("bad") } returns Result.failure(
            com.bydmate.app.data.backup.TelegramSinkException(com.bydmate.app.data.backup.TelegramError.BAD_TOKEN, 401)
        )
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        checkToken(vm, " bad ")

        val ctx: Context = ApplicationProvider.getApplicationContext()
        val state = vm.uiState.value
        assertFalse(state.tgBackupChecking)
        assertEquals("bad", state.tgBackupToken)
        val reason = ctx.getString(com.bydmate.app.R.string.settings_tg_error_bad_token)
        assertTrue(state.tgBackupStatus!!, state.tgBackupStatus!!.contains(reason))
        assertNull(settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN])
        coVerify(exactly = 0) { sink.findLinkChat(any(), any()) }
    }

    private val ctxForTg: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `first check of a new bot moves to the code step and stores nothing`() = runTest {
        val sink = tgSinkFake()
        coEvery { sink.getMe("123:abc") } returns Result.success("my_car_bot")
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        checkToken(vm, "123:abc")

        val code = vm.uiState.value.tgBackupCode!!
        assertTrue(code, Regex("\\d{6}").matches(code))
        assertEquals("my_car_bot", vm.uiState.value.tgBackupBotName)
        assertNull(vm.uiState.value.tgBackupStatus)
        assertNull(vm.uiState.value.tgBackupBinding)
        assertNull(settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN])
        coVerify(exactly = 0) { sink.findLinkChat(any(), any()) }
    }

    @Test
    fun `check before the code was sent keeps the same code and stores nothing`() = runTest {
        val sink = tgSinkFake()
        coEvery { sink.getMe("123:abc") } returns Result.success("my_car_bot")
        coEvery { sink.findLinkChat("123:abc", any()) } returns Result.success(null)
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        checkToken(vm, "123:abc")
        val code = vm.uiState.value.tgBackupCode!!

        checkToken(vm, "123:abc")

        assertEquals(code, vm.uiState.value.tgBackupCode)
        assertEquals(
            ctxForTg.getString(com.bydmate.app.R.string.settings_tg_backup_code_not_received),
            vm.uiState.value.tgBackupStatus,
        )
        assertNull(settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN])
        coVerify(exactly = 0) { sink.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `chat that sent the code is greeted and then stored`() = runTest {
        val sink = tgSinkFake()
        coEvery { sink.getMe("123:abc") } returns Result.success("my_car_bot")
        coEvery { sink.sendMessage("123:abc", 777L, any()) } returns Result.success(Unit)
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        checkToken(vm, "123:abc")
        val code = vm.uiState.value.tgBackupCode!!
        coEvery { sink.findLinkChat("123:abc", code) } returns
            Result.success(com.bydmate.app.data.backup.TelegramChat(777L, "Andy @andy_s"))

        checkToken(vm, "123:abc")

        assertEquals(TgBackupBinding("my_car_bot", "Andy @andy_s"), vm.uiState.value.tgBackupBinding)
        assertNull(vm.uiState.value.tgBackupStatus)
        assertNull(vm.uiState.value.tgBackupCode)
        assertEquals("123:abc", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN])
        assertEquals("my_car_bot", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_BOT_NAME])
        assertEquals("777", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID])
        assertEquals("Andy @andy_s", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_NAME])
        coVerify(exactly = 1) {
            sink.sendMessage("123:abc", 777L, ctxForTg.getString(com.bydmate.app.R.string.settings_tg_backup_greeting))
        }
    }

    @Test
    fun `failed greeting stores nothing and shows the reason`() = runTest {
        val sink = tgSinkFake()
        coEvery { sink.getMe("123:abc") } returns Result.success("my_car_bot")
        coEvery { sink.findLinkChat("123:abc", any()) } returns
            Result.success(com.bydmate.app.data.backup.TelegramChat(777L, "Andy"))
        coEvery { sink.sendMessage(any(), any(), any()) } returns Result.failure(
            com.bydmate.app.data.backup.TelegramSinkException(com.bydmate.app.data.backup.TelegramError.HTTP, 403)
        )
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        checkToken(vm, "123:abc")

        checkToken(vm, "123:abc")

        val reason = ctxForTg.getString(com.bydmate.app.R.string.settings_tg_error_http, "403")
        assertTrue(vm.uiState.value.tgBackupStatus!!, vm.uiState.value.tgBackupStatus!!.contains(reason))
        assertNull(settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN])
        assertNull(settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID])
    }

    @Test
    fun `re-checking a bound bot keeps its chat without a code`() = runTest {
        val sink = tgSinkFake()
        coEvery { sink.getMe("123:abc") } returns Result.success("my_car_bot")
        coEvery { sink.sendMessage("123:abc", 777L, any()) } returns Result.success(Unit)
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN] = "123:abc"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID] = "777"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_NAME] = "Andy"

        checkToken(vm, "123:abc")

        assertNull(vm.uiState.value.tgBackupCode)
        assertEquals("777", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID])
        assertEquals("Andy", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_NAME])
        coVerify(exactly = 0) { sink.findLinkChat(any(), any()) }
        coVerify(exactly = 1) { sink.sendMessage("123:abc", 777L, any()) }
    }

    @Test
    fun `failed chat lookup keeps the previously connected bot untouched`() = runTest {
        val sink = tgSinkFake()
        coEvery { sink.getMe("456:new") } returns Result.success("new_bot")
        coEvery { sink.findLinkChat("456:new", any()) } returns Result.failure(
            com.bydmate.app.data.backup.TelegramSinkException(com.bydmate.app.data.backup.TelegramError.WEBHOOK, 409)
        )
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN] = "123:abc"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_BOT_NAME] = "my_car_bot"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID] = "777"
        checkToken(vm, "456:new")

        checkToken(vm, "456:new")

        val reason = ctxForTg.getString(com.bydmate.app.R.string.settings_tg_error_webhook)
        assertTrue(vm.uiState.value.tgBackupStatus!!, vm.uiState.value.tgBackupStatus!!.contains(reason))
        assertEquals("123:abc", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN])
        assertEquals("my_car_bot", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_BOT_NAME])
        assertEquals("777", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID])
    }

    @Test
    fun `token edited during the check makes its result ignored`() = runTest {
        val sink = tgSinkFake()
        lateinit var vm: SettingsViewModel
        coEvery { sink.getMe("123:abc") } returns Result.success("my_car_bot")
        coEvery { sink.findLinkChat("123:abc", any()) } returns
            Result.success(com.bydmate.app.data.backup.TelegramChat(777L, "Andy"))
        coEvery { sink.sendMessage(any(), any(), any()) } coAnswers {
            vm.updateTgBackupToken("999:other")
            Result.success(Unit)
        }
        vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        checkToken(vm, "123:abc")

        vm.checkTelegramBackup()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.tgBackupChecking)
        assertNull(vm.uiState.value.tgBackupStatus)
        assertNull(vm.uiState.value.tgBackupBinding)
        assertNull(settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN])
        assertNull(settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID])
    }

    @Test
    fun `a connected bot opens on the done step, back on the code step returns to the token`() = runTest {
        // The stored bot is read by the init coroutine, which runs on the first advance.
        val connected = buildViewModel()
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN] = "123:abc"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID] = "777"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_BOT_NAME] = "my_car_bot"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_NAME] = "Andy"
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(TgBackupBinding("my_car_bot", "Andy"), connected.uiState.value.tgBackupBinding)

        val sink = tgSinkFake()
        coEvery { sink.getMe("456:new") } returns Result.success("new_bot")
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        checkToken(vm, "456:new")
        vm.cancelTelegramCode()

        assertNull(vm.uiState.value.tgBackupCode)
        assertNull(vm.uiState.value.tgBackupStatus)
        assertEquals("456:new", vm.uiState.value.tgBackupToken)
    }

    @Test
    fun `disconnect during a running check cancels it and nothing is stored`() = runTest {
        val sink = tgSinkFake()
        coEvery { sink.getMe("123:abc") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        checkToken(vm, "123:abc")
        assertTrue(vm.uiState.value.tgBackupChecking)
        vm.disconnectTelegramBackup()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.tgBackupChecking)
        assertNull(vm.uiState.value.tgBackupStatus)
        assertEquals("", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN])
        coVerify(exactly = 0) { sink.findLinkChat(any(), any()) }
    }

    @Test
    fun `telegram disconnect clears token, chat and bot name`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN] = "123:abc"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID] = "777"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_BOT_NAME] = "my_car_bot"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_NAME] = "Andy"

        vm.disconnectTelegramBackup()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_NAME])
        assertEquals("", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN])
        assertEquals("", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID])
        assertEquals("", settingsDao.map[SettingsRepository.KEY_TG_BACKUP_BOT_NAME])
        assertEquals("", vm.uiState.value.tgBackupToken)
        assertNull(vm.uiState.value.tgBackupBinding)
    }

// --- Send to Telegram for a saved log (#324) ---

    private fun savedLog(bytes: Long = 10): File =
        File.createTempFile("bydmate_logs_", ".txt").apply {
            deleteOnExit()
            java.io.RandomAccessFile(this, "rw").use { it.setLength(bytes) }
        }

    /** A VM whose recorder goes idle -> recording -> stopped at [log]; [connected] stores a bound bot. */
    private fun vmWithStoppedLog(log: File, connected: Boolean = true): SettingsViewModel {
        val recorder = kotlinx.coroutines.flow.MutableStateFlow(com.bydmate.app.diagnostics.LogRecorder.State())
        logRecorderState = recorder
        val vm = buildViewModel()
        if (connected) {
            settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN] = "123:abc"
            settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID] = "777"
            settingsDao.map[SettingsRepository.KEY_TG_BACKUP_BOT_NAME] = "my_car_bot"
            settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_NAME] = "Andy"
        }
        testDispatcher.scheduler.advanceUntilIdle()
        recorder.value = com.bydmate.app.diagnostics.LogRecorder.State(isRecording = true, filePath = log.absolutePath)
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.canSendLogToTelegram)
        recorder.value = com.bydmate.app.diagnostics.LogRecorder.State(
            lastStopped = com.bydmate.app.diagnostics.LogRecorder.Stopped(log.absolutePath, 0),
        )
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `the send button shows only for a saved log with a connected bot`() = runTest {
        val log = savedLog()
        assertTrue(vmWithStoppedLog(log).uiState.value.canSendLogToTelegram)
        assertFalse(vmWithStoppedLog(log, connected = false).uiState.value.canSendLogToTelegram)

        logRecorderState = null
        val idle = buildViewModel()
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_TOKEN] = "123:abc"
        settingsDao.map[SettingsRepository.KEY_TG_BACKUP_CHAT_ID] = "777"
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(idle.uiState.value.canSendLogToTelegram)
    }

    @Test
    fun `sending a saved log uploads it once with the file name as caption`() = runTest {
        val log = savedLog()
        val sink = tgSinkFake()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.success(Unit)
        val vm = vmWithStoppedLog(log)

        vm.sendLogToTelegram()
        vm.sendLogToTelegram()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { sink.sendDocument("123:abc", 777L, log, log.name) }
        assertFalse(vm.uiState.value.logSending)
        assertEquals(
            ctxForTg.getString(com.bydmate.app.R.string.settings_log_tg_sent),
            vm.uiState.value.logSaveStatus,
        )
    }

    @Test
    fun `a failed log upload names the reason`() = runTest {
        val log = savedLog()
        val sink = tgSinkFake()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.failure(
            com.bydmate.app.data.backup.TelegramSinkException(com.bydmate.app.data.backup.TelegramError.NO_NETWORK)
        )
        val vm = vmWithStoppedLog(log)

        vm.sendLogToTelegram()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.logSending)
        val reason = ctxForTg.getString(com.bydmate.app.R.string.settings_tg_error_no_network)
        assertEquals(
            ctxForTg.getString(com.bydmate.app.R.string.settings_log_tg_failed, reason),
            vm.uiState.value.logSaveStatus,
        )
    }

    @Test
    fun `a log over the Telegram limit is not uploaded`() = runTest {
        val log = savedLog(com.bydmate.app.data.backup.TelegramBackupSink.MAX_UPLOAD_BYTES + 1)
        val sink = tgSinkFake()
        val vm = vmWithStoppedLog(log)

        vm.sendLogToTelegram()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { sink.sendDocument(any(), any(), any(), any()) }
        assertEquals(
            ctxForTg.getString(com.bydmate.app.R.string.settings_log_tg_too_big),
            vm.uiState.value.logSaveStatus,
        )
    }

    @Test
    fun `an automatic log upload shows its result under the log row`() = runTest {
        val log = savedLog()
        val sink = tgSinkFake()
        coEvery { sink.sendDocument(any(), any(), any(), any()) } returns Result.success(Unit)
        val vm = vmWithStoppedLog(log)

        logTelegramSender.send(log)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.logSending)
        assertEquals(
            ctxForTg.getString(com.bydmate.app.R.string.settings_log_tg_sent),
            vm.uiState.value.logSaveStatus,
        )
    }

    @Test
    fun `the log auto-send switch is stored and read back`() = runTest {
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.tgLogAutoSendEnabled)

        vm.setTgLogAutoSendEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("true", settingsDao.map[SettingsRepository.KEY_TG_LOG_AUTO_SEND])
        assertTrue(vm.uiState.value.tgLogAutoSendEnabled)
    }

    // --- Polish voice language and the optional text-only assistant -------------------------------------

    @Test
    fun `turning on the legacy agent does not turn on the Polish assistant`() = runTest {
        val assistant = mockk<com.bydmate.app.voice.openclaw.OpenClawAssistant>(relaxed = true)
        val vm = buildViewModel { this.openClawAssistant = assistant }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setAgentEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.agentEnabled)
        coVerify(exactly = 0) { assistant.setEnabled(any()) }
        coVerify(exactly = 0) { assistant.pair() }
        coVerify(exactly = 0) { assistant.ask(any(), any()) }
    }

    @Test
    fun `the assistant opt-in, pairing and disconnect controls reach the assistant`() = runTest {
        val assistant = mockk<com.bydmate.app.voice.openclaw.OpenClawAssistant>(relaxed = true)
        val vm = buildViewModel { this.openClawAssistant = assistant }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.setOpenClawEnabled(true)
        vm.setOpenClawEndpoint("wss://gateway.example.ts.net/")
        vm.pairOpenClaw()
        testDispatcher.scheduler.advanceUntilIdle() // the pair call has run before disconnect cancels it
        vm.resetOpenClawConversation()
        vm.disconnectOpenClaw()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { assistant.setEnabled(true) }
        coVerify(exactly = 1) { assistant.setEndpoint("wss://gateway.example.ts.net/") }
        coVerify(exactly = 1) { assistant.pair() }
        coVerify(exactly = 1) { assistant.resetConversation() }
        coVerify(exactly = 1) { assistant.disconnect() }
    }

    @Test
    fun `cancelling pairing cancels the waiting pair call`() = runTest {
        val assistant = mockk<com.bydmate.app.voice.openclaw.OpenClawAssistant>(relaxed = true)
        val gate = CompletableDeferred<com.bydmate.app.voice.openclaw.OpenClawLinkState>()
        var cancelled = false
        coEvery { assistant.pair() } coAnswers {
            try { gate.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
        }
        val vm = buildViewModel { this.openClawAssistant = assistant }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.pairOpenClaw()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.pairOpenClaw() // a second tap while waiting is ignored
        vm.cancelOpenClawPairing()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { assistant.pair() }
        assertTrue(cancelled)
    }

    @Test
    fun `the connection test sends typed text through the assistant probe, one at a time`() = runTest {
        val probe = mockk<com.bydmate.app.voice.openclaw.AssistantConnectionProbe>(relaxed = true)
        val gate = CompletableDeferred<Unit>()
        coEvery { probe.send("Cześć") } coAnswers { gate.await() }
        val setup = testVoiceLanguageSetup()
        val vm = buildViewModel { this.assistantProbe = probe; this.voiceLanguageSetup = setup }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.sendAssistantTest("Cześć")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.sendAssistantTest("Cześć") // ignored while the first is running
        testDispatcher.scheduler.advanceUntilIdle()
        gate.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { probe.send("Cześć") }
        // Text chat is its own path: it never activates a profile or touches a model.
        coVerify(exactly = 0) { setup.activatePolish() }
        coVerify(exactly = 0) { setup.downloadAsr() }
        coVerify(exactly = 0) { setup.downloadVoice() }
    }

    @Test
    fun `language activation and the Polish downloads are delegated to the setup`() = runTest {
        val setup = testVoiceLanguageSetup()
        val vm = buildViewModel { this.voiceLanguageSetup = setup }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.downloadPolishAsr()
        vm.downloadPolishVoice()
        vm.activatePolish()
        testDispatcher.scheduler.advanceUntilIdle() // the downloads have run before a delete cancels them
        vm.deletePolishAsr()
        vm.deletePolishVoice()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { setup.downloadAsr() }
        coVerify(exactly = 1) { setup.downloadVoice() }
        coVerify(exactly = 1) { setup.activatePolish() }
        coVerify(exactly = 1) { setup.deleteAsr() }
        coVerify(exactly = 1) { setup.deleteVoice() }
    }
}
