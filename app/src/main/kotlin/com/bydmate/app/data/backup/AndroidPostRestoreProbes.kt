package com.bydmate.app.data.backup

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.bydmate.app.camera.BlindSpotPreferences
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.ui.widget.WidgetController
import com.bydmate.app.ui.widget.WidgetPreferences
import com.bydmate.app.voice.PolishModelManager
import com.bydmate.app.voice.TtsModelManager
import com.bydmate.app.voice.TtsVoiceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [PostRestoreProbes] over the real system: the same sources the features themselves read. */
class AndroidPostRestoreProbes(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val helperBootstrap: HelperBootstrap,
    private val helperClient: HelperClient,
    private val polishModelManager: PolishModelManager,
    private val ttsModelManager: TtsModelManager,
) : PostRestoreProbes {

    override suspend fun toggles(): RestoredToggles {
        // Voice/TTS: the "voice" prefs mirror is what VoiceGate and the key service read.
        val voice = context.getSharedPreferences("voice", Context.MODE_PRIVATE)
        return RestoredToggles(
            widget = WidgetPreferences(context).isEnabled(),
            blindSpot = BlindSpotPreferences(context).enabled,
            voice = voice.getBoolean(SettingsRepository.KEY_VOICE_ENABLED, false),
            agent = settingsRepository.isAgentEnabled(),
            tts = voice.getBoolean(SettingsRepository.KEY_TTS_ENABLED, false),
            ttsOffline = true,
            ttsVoiceId = TtsVoiceCatalog.GOSIA.id,
            nativeAssistantDisabled =
                settingsRepository.getString(SettingsRepository.KEY_DISABLE_NATIVE_ASSISTANT, "") == "true",
        )
    }

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    override fun hasPermission(name: String): Boolean =
        context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    override fun asrModelReady(): Boolean = polishModelManager.isReady()

    override fun ttsVoiceReady(voiceId: String): Boolean = ttsModelManager.isReady(TtsVoiceCatalog.byId(voiceId))

    override suspend fun grantOverlayViaDaemon(): Boolean =
        helperBootstrap.ensureRunning() && helperClient.grantOverlayPermission()

    override suspend fun attachWidget() = withContext(Dispatchers.Main) { WidgetController.attach(context, "post_restore") }
}
