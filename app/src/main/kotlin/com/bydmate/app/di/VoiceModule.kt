package com.bydmate.app.di

import android.content.Context
import android.media.AudioManager
import com.bydmate.app.agent.ActionConfirmer
import com.bydmate.app.agent.ActionExecutor
import com.bydmate.app.agent.ActionRegistry
import com.bydmate.app.agent.ActionValidating
import com.bydmate.app.agent.DispatcherActionExecutor
import com.bydmate.app.agent.OverlayActionConfirmer
import com.bydmate.app.agent.RegistryActionValidator
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.WriteAllowlist
import com.bydmate.app.platform.LegacyHeadUnit
import com.bydmate.app.service.TailscaleStartup
import com.bydmate.app.service.TrackingService
import com.bydmate.app.voice.*
import com.bydmate.app.voice.openclaw.ActionLedger
import com.bydmate.app.voice.openclaw.AliasProposalSinkHolder
import com.bydmate.app.voice.openclaw.AssistantActionHandler
import com.bydmate.app.voice.openclaw.AssistantConnectionProbe
import com.bydmate.app.voice.openclaw.LedgerAssistantActionHandler
import com.bydmate.app.voice.openclaw.OpenClawAssistant
import com.bydmate.app.voice.openclaw.OpenClawClient
import com.bydmate.app.voice.openclaw.OpenClawClientChat
import com.bydmate.app.voice.openclaw.OpenClawConnectionCoordinator
import com.bydmate.app.voice.openclaw.OpenClawConversationStore
import com.bydmate.app.voice.openclaw.OpenClawIdentityStore
import com.bydmate.app.voice.openclaw.OpenClawPrefs
import com.bydmate.app.voice.openclaw.SharedPreferencesOpenClawPrefs
import com.bydmate.app.voice.openclaw.StoredOpenClawCredentials
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.delay

/** Hands [ProfileAsrEngine] to [PolishModelManager] after both exist: the engine needs the manager's
 *  files and the manager needs the engine to close native objects before touching them. */
internal class LazyAssetCoordinator(private val engine: Lazy<ProfileAsrEngine>) : NativeAssetCoordinator {
    override suspend fun <T> withAssetsReleased(language: VoiceLanguage, block: suspend () -> T): T =
        engine.get().withAssetsReleased(language, block)
}
@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {
    @Provides @Singleton
    fun provideVoiceProfileStore(@ApplicationContext ctx: Context) = VoiceProfileStore(ctx)

    /** Read once per session by [VoiceController]; a later settings change cannot mix languages. */
    @Provides @Singleton
    fun provideSessionProfile(store: VoiceProfileStore): () -> VoiceSessionProfile = { store.captureSession() }

    @Provides @Singleton
    internal fun providePolishModelManager(
        @ApplicationContext ctx: Context,
        http: OkHttpClient,
        vad: SharedVadModelManager,
        engine: Lazy<ProfileAsrEngine>,
    ) = PolishModelManager(ctx, http, vad = vad, coordinator = LazyAssetCoordinator(engine))

    @Provides @Singleton
    internal fun provideProfileAsrEngine(
        store: VoiceProfileStore,
        polish: PolishModelManager,
        guard: AsrLoadGuard,
    ): ProfileAsrEngine = ProfileAsrEngine(
        store,
        mapOf(
            VoiceLanguage.PL to ParakeetAsrEngine(polish, loadGuard = guard, warmVad = LegacyHeadUnit.isAndroid10),
        ),
    )

    @Provides @Singleton
    internal fun provideProfileSwitcher(engine: ProfileAsrEngine): ProfileSwitcher =
        ProfileSwitcher { target, readiness -> engine.activate(target, readiness) }

    @Provides @Singleton
    internal fun provideAssetCoordinator(engine: ProfileAsrEngine): NativeAssetCoordinator = engine

    @Provides @Singleton
    fun provideLegacyRussianAssetCleanup(
        @ApplicationContext ctx: Context,
    ) = LegacyRussianAssetCleanup(
        filesRoot = ctx.filesDir,
        // No retired Russian native engine remains in this graph. Do not release the active
        // Polish engine merely to delete unrelated retired directories.
        releaseRussianAssets = {},
        warningSink = { warning -> android.util.Log.w("LegacyRuCleanup", warning.toString()) },
    )

    @Provides @Singleton
    fun provideOpenClawIdentityStore(@ApplicationContext ctx: Context) = OpenClawIdentityStore.create(ctx)

    @Provides @Singleton
    fun provideOpenClawConversationStore(@ApplicationContext ctx: Context) = OpenClawConversationStore.create(ctx)

    @Provides @Singleton
    fun provideOpenClawPrefs(@ApplicationContext ctx: Context): OpenClawPrefs = SharedPreferencesOpenClawPrefs(ctx)

    @Provides @Singleton
    fun provideActionRegistry(allowlist: WriteAllowlist) = ActionRegistry(allowlist = allowlist)

    @Provides @Singleton
    fun provideActionValidator(registry: ActionRegistry): ActionValidating = RegistryActionValidator(registry)

    /** Kept across reconnects, opt-out and restarts; the identity's device id namespaces it. */
    @Provides @Singleton
    fun provideActionLedger(@ApplicationContext ctx: Context, identity: OpenClawIdentityStore) =
        ActionLedger(File(ctx.noBackupFilesDir, OpenClawIdentityStore.DIRECTORY_NAME), { identity.identity()?.deviceId })

    @Provides @Singleton
    fun provideActionConfirmer(@ApplicationContext ctx: Context): ActionConfirmer = OverlayActionConfirmer(ctx)

    @Provides @Singleton
    fun provideActionExecutor(
        registry: ActionRegistry,
        dispatcher: ActionDispatcher,
        confirmer: ActionConfirmer,
        gate: VoiceGate,
    ): ActionExecutor = DispatcherActionExecutor(registry, dispatcher, confirmer) { gate.vehicleSnapshot() }

    /** Bound by [VoiceAliasCoordinator] when it is created; [provideAssistantActionHandler] depends on it. */
    @Provides @Singleton
    fun provideAliasProposalSinkHolder() = AliasProposalSinkHolder()

    @Provides @Singleton
    @Suppress("LongParameterList") // Explicit Hilt dependencies include the coordinator's required holder binding.
    fun provideAssistantActionHandler(
        registry: ActionRegistry,
        validator: ActionValidating,
        ledger: ActionLedger,
        executor: ActionExecutor,
        aliasSink: AliasProposalSinkHolder,
        // Never read: asking for it makes Hilt build the coordinator, which binds [aliasSink], before this
        // handler can advertise teaching. It reaches the handler back through a Provider, so there is no cycle.
        @Suppress("UNUSED_PARAMETER") aliasCoordinator: VoiceAliasCoordinator,
    ): AssistantActionHandler = LedgerAssistantActionHandler(
        registry, validator, ledger, executor, aliasSink = aliasSink,
        log = { android.util.Log.i("AssistantActions", it) },
    )

    /** The Polish profile's only online path. Its own transport, identity and conversation: no
     *  OkHttp client, key or connection of the Russian providers is shared with it. */
    @Provides @Singleton
    fun provideOpenClawAssistant(
        prefs: OpenClawPrefs,
        identity: OpenClawIdentityStore,
        conversation: OpenClawConversationStore,
        actions: AssistantActionHandler,
    ) = OpenClawAssistant(
        prefs = prefs,
        chatFor = { endpoint ->
            OpenClawClientChat(OpenClawClient(endpoint, identity, conversation, attachments = actions))
        },
        credentials = StoredOpenClawCredentials(identity, conversation, onForget = actions::onIdentityForgotten),
        actionLifecycle = actions,
    )

    @Provides @Singleton
    internal fun provideOpenClawConnectionCoordinator(
        assistant: OpenClawAssistant,
    ) = OpenClawConnectionCoordinator(
        assistantStatus = assistant.status,
        reconnectIfEligible = assistant::reconnectIfEligible,
        reconnectManually = assistant::reconnect,
        requestTailscale = TailscaleStartup::requestConnection,
        retryDelay = { delay(it) },
    )

    @Provides @Singleton
    fun provideVoiceAssistant(assistant: OpenClawAssistant): VoiceAssistant = assistant

    /** The settings connection test: the same assistant, spoken only by the local Polish voice. */
    @Provides @Singleton
    fun provideAssistantConnectionProbe(
        assistant: OpenClawAssistant,
        @Named("polishTts") polishVoice: TtsEngine,
        setup: VoiceLanguageSetup,
    ) = AssistantConnectionProbe(assistant, polishVoice) { setup.state.value.voiceReady }

    @Provides @Singleton
    fun provideTtsModelManager(@ApplicationContext ctx: Context, http: OkHttpClient) =
        TtsModelManager(ctx, http)

    @Provides @Singleton
    fun provideSharedVadModelManager(@ApplicationContext ctx: Context, http: OkHttpClient) =
        SharedVadModelManager(ctx, http)

    @Provides @Singleton
    fun provideAsrLoadGuard(@ApplicationContext ctx: Context) = AsrLoadGuard(ctx)

    @Named("ttsLoadGuard") @Provides @Singleton
    fun provideTtsLoadGuard(@ApplicationContext ctx: Context): AsrLoadGuard =
        AsrLoadGuard(ctx.getSharedPreferences("tts_load_guard", Context.MODE_PRIVATE))

    @Provides @Singleton
    internal fun provideContinuousAsr(engine: ProfileAsrEngine): ContinuousAsr = engine

    /** Always Gosia, always local: speaks the assistant connection test without activating Polish. */
    @Named("polishTts") @Provides @Singleton
    fun providePolishTtsEngine(
        mm: TtsModelManager,
        @ApplicationContext ctx: Context,
        @Named("ttsLoadGuard") ttsGuard: AsrLoadGuard,
    ): TtsEngine = SherpaTtsEngine(
        mm,
        selectedVoice = { TtsVoiceCatalog.defaultFor() },
        rate = { ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getFloat("tts_rate", 1.0f) },
        liveliness = { ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getInt("tts_liveliness", 33) },
        loadGuard = ttsGuard,
    )

    @Provides @Singleton
    fun provideTtsEngine(
        mm: TtsModelManager,
        @ApplicationContext ctx: Context,
        @Named("ttsLoadGuard") ttsGuard: AsrLoadGuard,
    ): TtsEngine = SherpaTtsEngine(
        mm,
        selectedVoice = { TtsVoiceCatalog.GOSIA },
        rate = { ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getFloat("tts_rate", 1.0f) },
        liveliness = { ctx.getSharedPreferences("voice", Context.MODE_PRIVATE).getInt("tts_liveliness", 33) },
        loadGuard = ttsGuard,
    )

    @Provides @Singleton
    fun provideAgentIdentity(@ApplicationContext context: Context): () -> AgentIdentity = {
        val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        AgentIdentity(
            name = prefs.getString("agent_name", "").orEmpty().trim(),
            persona = AgentPersona.fromId(prefs.getString("agent_persona", null)),
            gender = if (prefs.getString("agent_gender", "m") == "f") TtsGender.FEMALE else TtsGender.MALE,
        )
    }

    @Provides @Singleton
    fun provideAudioCapture(@ApplicationContext ctx: Context) =
        AudioCapture(
            ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE),
        )

    @Provides @Singleton
    fun provideVoiceEarcon() = VoiceEarcon()

    @Provides @Singleton
    fun provideVoiceGate(@ApplicationContext ctx: Context): VoiceGate = object : VoiceGate {
        override fun isEnabled(): Boolean =
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getBoolean("voice_enabled", false)
        override fun vehicleSnapshot(): DiParsData? = TrackingService.lastData.value
        override fun snapshotAgeMs(): Long? =
            TrackingService.lastDataAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it }
        override fun rangeKm(): Double? = TrackingService.lastRangeKm.value
        override fun ttsEnabled(): Boolean =
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getBoolean("tts_enabled", false)
        override fun closeAfterCommand(): Boolean =
            ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
                .getBoolean("close_after_command", false)
    }

    @Provides @Singleton
    fun provideSelfEchoFilter() = SelfEchoFilter()
}
