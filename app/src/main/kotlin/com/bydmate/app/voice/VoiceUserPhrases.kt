package com.bydmate.app.voice

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.agent.AgentCommandCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** A built-in offline command the user may give own phrases: stable catalog id, display name
 *  and the dispatch string; [nameFor] gives the Polish name for the Polish profile. */
data class VoiceUserCommand(val id: String, val name: String, val command: String) {
    /** [name] for a retained legacy value; the Polish action name for Polish (falls back to [name] for an id the
     *  Polish module does not know). */
    fun nameFor(language: VoiceLanguage): String =
        if (language == VoiceLanguage.PL) PolishVoiceReplies.commandName(id) ?: name else name
}

/** Where a persisted alias points. [Unavailable] keeps data this build cannot execute: [retained] is
 *  the alias entry as JSON (without its phrase), written back unchanged on the next Save. */
sealed interface AliasTarget {
    /** A built-in vehicle command; [value] is the fixed value of a ranged command (null for fixed ones). */
    data class Vehicle(val commandId: String, val value: Int? = null) : AliasTarget
    data class NavigateQuery(val query: String) : AliasTarget
    /** "home" or "work". */
    data class NavigateShortcut(val which: String) : AliasTarget
    data class Unavailable(val kind: String, val commandId: String?, val retained: String) : AliasTarget
}

data class VoiceAlias(val phrase: String, val target: AliasTarget) {
    val executable: Boolean get() = target !is AliasTarget.Unavailable
}

sealed interface SaveResult {
    data object Saved : SaveResult
    data class Rejected(val check: VoiceUserPhrases.Check) : SaveResult
    /** The synchronous `commit()` failed; nothing was published. */
    data object PersistFailed : SaveResult
}

sealed interface ReplaceResult {
    data object Replaced : ReplaceResult
    /** The alias to replace is not (or no longer) in the store; nothing was written. */
    data object NotFound : ReplaceResult
    data class Rejected(val check: VoiceUserPhrases.Check) : ReplaceResult
    data object PersistFailed : ReplaceResult
}

/**
 * The user's own phrases (like the competitor's `voice_cmd_overrides`), kept as a versioned alias
 * document in SharedPreferences("voice") under [KEY]:
 * `{"version":2,"aliases":[{"phrase","kind":"vehicle|navigate",...}],"unreadable"?,"retained"?}`.
 * The pre-v2 shape (`commandId → [phrases]`) still loads. A phrase matches only when it is the whole
 * utterance, word for word after [VoicePhrase.isExact] normalization. Loading never writes: unknown
 * ids, unknown kinds and unreadable text are held in memory (reserving their phrases, never
 * executable) and written back on the next explicit Save. Every save is a synchronous `commit()`
 * under one lock; state is published only after the commit succeeds. Null [prefs] keeps everything in
 * memory (unit tests).
 */
@Singleton
@Suppress("TooManyFunctions") // One locked preference store owns compatibility APIs, commits and state publication.
class VoiceUserPhrases(private val prefs: SharedPreferences? = null) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** What one load produced and what a save writes back. */
    private class Doc(
        val aliases: List<VoiceAlias>,
        val unreadable: String?,
        /** Alias entries without a usable phrase, kept verbatim. */
        val retained: List<String>,
    )

    private val lock = Any()
    @Volatile private var doc: Doc = load()
    private val _aliases = MutableStateFlow(doc.aliases)
    private val _phrases = MutableStateFlow(phraseMap(doc.aliases))

    /** Every alias of every kind, in saved order. */
    val aliases: StateFlow<List<VoiceAlias>> = _aliases.asStateFlow()

    /** The phrase-facing view: commandId → phrases of the aliases with no fixed value. */
    val phrases: StateFlow<Map<String, List<String>>> = _phrases.asStateFlow()

    /** Raw preference text this build could not read, kept for the next Save; null when readable. */
    val retainedUnreadable: String? get() = doc.unreadable

    sealed interface Check {
        data object Ok : Check
        data object Empty : Check
        data object TooLong : Check
        data object TooMany : Check
        /** The phrase already belongs to [owner]: another command's name or an automation's. */
        data class InUse(val owner: String) : Check
    }

    /** The alias whose phrase is the whole of [transcript]; check [VoiceAlias.executable] before acting. */
    fun matchAlias(transcript: String): VoiceAlias? =
        _aliases.value.firstOrNull { VoicePhrase.isExact(transcript, it.phrase) }

    /** Built-in fixed command whose user phrase is the whole of [transcript] (the old phrase-only
     *  API; ranged, destination and unavailable aliases are reached through [matchAlias]). */
    fun match(transcript: String): VoiceUserCommand? {
        val target = matchAlias(transcript)?.target as? AliasTarget.Vehicle ?: return null
        return target.takeIf { it.value == null }?.let { byId[it.commandId] }
    }

    /** Normalized phrase ([VoicePhrase.normalize]) → owner name, for the automation editor's
     *  collision check. Unavailable aliases reserve their phrase too. */
    fun owners(): Map<String, String> = buildMap {
        for (alias in _aliases.value) putIfAbsent(VoicePhrase.normalize(alias.phrase), ownerName(alias.target))
    }

    /** Whether [phrase] may be added to [commandId]. [automationPhrases] maps normalized
     *  automation phrases to rule names: automations are resolved first, so a clash would
     *  never reach the command. */
    fun check(commandId: String, phrase: String, automationPhrases: Map<String, String>): Check =
        checkAlias(AliasTarget.Vehicle(commandId), phrase, automationPhrases)

    /** [replacing] is the alias being edited: it does not collide with, or count against, its own successor. */
    fun checkAlias(
        target: AliasTarget,
        phrase: String,
        automationPhrases: Map<String, String>,
        replacing: VoiceAlias? = null,
    ): Check = checkIn(without(_aliases.value, replacing), target, phrase, automationPhrases)

    /** [list] without the first element equal to [alias] (the list itself when [alias] is null or absent). */
    private fun without(list: List<VoiceAlias>, alias: VoiceAlias?): List<VoiceAlias> {
        val index = alias?.let(list::indexOf) ?: -1
        return if (index < 0) list else list.filterIndexed { i, _ -> i != index }
    }

    private fun checkIn(
        list: List<VoiceAlias>,
        target: AliasTarget,
        phrase: String,
        automationPhrases: Map<String, String>,
    ): Check {
        val norm = VoicePhrase.normalize(phrase)
        if (norm.isBlank()) return Check.Empty
        if (phrase.trim().length > MAX_CHARS) return Check.TooLong
        if (list.count { it.target == target } >= MAX_PHRASES) return Check.TooMany
        list.firstOrNull { VoicePhrase.isExact(phrase, it.phrase) }?.let { return Check.InUse(ownerName(it.target)) }
        automationPhrases[norm]?.let { return Check.InUse(it) }
        return Check.Ok
    }

    /** The one write path for the assistant: check and persist [phrase] → [target] atomically. */
    fun saveAlias(target: AliasTarget, phrase: String, automationPhrases: Map<String, String> = emptyMap()): SaveResult =
        synchronized(lock) {
            val verdict = checkIn(doc.aliases, target, phrase, automationPhrases)
            if (verdict != Check.Ok) return SaveResult.Rejected(verdict)
            if (publish(doc.aliases + VoiceAlias(phrase.trim(), target))) SaveResult.Saved else SaveResult.PersistFailed
        }

    /** Adds [phrase] to a built-in command without a collision check (the editor checks first).
     *  True when the new list was durably committed. */
    fun add(commandId: String, phrase: String): Boolean = synchronized(lock) {
        publish(doc.aliases + VoiceAlias(phrase.trim(), AliasTarget.Vehicle(commandId)))
    }

    /** Removes one alias equal to [alias], of any kind (a retained unavailable one too). False when none is
     *  stored or the commit failed; nothing is published then. */
    fun removeAlias(alias: VoiceAlias): Boolean = synchronized(lock) {
        if (doc.aliases.indexOf(alias) < 0) return false
        publish(without(doc.aliases, alias))
    }

    /** Replaces one stored alias equal to [old] by [new] at the same position, in one commit. [new] is checked
     *  against every other alias and [automationPhrases]; nothing is written on a collision or a failed commit. */
    fun replaceAlias(old: VoiceAlias, new: VoiceAlias, automationPhrases: Map<String, String> = emptyMap()): ReplaceResult =
        synchronized(lock) {
            val index = doc.aliases.indexOf(old)
            if (index < 0) return ReplaceResult.NotFound
            val verdict = checkIn(without(doc.aliases, old), new.target, new.phrase, automationPhrases)
            if (verdict != Check.Ok) return ReplaceResult.Rejected(verdict)
            val next = doc.aliases.toMutableList().also { it[index] = VoiceAlias(new.phrase.trim(), new.target) }
            if (publish(next)) ReplaceResult.Replaced else ReplaceResult.PersistFailed
        }

    /** True when the new list was durably committed. Removing an absent phrase still commits. */
    fun remove(commandId: String, phrase: String): Boolean = synchronized(lock) {
        val index = doc.aliases.indexOfFirst { it.phrase == phrase && commandIdOf(it.target) == commandId && valueOf(it.target) == null }
        publish(if (index < 0) doc.aliases else doc.aliases.filterIndexed { i, _ -> i != index })
    }

    /** Commits the v2 document with [aliases]; publishes in-memory state only when `commit()` succeeds. */
    private fun publish(aliases: List<VoiceAlias>): Boolean {
        val next = Doc(aliases, doc.unreadable, doc.retained)
        if (prefs != null && !prefs.edit().putString(KEY, serialize(next)).commit()) {
            Log.w(TAG, "user phrases not saved: commit failed")
            return false
        }
        doc = next
        _aliases.value = aliases
        _phrases.value = phraseMap(aliases)
        return true
    }

    private fun load(): Doc {
        val raw = prefs?.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return Doc(emptyList(), null, emptyList())
        return runCatching { parse(raw) }
            .onFailure { Log.w(TAG, "user phrases unreadable, keeping raw text unused: ${it.message}") }
            .getOrElse { Doc(emptyList(), raw, emptyList()) }
    }

    private fun parse(raw: String): Doc {
        val json = JSONObject(raw)
        if (!json.has("version")) return Doc(parseLegacy(json), null, emptyList())
        require(json.getInt("version") == VERSION) { "unsupported version ${json.opt("version")}" }
        val entries = json.getJSONArray("aliases")
        val aliases = mutableListOf<VoiceAlias>()
        val junk = mutableListOf<String>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i)
            val phrase = entry?.opt("phrase") as? String
            if (entry == null || phrase == null) junk += entries.get(i).toString()
            else aliases += VoiceAlias(phrase, targetOf(entry))
        }
        val retained = json.optJSONArray("retained")?.let { a -> (0 until a.length()).map { a.get(it).toString() } }.orEmpty()
        return Doc(aliases, json.opt("unreadable") as? String, retained + junk)
    }

    private fun parseLegacy(json: JSONObject): List<VoiceAlias> = buildList {
        for (id in json.keys()) {
            val arr = json.getJSONArray(id)
            for (i in 0 until arr.length()) {
                val phrase = arr.getString(i)
                val target = if (id in byId) AliasTarget.Vehicle(id)
                else AliasTarget.Unavailable("vehicle", id, JSONObject().put("kind", "vehicle").put("id", id).toString())
                add(VoiceAlias(phrase, target))
            }
        }
    }

    private fun targetOf(entry: JSONObject): AliasTarget {
        val kind = entry.optString("kind")
        val known: AliasTarget? = when (kind) {
            "vehicle" -> {
                val id = entry.opt("id") as? String
                val value = if (entry.has("value")) entry.opt("value") as? Int ?: return unavailable(entry) else null
                if (id != null && vehicleAvailable(id, value)) AliasTarget.Vehicle(id, value) else null
            }
            "navigate" -> navigationTargetOf(entry)
            else -> null
        }
        return known ?: unavailable(entry)
    }

    private fun navigationTargetOf(entry: JSONObject): AliasTarget? {
        val query = entry.opt("query") as? String
        val shortcut = entry.opt("shortcut") as? String
        return when {
            query != null && shortcut == null && query.isNotBlank() -> AliasTarget.NavigateQuery(query)
            shortcut in SHORTCUTS && query == null -> AliasTarget.NavigateShortcut(shortcut!!)
            else -> null
        }
    }

    private fun unavailable(entry: JSONObject): AliasTarget.Unavailable {
        val retained = JSONObject(entry.toString()).apply { remove("phrase") }
        return AliasTarget.Unavailable(entry.optString("kind"), entry.opt("id") as? String, retained.toString())
    }

    private fun serialize(d: Doc): String {
        val entries = JSONArray()
        for (alias in d.aliases) {
            val entry = when (val t = alias.target) {
                is AliasTarget.Vehicle -> JSONObject().put("kind", "vehicle").put("id", t.commandId)
                    .also { if (t.value != null) it.put("value", t.value) }
                is AliasTarget.NavigateQuery -> JSONObject().put("kind", "navigate").put("query", t.query)
                is AliasTarget.NavigateShortcut -> JSONObject().put("kind", "navigate").put("shortcut", t.which)
                is AliasTarget.Unavailable -> JSONObject(t.retained)
            }
            entries.put(entry.put("phrase", alias.phrase))
        }
        val json = JSONObject().put("version", VERSION).put("aliases", entries)
        d.unreadable?.let { json.put("unreadable", it) }
        if (d.retained.isNotEmpty()) json.put("retained", JSONArray(d.retained.map { runCatching { JSONObject(it) }.getOrDefault(it) }))
        return json.toString()
    }

    private fun phraseMap(list: List<VoiceAlias>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (alias in list) {
            val id = commandIdOf(alias.target) ?: continue
            if (valueOf(alias.target) == null) {
                out.getOrPut(id) { mutableListOf() }.add(alias.phrase)
            }
        }
        return out
    }

    private fun commandIdOf(t: AliasTarget): String? = when (t) {
        is AliasTarget.Vehicle -> t.commandId
        is AliasTarget.Unavailable -> t.commandId.takeIf { t.kind == "vehicle" }
        else -> null
    }

    private fun valueOf(t: AliasTarget): Int? = (t as? AliasTarget.Vehicle)?.value

    private fun ownerName(t: AliasTarget): String = when (t) {
        is AliasTarget.Vehicle -> if (t.value == null) byId[t.commandId]?.name
            ?: AgentCommandCatalog.ALL.firstOrNull { it.id == t.commandId }?.label ?: t.commandId
        else AgentCommandCatalog.ALL.firstOrNull { it.id == t.commandId }?.label?.replace("N", t.value.toString()) ?: t.commandId
        is AliasTarget.NavigateQuery -> "Nawiguj do: ${t.query}"
        is AliasTarget.NavigateShortcut -> if (t.which == "home") "Nawiguj do domu" else "Nawiguj do pracy"
        is AliasTarget.Unavailable -> t.commandId ?: t.kind
    }

    companion object {
        private const val TAG = "VoiceUserPhrases"
        const val PREFS = "voice"
        const val KEY = "voice_cmd_overrides"
        const val MAX_PHRASES = 5
        const val MAX_CHARS = 40
        private const val VERSION = 2
        private val SHORTCUTS = setOf("home", "work")

        /** Fixed built-in offline commands (the parser's own catalog), named from the agent
         *  catalog. Ranged ones (temperature) need a number and cannot hang on a fixed phrase. */
        val COMMANDS: List<VoiceUserCommand> by lazy {
            val offline = VoiceCatalog.ALL.filter { it.value == null }.map { it.command(null) }.toSet()
            AgentCommandCatalog.ALL
                .filter { it.value == null && it.chinese(null) in offline }
                .map { VoiceUserCommand(it.id, it.label, it.chinese(null)) }
        }

        private val byId: Map<String, VoiceUserCommand> by lazy { COMMANDS.associateBy { it.id } }

        /** Whether a saved [target] loads back as an available (not [AliasTarget.Unavailable]) alias; the assistant
         *  path offers only such targets, so a Save never becomes a retained record after a restart. */
        fun canPersist(target: AliasTarget): Boolean = when (target) {
            is AliasTarget.Vehicle -> vehicleAvailable(target.commandId, target.value)
            is AliasTarget.NavigateQuery -> target.query.isNotBlank()
            is AliasTarget.NavigateShortcut -> target.which in SHORTCUTS
            is AliasTarget.Unavailable -> false
        }

        /** A known catalog command in the shape the catalog gives it: no value for a fixed command (offline
         *  grammar or not), a value inside its range for a ranged one. Whether the car can run it now is
         *  decided at run time by the shared validator, never here. */
        private fun vehicleAvailable(id: String, value: Int?): Boolean {
            val cmd = AgentCommandCatalog.ALL.firstOrNull { it.id == id } ?: return false
            val range = cmd.value ?: return value == null
            return value != null && value in range
        }
    }
}
