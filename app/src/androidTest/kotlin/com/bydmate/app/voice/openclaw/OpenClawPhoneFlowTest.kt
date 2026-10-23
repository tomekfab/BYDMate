package com.bydmate.app.voice.openclaw

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.di.VoiceModule
import com.bydmate.app.voice.AssistantReply
import com.bydmate.app.voice.PolishVoiceReplies.Problem
import com.bydmate.app.voice.SherpaTtsEngine
import com.bydmate.app.voice.TtsModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Plan step 7, phone-first: the assistant connection test on a real Android device or emulator,
 * with no car, no microphone, no speech model and no Polish profile. The production
 * [OpenClawAssistant] and [AssistantConnectionProbe] run over the real Keystore-backed identity
 * store and real preferences. Network failures come from a controlled transport (a scripted
 * [OpenClawChat]) or from the real client pointed at a closed local port, so none of these tests
 * needs the Gateway. The live check against the Mac Mini is a separate manual run.
 *
 * Production data is never touched: test-only Keystore alias, directory and preferences file.
 */
@RunWith(AndroidJUnit4::class)
class OpenClawPhoneFlowTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "bydmate_openclaw_phone_flow_test"
    private lateinit var dir: File
    private val prefsName = "openclaw_phone_flow_test"

    private class Scripted : OpenClawChat {
        override val linkState: StateFlow<OpenClawLinkState> = MutableStateFlow(OpenClawLinkState.Ready)
        val asked = mutableListOf<String>()
        @Volatile var result: OpenClawTurnResult = OpenClawTurnResult.Answer("Cześć, tu Bob.")
        override suspend fun connect() = OpenClawLinkState.Ready
        override suspend fun pair() = OpenClawLinkState.Ready
        override suspend fun ask(text: String, onDelta: (String) -> Unit): OpenClawTurnResult { asked += text; return result }
        override suspend fun resendUncertain(onDelta: (String) -> Unit) = result
        override suspend fun cancel() = Unit
        override suspend fun resetConversation() = Unit
        override suspend fun disconnect() = Unit
    }

    private fun identityStore() = OpenClawIdentityStore(dir, KeystoreSecretCipher(alias))
    private fun conversationStore() = OpenClawConversationStore(dir)
    private fun prefs() = SharedPreferencesOpenClawPrefs(context.getSharedPreferences(prefsName, Context.MODE_PRIVATE))
    private fun credentials() = StoredOpenClawCredentials(identityStore(), conversationStore())

    private fun assistant(prefs: OpenClawPrefs = prefs(), chat: OpenClawChat = Scripted()) =
        OpenClawAssistant(prefs, { chat }, credentials())

    /** The production engine with no Gosia installed: its model directory does not exist. */
    private fun engineWithoutGosia() = SherpaTtsEngine(TtsModelManager(context, OkHttpClient()))

    @Before fun setUp() {
        dir = File(context.noBackupFilesDir, "openclaw-phone-flow-test")
        identityStore().clear()
        dir.deleteRecursively()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        Log.i(TAG, "device sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} abi=${Build.SUPPORTED_ABIS.first()}")
    }

    @After fun tearDown() {
        identityStore().clear()
        dir.deleteRecursively()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun optInIsOffByDefaultAndSeparateFromEveryRussianSetting() = runBlocking {
        val voiceBefore = context.getSharedPreferences("voice", Context.MODE_PRIVATE).all.toMap()
        val chat = Scripted()
        val assistant = assistant(chat = chat)

        val reply = assistant.ask("Cześć") as AssistantReply.Problem

        assertEquals(Problem.AI_DISABLED, reply.problem)
        assertTrue("nothing was sent", chat.asked.isEmpty())
        assertFalse(prefs().isEnabled())

        assistant.setEnabled(true)

        assertTrue(prefs().isEnabled())
        assertEquals("the Russian voice and agent preferences are untouched", voiceBefore,
            context.getSharedPreferences("voice", Context.MODE_PRIVATE).all.toMap())
    }

    @Test fun typedPolishTextGetsAnAnswerWithoutAnyModelProfileMicrophoneOrCar() = runBlocking {
        val chat = Scripted()
        prefs().apply { setEnabled(true); setEndpointText("wss://gateway.example.ts.net/") }
        val probe = AssistantConnectionProbe(assistant(chat = chat), engineWithoutGosia()) { false }

        probe.send("Cześć, kim jesteś?")

        val state = probe.probe.value
        assertEquals(listOf("Cześć, kim jesteś?"), chat.asked)
        assertEquals("Cześć, tu Bob.", state.reply)
        assertNull(state.error)
        assertEquals("text only until Gosia is installed", AssistantConnectionProbe.AUDIO_NO_VOICE, state.audio)

        val types = AssistantConnectionProbe::class.java.constructors.single().parameterTypes.map { it.name }
        assertTrue("no vehicle or microphone collaborator: $types",
            types.none { "ActionDispatcher" in it || "AudioCapture" in it || "VehicleApi" in it })
    }

    @Test fun controlledFailuresAreDeterministicAndShownInPolish() = runBlocking {
        prefs().apply { setEnabled(true); setEndpointText("wss://gateway.example.ts.net/") }
        val cases = listOf(
            OpenClawTurnResult.Failed(OpenClawFailure.NetworkUnavailable) to PolishAssistantText.failure(OpenClawFailure.NetworkUnavailable),
            OpenClawTurnResult.Failed(OpenClawFailure.TlsRejected) to PolishAssistantText.failure(OpenClawFailure.TlsRejected),
            OpenClawTurnResult.NeedsApproval("req-1") to PolishAssistantText.APPROVAL,
            OpenClawTurnResult.Revoked to PolishAssistantText.REVOKED,
            OpenClawTurnResult.Uncertain to PolishAssistantText.UNCERTAIN,
        )
        for ((result, line) in cases) {
            val chat = Scripted().also { it.result = result }
            val probe = AssistantConnectionProbe(assistant(chat = chat), engineWithoutGosia()) { false }
            probe.send("Cześć")
            assertEquals(result.toString(), line, probe.probe.value.error)
            assertNull(probe.probe.value.reply)
            assertEquals("never retried", 1, chat.asked.size)
        }
    }

    @Test fun theRealClientFailsFastAndInPolishWhenNothingListens() = runBlocking {
        // The production wiring (VoiceModule) over a closed local port: a real connect attempt that
        // fails the same way every time, with no Gateway and no other provider.
        prefs().apply { setEnabled(true); setEndpointText("wss://127.0.0.1:9/") }
        val production = VoiceModule.provideOpenClawAssistant(prefs(), identityStore(), conversationStore())
        val probe = AssistantConnectionProbe(production, engineWithoutGosia()) { false }

        probe.send("Cześć")

        val error = probe.probe.value.error
        assertNotNull(error)
        assertTrue(error, error == PolishAssistantText.failure(OpenClawFailure.NetworkUnavailable) ||
            error == PolishAssistantText.failure(OpenClawFailure.Timeout))
        assertNull(probe.probe.value.reply)
        assertFalse("a failed attempt does not create a pairing", credentials().exist())
    }

    @Test fun pairingSurvivesARestartAndDisconnectForgetsIt() = runBlocking {
        val first = identityStore()
        first.getOrCreateIdentity()
        first.saveDeviceToken("test-token-not-a-secret")
        conversationStore().sessionKey("pl")
        assertTrue(credentials().exist())

        // A new process builds new objects over the same files and the same Keystore key.
        val assistant = assistant()
        assertEquals(OpenClawLinkState.Disconnected, assistant.status.value.link)

        assistant.disconnect()

        assertFalse(credentials().exist())
        assertNull(identityStore().deviceToken())
        assertFalse("the conversation file is gone", File(dir, OpenClawConversationStore.FILE_NAME).exists())
        assertEquals(OpenClawLinkState.Unpaired, assistant.status.value.link)
    }

    @Test fun theAddressCannotBeChangedWhileADeviceIsPaired() = runBlocking {
        prefs().setEndpointText("wss://gateway.example.ts.net/")
        identityStore().getOrCreateIdentity()
        identityStore().saveDeviceToken("test-token-not-a-secret")
        val assistant = assistant()

        assertFalse(assistant.setEndpoint("wss://other.example.ts.net/"))
        assertEquals("wss://gateway.example.ts.net/", prefs().endpointText())

        assistant.disconnect()
        assertTrue(assistant.setEndpoint("wss://other.example.ts.net/"))
    }

    private companion object {
        const val TAG = "OpenClawPhoneFlow"
    }
}
