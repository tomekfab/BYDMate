package com.bydmate.app.voice

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoiceUserPhrasesTest {
    private val close = "windows_close_all"
    private val open = "windows_open_all"

    /** In-memory SharedPreferences for the one voice key. Counts `apply()` / `commit()` calls and
     *  records every successful write; [commitResult] false simulates a failed disk write. */
    private class FakePrefs(initial: String? = null) {
        @Volatile var stored: String? = initial
        val applies = AtomicInteger()
        val commits = AtomicInteger()
        val writes = AtomicInteger()
        @Volatile var commitResult = true

        val prefs: SharedPreferences = mockk {
            every { getString(VoiceUserPhrases.KEY, any()) } answers { stored }
            every { edit() } answers {
                var pending: String? = null
                val editor = mockk<SharedPreferences.Editor>(relaxed = true)
                every { editor.putString(VoiceUserPhrases.KEY, any()) } answers { pending = secondArg(); editor }
                every { editor.apply() } answers { applies.incrementAndGet(); writes.incrementAndGet(); stored = pending }
                every { editor.commit() } answers {
                    commits.incrementAndGet()
                    if (commitResult) { writes.incrementAndGet(); stored = pending }
                    commitResult
                }
                editor
            }
        }
    }

    private fun memoryPrefs(): SharedPreferences = FakePrefs().prefs

    @Test fun `catalog commands are the parser's fixed commands`() {
        val ids = VoiceUserPhrases.COMMANDS.map { it.id }
        assertTrue(ids.toString(), close in ids && open in ids)
        assertEquals("车窗关闭", VoiceUserPhrases.COMMANDS.first { it.id == close }.command)
    }

    @Test fun `equal phrase matches`() {
        val p = VoiceUserPhrases().apply { add(close, "zamknij okna") }
        val cmd = VoiceUserPhrases.COMMANDS.first { it.id == close }
        assertEquals(cmd, p.match("zamknij okna"))
    }

    @Test fun `phrase inside a longer utterance does not match`() {
        val p = VoiceUserPhrases().apply { add(close, "zamknij okna") }
        assertNull(p.match("no dobra zamknij okna szybko"))
    }

    @Test fun `Polish fillers punctuation and case are ignored`() {
        val p = VoiceUserPhrases().apply { add(open, "otwórz choinkę") }
        assertEquals(open, p.match("Ej, słuchaj, otwórz choinkę mi, proszę!")?.id)
    }

    // Matching is word for word: another inflection does not match, so the parser keeps it.
    @Test fun `another inflection of the phrase does not match`() {
        val p = VoiceUserPhrases().apply { add(close, "otwórz okna") }
        assertNull(p.match("otwórz okno"))
        assertEquals(close, p.match("słuchaj otwórz okna proszę")?.id)
    }

    @Test fun `no partial-word match`() {
        val p = VoiceUserPhrases().apply { add(close, "okno") }
        assertNull(p.match("okienkować"))
    }

    @Test fun `each phrase matches only itself`() {
        val p = VoiceUserPhrases().apply {
            add(open, "przewietrz")
            add(close, "skończ wietrzenie")
        }
        assertEquals(close, p.match("skończ wietrzenie")?.id)
        assertEquals(open, p.match("przewietrz")?.id)
    }

    @Test fun `remove drops the phrase`() {
        val p = VoiceUserPhrases().apply { add(close, "zamknij okna"); remove(close, "zamknij okna") }
        assertNull(p.match("zamknij okna"))
        assertTrue(p.phrases.value.isEmpty())
    }

    @Test fun `phrases persist through prefs`() {
        val prefs = memoryPrefs()
        VoiceUserPhrases(prefs).apply { add(close, "zamknij okna"); add(open, "przewietrz") }

        val reloaded = VoiceUserPhrases(prefs)
        assertEquals(mapOf(close to listOf("zamknij okna"), open to listOf("przewietrz")), reloaded.phrases.value)
        assertEquals(close, reloaded.match("zamknij okna")?.id)
    }

    @Test fun `check refuses empty, too long and too many`() {
        val p = VoiceUserPhrases()
        assertEquals(VoiceUserPhrases.Check.Empty, p.check(close, " ,! ", emptyMap()))
        assertEquals(VoiceUserPhrases.Check.TooLong, p.check(close, "a".repeat(VoiceUserPhrases.MAX_CHARS + 1), emptyMap()))
        repeat(VoiceUserPhrases.MAX_PHRASES) { p.add(close, "fraza numer $it") }
        assertEquals(VoiceUserPhrases.Check.TooMany, p.check(close, "jeszcze jedna", emptyMap()))
        assertEquals(VoiceUserPhrases.Check.Ok, p.check(open, "jeszcze jedna", emptyMap()))
    }

    @Test fun `check refuses only exact duplicates of user and automation phrases`() {
        val p = VoiceUserPhrases().apply { add(close, "zamknij okna") }
        val closeName = VoiceUserPhrases.COMMANDS.first { it.id == close }.name

        assertEquals(VoiceUserPhrases.Check.InUse(closeName), p.check(open, "Zamknij, okna!", emptyMap()))
        assertEquals(
            VoiceUserPhrases.Check.InUse("Działka"),
            p.check(open, "jedziemy na działkę", mapOf(VoicePhrase.normalize("jedziemy na działkę") to "Działka")),
        )
        // A phrase that merely contains another one, or one the built-in parser knows, is fine.
        assertEquals(VoiceUserPhrases.Check.Ok, p.check(open, "zamknij okna teraz", emptyMap()))
        assertEquals(VoiceUserPhrases.Check.Ok, p.check(open, "otwórz okna", emptyMap()))
    }

    // A collision is judged as exactly as a match: another inflection is another phrase.
    @Test fun `another inflection of a taken phrase is not a duplicate`() {
        val p = VoiceUserPhrases().apply { add(open, "otwórz okna") }
        assertEquals(VoiceUserPhrases.Check.Ok, p.check(close, "otwórz okno", emptyMap()))
        assertEquals(VoiceUserPhrases.Check.Ok,
            p.check(close, "zamknij szyberdach", mapOf(VoicePhrase.normalize("zamknij szyberdachy") to "Działka")))
        assertEquals(VoiceUserPhrases.Check.InUse("Działka"),
            p.check(close, "Zamknij szyberdachy!", mapOf(VoicePhrase.normalize("zamknij szyberdachy") to "Działka")))
    }

    @Test fun `owners maps normalized phrases to command names`() {
        val p = VoiceUserPhrases().apply { add(close, "Zamknij okna") }
        val name = VoiceUserPhrases.COMMANDS.first { it.id == close }.name
        assertEquals(mapOf(VoicePhrase.normalize("zamknij okna") to name), p.owners())
    }

    private val legacyJson = """{"$close":["zamknij okna"],"$open":["przewietrz"]}"""
    private val legacyUnknown = """{"no_such_command":["zrób coś"],"$close":["zamknij okna"]}"""

    @Test fun `load of legacy JSON and malformed text never writes the preference`() {
        val legacy = FakePrefs(legacyJson)
        val p = VoiceUserPhrases(legacy.prefs)
        assertEquals(
            listOf(
                VoiceAlias("zamknij okna", AliasTarget.Vehicle(close)),
                VoiceAlias("przewietrz", AliasTarget.Vehicle(open)),
            ),
            p.aliases.value,
        )
        assertEquals(close, p.match("zamknij okna")?.id)
        assertEquals(legacyJson, legacy.stored)
        assertEquals(0, legacy.writes.get())
        assertEquals(0, legacy.commits.get() + legacy.applies.get())

        val raw = "{not json at all"
        val broken = FakePrefs(raw)
        val b = VoiceUserPhrases(broken.prefs)
        assertEquals(raw, b.retainedUnreadable)
        assertTrue(b.aliases.value.isEmpty())
        assertNull(b.match("cokolwiek"))
        assertEquals(raw, broken.stored)
        assertEquals(0, broken.writes.get())
    }

    @Test fun `unknown legacy command id stays reserved but cannot execute`() {
        val fake = FakePrefs(legacyUnknown)
        val p = VoiceUserPhrases(fake.prefs)
        val hit = p.matchAlias("zrób coś")
        assertEquals("zrób coś", hit?.phrase)
        assertFalse(hit!!.executable)
        assertTrue(hit.target is AliasTarget.Unavailable)
        assertNull(p.match("zrób coś"))
        assertTrue(VoicePhrase.normalize("zrób coś") in p.owners())
        assertEquals(VoiceUserPhrases.Check.InUse("no_such_command"), p.check(open, "Zrób coś!", emptyMap()))
        assertEquals(0, fake.writes.get())

        // Its data survives the first explicit Save of something else.
        assertEquals(SaveResult.Saved, p.saveAlias(AliasTarget.Vehicle(open), "przewietrz"))
        val again = VoiceUserPhrases(fake.prefs)
        assertFalse(again.matchAlias("zrób coś")!!.executable)
        assertEquals(close, again.match("zamknij okna")?.id)
        assertEquals(open, again.match("przewietrz")?.id)
    }

    @Test fun `malformed raw text survives a later Save without becoming executable`() {
        val raw = "{not json at all"
        val fake = FakePrefs(raw)
        val p = VoiceUserPhrases(fake.prefs)
        assertEquals(SaveResult.Saved, p.saveAlias(AliasTarget.Vehicle(close), "zamknij okna"))
        val again = VoiceUserPhrases(fake.prefs)
        assertEquals(raw, again.retainedUnreadable)
        assertEquals(close, again.match("zamknij okna")?.id)
    }

    @Test fun `v2 document is written only by explicit Save, with commit, and survives a new store`() {
        val fake = FakePrefs(legacyJson)
        val p = VoiceUserPhrases(fake.prefs)
        assertEquals(0, fake.writes.get())

        assertEquals(SaveResult.Saved, p.saveAlias(AliasTarget.Vehicle(close), "domknij okna"))
        assertEquals(1, fake.commits.get())
        assertEquals(0, fake.applies.get())
        assertEquals(2, org.json.JSONObject(fake.stored!!).getInt("version"))

        val again = VoiceUserPhrases(fake.prefs)
        assertEquals(p.aliases.value, again.aliases.value)
        assertEquals(close, again.match("domknij okna")?.id)
        assertEquals(open, again.match("przewietrz")?.id)
    }

    @Test fun `add and remove commit synchronously too`() {
        val fake = FakePrefs()
        val p = VoiceUserPhrases(fake.prefs)
        assertTrue(p.add(close, "zamknij okna"))
        assertTrue(p.remove(close, "zamknij okna"))
        assertEquals(2, fake.commits.get())
        assertEquals(0, fake.applies.get())
    }

    @Test fun `a failed commit publishes nothing`() {
        val fake = FakePrefs(legacyJson).apply { commitResult = false }
        val p = VoiceUserPhrases(fake.prefs)
        val before = p.aliases.value
        assertEquals(SaveResult.PersistFailed, p.saveAlias(AliasTarget.Vehicle(close), "domknij okna"))
        assertFalse(p.add(open, "inna fraza"))
        assertEquals(before, p.aliases.value)
        assertNull(p.match("domknij okna"))
        assertEquals(legacyJson, fake.stored)
    }

    @Test fun `ranged fixed-value and destination aliases persist and take part in owners`() {
        val fake = FakePrefs()
        val p = VoiceUserPhrases(fake.prefs)
        assertEquals(SaveResult.Saved, p.saveAlias(AliasTarget.Vehicle("ac_set_temp", 22), "dwadzieścia dwa stopnie"))
        assertEquals(SaveResult.Saved, p.saveAlias(AliasTarget.NavigateQuery("Rynek Główny, Kraków"), "jedziemy na rynek"))
        assertEquals(SaveResult.Saved, p.saveAlias(AliasTarget.NavigateShortcut("home"), "wracamy"))

        val again = VoiceUserPhrases(fake.prefs)
        val temp = again.matchAlias("Dwadzieścia dwa stopnie!")
        assertEquals(AliasTarget.Vehicle("ac_set_temp", 22), temp?.target)
        assertTrue(temp!!.executable)
        assertEquals(AliasTarget.NavigateQuery("Rynek Główny, Kraków"), again.matchAlias("jedziemy na rynek")?.target)
        assertEquals(AliasTarget.NavigateShortcut("home"), again.matchAlias("wracamy")?.target)
        val owners = again.owners()
        for (phrase in listOf("dwadzieścia dwa stopnie", "jedziemy na rynek", "wracamy")) {
            assertTrue(phrase, VoicePhrase.normalize(phrase) in owners)
        }
        // A destination alias blocks the same phrase for a vehicle command.
        assertEquals(
            true,
            again.check(close, "jedziemy na rynek", emptyMap()) is VoiceUserPhrases.Check.InUse,
        )
        // A ranged alias is not a fixed built-in command, so the old command-only API does not execute it.
        assertNull(again.match("dwadzieścia dwa stopnie"))
    }

    @Test fun `out-of-range or unknown vehicle values load as unavailable`() {
        val v2 = """{"version":2,"aliases":[
            {"phrase":"za gorąco","kind":"vehicle","id":"ac_set_temp","value":99},
            {"phrase":"coś nowego","kind":"future_kind","payload":{"x":1}}]}"""
        val p = VoiceUserPhrases(FakePrefs(v2).prefs)
        assertFalse(p.matchAlias("za gorąco")!!.executable)
        assertFalse(p.matchAlias("coś nowego")!!.executable)
    }

    @Test fun `editor and assistant saves through one store lose no committed alias`() {
        val fake = FakePrefs()
        val p = VoiceUserPhrases(fake.prefs)
        val perThread = 20
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.execute {
                start.await(10, TimeUnit.SECONDS)
                repeat(perThread) { p.saveAlias(AliasTarget.NavigateQuery("miejsce $it"), "edytor trasa $it") }
                done.countDown()
            }
            pool.execute {
                start.await(10, TimeUnit.SECONDS)
                repeat(perThread) { p.saveAlias(AliasTarget.Vehicle("ac_set_temp", 16 + it % 10), "asystent temp $it") }
                done.countDown()
            }
            start.countDown()
            assertTrue("saves did not finish in time", done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2 * perThread, p.aliases.value.size)
        val reloaded = VoiceUserPhrases(fake.prefs)
        assertEquals(2 * perThread, reloaded.aliases.value.size)
        assertEquals(2 * perThread, fake.commits.get())
    }

    // A fixed catalog command with no offline grammar entry is still a real, executable target.
    @Test fun `a fixed catalog command outside COMMANDS survives reload`() {
        val outside = "ac_wind_face"
        assertTrue(VoiceUserPhrases.COMMANDS.none { it.id == outside })
        val disk = FakePrefs()
        val first = VoiceUserPhrases(disk.prefs)
        assertEquals(SaveResult.Saved, first.saveAlias(AliasTarget.Vehicle(outside), "nawiew na twarz"))

        val reloaded = VoiceUserPhrases(disk.prefs)
        val alias = reloaded.matchAlias("nawiew na twarz")
        assertEquals(AliasTarget.Vehicle(outside), alias?.target)
        assertTrue(alias!!.executable)
        assertTrue(reloaded.owners().containsKey(VoicePhrase.normalize("nawiew na twarz")))
        // The phrase-only API stays limited to the offline commands.
        assertNull(reloaded.match("nawiew na twarz"))
    }

    @Test fun `canPersist needs a known id and the catalog value shape`() {
        assertTrue(VoiceUserPhrases.canPersist(AliasTarget.Vehicle("ac_wind_face")))
        assertTrue(VoiceUserPhrases.canPersist(AliasTarget.Vehicle(close)))
        assertTrue(VoiceUserPhrases.canPersist(AliasTarget.Vehicle("ac_set_temp", 22)))
        assertFalse(VoiceUserPhrases.canPersist(AliasTarget.Vehicle("ac_set_temp")))
        assertFalse(VoiceUserPhrases.canPersist(AliasTarget.Vehicle("ac_set_temp", 99)))
        assertFalse(VoiceUserPhrases.canPersist(AliasTarget.Vehicle("ac_wind_face", 1)))
        assertFalse(VoiceUserPhrases.canPersist(AliasTarget.Vehicle("no_such_command")))
        assertFalse(VoiceUserPhrases.canPersist(AliasTarget.Unavailable("vehicle", "x", "{}")))
    }

    @Test fun `a v2 document with a fixed catalog command outside COMMANDS loads as executable`() {
        val raw = """{"version":2,"aliases":[{"kind":"vehicle","id":"ac_wind_face","phrase":"na twarz"}]}"""
        val alias = VoiceUserPhrases(FakePrefs(raw).prefs).aliases.value.single()
        assertEquals(AliasTarget.Vehicle("ac_wind_face"), alias.target)
        assertTrue(alias.executable)
    }

    private fun ranged(value: Int) = VoiceAlias("ciepło $value", AliasTarget.Vehicle("ac_set_temp", value))
    private val rynek = VoiceAlias("jedziemy na rynek", AliasTarget.NavigateQuery("Rynek Główny, Kraków"))

    @Test fun `removeAlias removes a ranged a destination and a retained unavailable alias with one commit each`() {
        val legacy = """{"no_such_command":["stara fraza"]}"""
        val fake = FakePrefs(legacy)
        val p = VoiceUserPhrases(fake.prefs)
        assertEquals(SaveResult.Saved, p.saveAlias(ranged(22).target, ranged(22).phrase))
        assertEquals(SaveResult.Saved, p.saveAlias(rynek.target, rynek.phrase))
        val retained = p.aliases.value.first { !it.executable }
        val before = fake.commits.get()

        assertTrue(p.removeAlias(ranged(22)))
        assertTrue(p.removeAlias(rynek))
        assertTrue(p.removeAlias(retained))

        assertEquals(before + 3, fake.commits.get())
        assertTrue(p.aliases.value.isEmpty())
        assertTrue(VoiceUserPhrases(fake.prefs).aliases.value.isEmpty())
    }

    @Test fun `removeAlias of an absent alias writes nothing and a failed commit keeps the alias`() {
        val fake = FakePrefs()
        val p = VoiceUserPhrases(fake.prefs)
        p.saveAlias(rynek.target, rynek.phrase)
        val commits = fake.commits.get()
        assertFalse(p.removeAlias(ranged(20)))
        assertEquals(commits, fake.commits.get())

        fake.commitResult = false
        assertFalse(p.removeAlias(rynek))
        assertEquals(listOf(rynek), p.aliases.value)
    }

    @Test fun `removeAlias drops only one of two equal legacy duplicates`() {
        val fake = FakePrefs("""{"$close":["zamknij to","zamknij to"]}""")
        val p = VoiceUserPhrases(fake.prefs)
        val twin = p.aliases.value.first()
        assertEquals(2, p.aliases.value.size)
        assertTrue(p.removeAlias(twin))
        assertEquals(listOf(twin), p.aliases.value)
    }

    @Test fun `replaceAlias retargets in place in one commit and survives reload`() {
        val fake = FakePrefs()
        val p = VoiceUserPhrases(fake.prefs)
        p.saveAlias(ranged(20).target, ranged(20).phrase)
        p.saveAlias(rynek.target, rynek.phrase)
        p.saveAlias(AliasTarget.NavigateShortcut("home"), "wracamy")
        val commits = fake.commits.get()

        // Same phrase, new target: the alias does not collide with itself.
        val warmer = VoiceAlias(ranged(20).phrase, AliasTarget.Vehicle("ac_set_temp", 24))
        assertEquals(ReplaceResult.Replaced, p.replaceAlias(ranged(20), warmer))
        assertEquals(commits + 1, fake.commits.get())
        assertEquals(listOf(warmer.phrase, rynek.phrase, "wracamy"), p.aliases.value.map { it.phrase })
        assertEquals(warmer, VoiceUserPhrases(fake.prefs).aliases.value.first())
    }

    @Test fun `replaceAlias refuses a collision and a failed commit without publishing anything`() {
        val fake = FakePrefs()
        val p = VoiceUserPhrases(fake.prefs)
        p.saveAlias(ranged(20).target, ranged(20).phrase)
        p.saveAlias(rynek.target, rynek.phrase)
        val snapshot = p.aliases.value
        val commits = fake.commits.get()

        val clash = VoiceAlias(rynek.phrase, ranged(20).target)
        assertTrue((p.replaceAlias(ranged(20), clash) as ReplaceResult.Rejected).check is VoiceUserPhrases.Check.InUse)
        val auto = VoiceAlias("regel", ranged(20).target)
        val automation = mapOf(VoicePhrase.normalize("regel") to "Reguła")
        assertEquals(
            ReplaceResult.Rejected(VoiceUserPhrases.Check.InUse("Reguła")),
            p.replaceAlias(ranged(20), auto, automation),
        )
        assertEquals(ReplaceResult.NotFound, p.replaceAlias(ranged(30), VoiceAlias("inna", ranged(30).target)))
        assertEquals(commits, fake.commits.get())
        assertEquals(snapshot, p.aliases.value)

        fake.commitResult = false
        assertEquals(ReplaceResult.PersistFailed, p.replaceAlias(ranged(20), VoiceAlias("nowa", ranged(21).target)))
        assertEquals(snapshot, p.aliases.value)
    }

    @Test fun `checkAlias replacing ignores the alias being edited`() {
        val p = VoiceUserPhrases()
        p.saveAlias(ranged(20).target, ranged(20).phrase)
        assertTrue(p.checkAlias(ranged(22).target, ranged(20).phrase, emptyMap()) is VoiceUserPhrases.Check.InUse)
        assertEquals(VoiceUserPhrases.Check.Ok, p.checkAlias(ranged(22).target, ranged(20).phrase, emptyMap(), replacing = ranged(20)))
    }
}
