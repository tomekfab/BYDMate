package com.bydmate.app.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bydmate.app.BYDMateApp
import com.bydmate.app.voice.openclaw.OpenClawIdentityStore
import com.bydmate.app.voice.openclaw.SharedPreferencesOpenClawPrefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Upgrade fixture. Its methods are deliberately separate instrumentation selectors: the host
 * force-stops between seeding and assertion, and always invokes [restoreSnapshot] explicitly.
 * The private snapshot contains only preferences that this fixture changes, their type/absence,
 * and the previous AppCompat locale. It never contains OpenClaw identity bytes or any secret.
 */
@RunWith(AndroidJUnit4::class)
class PolishOnlyUpgradeTest {

    @Test fun snapshotAndSeed() {
        val target = targetContext()
        val fixture = fixtureFile(target)
        assertFalse("an un-restored upgrade fixture already exists", fixture.exists())
        val legacyCase = legacyCase()
        val snapshot = JSONObject().apply {
            put("legacyCase", legacyCase)
            put("localeTags", AppCompatDelegate.getApplicationLocales().toLanguageTags())
            put("voiceProfile", snapshotPrefs(target.getSharedPreferences(VOICE_PROFILE, Context.MODE_PRIVATE), PROFILE_KEYS))
            put("voice", snapshotPrefs(target.getSharedPreferences(VOICE, Context.MODE_PRIVATE), VOICE_KEYS))
            put("openClaw", snapshotPrefs(target.getSharedPreferences(OPENCLAW, Context.MODE_PRIVATE), OPENCLAW_KEYS))
            // A SHA-256 digest proves the private identity directory stays byte-for-byte unchanged
            // without storing or emitting its contents.
            put("identityDigest", directoryDigest(File(target.noBackupFilesDir, OpenClawIdentityStore.DIRECTORY_NAME)))
        }
        writeAtomically(fixture, snapshot.toString())

        val profile = target.getSharedPreferences(VOICE_PROFILE, Context.MODE_PRIVATE)
        profile.edit().apply {
            remove(KEY_PENDING_LANGUAGE)
            when (legacyCase) {
                CASE_MISSING -> remove(KEY_ACTIVE_LANGUAGE)
                CASE_RUSSIAN -> putString(KEY_ACTIVE_LANGUAGE, "ru")
                CASE_UNKNOWN -> putString(KEY_ACTIVE_LANGUAGE, "unknown")
                CASE_WRONG_TYPE -> putInt(KEY_ACTIVE_LANGUAGE, 7)
            }
        }.commitOrThrow("seed voice profile")
        target.getSharedPreferences(VOICE, Context.MODE_PRIVATE).edit()
            .putString("tts_voice", "dmitri")
            .putString("tts_source", "gemini")
            .putBoolean("voice_enabled", false)
            .putBoolean("tts_enabled", false)
            .commitOrThrow("seed voice settings")
        target.getSharedPreferences(OPENCLAW, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", false)
            .commitOrThrow("seed OpenClaw opt-in")
    }

    @Test fun assertMigrated() {
        val target = targetContext()
        val snapshot = readSnapshot(target)
        assertEquals("fixture case", legacyCase(), snapshot.getString("legacyCase"))

        // The host force-stops after seeding and starts this selector only after installing the
        // candidate. Android therefore constructs BYDMateApp afresh; this assertion reads the
        // persisted effect of that real startup path and calls no fixture-only migration hook.
        assertTrue("candidate must enter the real application startup", target.applicationContext is BYDMateApp)
        val profilePrefs = target.getSharedPreferences(VOICE_PROFILE, Context.MODE_PRIVATE)
        val savedLanguage = try {
            profilePrefs.getString(KEY_ACTIVE_LANGUAGE, null)
        } catch (failure: ClassCastException) {
            throw AssertionError("legacy voice language type was not migrated safely at startup", failure)
        }
        assertEquals("startup migration must persist Polish", "pl", savedLanguage)
        val profile = VoiceProfileStore(target)
        val language = try {
            profile.activeLanguage()
        } catch (failure: ClassCastException) {
            throw AssertionError("legacy voice language type was not migrated safely", failure)
        }
        assertEquals("voice profile must resolve to Polish", VoiceLanguage.PL, language)

        val voice = target.getSharedPreferences(VOICE, Context.MODE_PRIVATE)
        assertEquals("legacy TTS selection must migrate to Gosia", "gosia", voice.getString("tts_voice", null))
        assertFalse("restored online TTS must not remain selectable", voice.getString("tts_source", null) in ONLINE_TTS_SOURCES)
        assertFalse("disabled voice must stay disabled", voice.getBoolean("voice_enabled", true))
        assertFalse("disabled TTS must stay disabled", voice.getBoolean("tts_enabled", true))

        val openClaw = target.getSharedPreferences(OPENCLAW, Context.MODE_PRIVATE)
        assertFalse("OpenClaw opt-in must stay disabled", openClaw.getBoolean("enabled", true))
        assertEquals(
            "OpenClaw identity must remain byte-for-byte unchanged",
            snapshot.getString("identityDigest"),
            directoryDigest(File(target.noBackupFilesDir, OpenClawIdentityStore.DIRECTORY_NAME)),
        )
    }

    @Test fun restoreSnapshot() {
        val target = targetContext()
        val fixture = fixtureFile(target)
        val snapshot = readSnapshot(target)
        restorePrefs(target.getSharedPreferences(VOICE_PROFILE, Context.MODE_PRIVATE), snapshot.getJSONObject("voiceProfile"))
        restorePrefs(target.getSharedPreferences(VOICE, Context.MODE_PRIVATE), snapshot.getJSONObject("voice"))
        restorePrefs(target.getSharedPreferences(OPENCLAW, Context.MODE_PRIVATE), snapshot.getJSONObject("openClaw"))
        val localeTags = snapshot.getString("localeTags")
        var restoredLocaleTags: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTags))
            restoredLocaleTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        }
        assertEquals("restore AppCompat locale synchronously", localeTags, restoredLocaleTags)
        assertTrue("delete restored fixture", fixture.delete())
    }

    private fun legacyCase(): String = InstrumentationRegistry.getArguments().getString("legacyCase")
        ?.takeIf { it in CASES }
        ?: throw AssertionError("legacyCase must be one of ${CASES.joinToString()}")

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readSnapshot(context: Context): JSONObject {
        val fixture = fixtureFile(context)
        assertTrue("snapshot fixture is missing", fixture.isFile)
        return JSONObject(fixture.readText())
    }

    private fun fixtureFile(context: Context) = File(context.noBackupFilesDir, FIXTURE_NAME)

    private fun snapshotPrefs(prefs: SharedPreferences, keys: List<String>): JSONObject = JSONObject().apply {
        keys.forEach { key ->
            val value = prefs.all[key]
            put(key, JSONObject().apply {
                put("present", prefs.contains(key))
                if (prefs.contains(key)) putValue(value)
            })
        }
    }

    private fun JSONObject.putValue(value: Any?) {
        when (value) {
            is String -> put("type", "string").put("value", value)
            is Boolean -> put("type", "boolean").put("value", value)
            is Int -> put("type", "int").put("value", value)
            is Long -> put("type", "long").put("value", value)
            is Float -> put("type", "float").put("value", value.toString())
            is Set<*> -> put("type", "stringSet").put("value", JSONArray(value.toList()))
            null -> put("type", "null")
            else -> throw AssertionError("unsupported SharedPreferences value type")
        }
    }

    private fun restorePrefs(prefs: SharedPreferences, snapshot: JSONObject) {
        prefs.edit().apply {
            val keys = snapshot.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val saved = snapshot.getJSONObject(key)
                if (!saved.getBoolean("present")) {
                    remove(key)
                    continue
                }
                when (saved.getString("type")) {
                    "string" -> putString(key, saved.getString("value"))
                    "boolean" -> putBoolean(key, saved.getBoolean("value"))
                    "int" -> putInt(key, saved.getInt("value"))
                    "long" -> putLong(key, saved.getLong("value"))
                    "float" -> putFloat(key, saved.getString("value").toFloat())
                    "stringSet" -> {
                        val values = saved.getJSONArray("value")
                        putStringSet(key, (0 until values.length()).mapTo(linkedSetOf()) { values.getString(it) })
                    }
                    "null" -> remove(key)
                    else -> throw AssertionError("unsupported saved preference type")
                }
            }
        }.commitOrThrow("restore snapshot")
    }

    private fun SharedPreferences.Editor.commitOrThrow(operation: String) {
        assertTrue(operation, commit())
    }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        assertFalse("an interrupted fixture snapshot already exists", temporary.exists())
        FileOutputStream(temporary).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        assertTrue("publish fixture snapshot", temporary.renameTo(file))
    }

    private fun directoryDigest(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!dir.exists()) return "absent"
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.forEach { file ->
            digest.update(file.relativeTo(dir).path.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(file.readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val VOICE_PROFILE = "voice_profile"
        const val VOICE = "voice"
        const val OPENCLAW = SharedPreferencesOpenClawPrefs.FILE_NAME
        const val FIXTURE_NAME = "polish-only-upgrade-fixture.json"
        const val CASE_MISSING = "missing"
        const val CASE_RUSSIAN = "ru"
        const val CASE_UNKNOWN = "unknown"
        const val CASE_WRONG_TYPE = "wrong-type"
        val CASES = setOf(CASE_MISSING, CASE_RUSSIAN, CASE_UNKNOWN, CASE_WRONG_TYPE)
        const val KEY_ACTIVE_LANGUAGE = "active_language"
        const val KEY_PENDING_LANGUAGE = "pending_language"
        val PROFILE_KEYS = listOf(KEY_ACTIVE_LANGUAGE, KEY_PENDING_LANGUAGE)
        val VOICE_KEYS = listOf("tts_voice", "tts_source", "voice_enabled", "tts_enabled")
        val OPENCLAW_KEYS = listOf("enabled")
        val ONLINE_TTS_SOURCES = setOf("gemini", "minimax")
    }
}
