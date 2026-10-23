package com.bydmate.app.agent

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.CommandTranslator
import com.bydmate.app.data.vehicle.WriteAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ActionRegistryTest {

    private val allowlist: WriteAllowlist by lazy {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        WriteAllowlist.loadProduction { File(root, "app/src/main/assets/competitor-actions.json").readText() }
    }

    private val registry: ActionRegistry by lazy { ActionRegistry(allowlist = allowlist) }

    private val golden: List<Pair<String, ActionRisk>> by lazy {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("openclaw-actions/registry-risk-golden.tsv")) {
            "golden file missing"
        }
        stream.bufferedReader().readLines().filter { it.isNotBlank() }.map {
            val (id, risk) = it.split('\t')
            id to ActionRisk.valueOf(risk)
        }
    }

    @Test fun `every runtime-executable catalog id has a descriptor`() {
        assertEquals(110, registry.descriptors.size)
        assertEquals(AgentCommandCatalog.ALL.map { it.id }, registry.descriptors.map { it.id })
    }

    @Test fun `risk of every id matches the golden file and 55 are CONFIRM`() {
        assertEquals(110, golden.size)
        assertEquals(55, golden.count { it.second == ActionRisk.CONFIRM })
        assertEquals(golden, registry.descriptors.map { it.id to it.risk })
    }

    @Test fun `unvalidated ids are not filtered out`() {
        for (id in listOf("hud_on", "ac_fan_level", "trunk_open", "drive_mode_rock")) {
            assertNotNull("$id missing", registry.descriptor(id))
        }
    }

    @Test fun `trunk open is CONFIRM and hud on is COMFORT`() {
        assertEquals(ActionRisk.CONFIRM, registry.descriptor("trunk_open")?.risk)
        assertEquals(ActionRisk.COMFORT, registry.descriptor("hud_on")?.risk)
    }

    @Test fun `massage ids never appear and are refused as not executable`() {
        assertTrue(registry.descriptors.none { it.id.contains("massage", ignoreCase = true) })
        assertEquals(ActionRejection.NOT_EXECUTABLE, registry.refusalFor("driver_massage_lvl1"))
        assertEquals(ActionRejection.NOT_EXECUTABLE, registry.refusalFor("Seat_Masaz"))
        assertEquals(ActionRejection.UNKNOWN_COMMAND, registry.refusalFor("nonsense"))
    }

    @Test fun `local-only massage commands are in no catalog and the registry still refuses massage`() {
        val local = CommandTranslator.LOCAL_VOICE_ONLY
        assertTrue(local.isNotEmpty())
        assertTrue(local.intersect(AgentCommandCatalog.ALL.map { it.chinese(null) }.toSet()).isEmpty())
        assertTrue(local.intersect(com.bydmate.app.voice.VoiceCatalog.ALL.filter { it.value == null }.map { it.command(null) }.toSet()).isEmpty())
        assertEquals(ActionRejection.NOT_EXECUTABLE, registry.refusalFor("driver_massage_pulse"))
        assertEquals(ActionRejection.NOT_EXECUTABLE, registry.refusalFor("driver_massage_off"))
    }

    @Test fun `version is 12 hex chars, stable, and changes with a label`() {
        assertTrue(registry.version.matches(Regex("[0-9a-f]{12}")))
        assertEquals(registry.version, ActionRegistry(allowlist = allowlist).version)

        val base = AgentCommandCatalog.ALL.take(3)
        val changed = base.mapIndexed { i, c -> if (i == 1) c.copy(label = c.label + "!") else c }
        val v1 = ActionRegistry(base, allowlist).version
        val v2 = ActionRegistry(changed, allowlist).version
        assertTrue(v1.matches(Regex("[0-9a-f]{12}")))
        assertNotEquals(v1, v2)
    }

    @Test fun `entry unknown to the translator is excluded and refused as not executable`() {
        val bogus = AgentCommandCatalog.Cmd("bogus_cmd", "nieznane", { "未知命令" })
        val reg = ActionRegistry(listOf(AgentCommandCatalog.ALL.first(), bogus), allowlist)
        assertEquals(listOf(AgentCommandCatalog.ALL.first().id), reg.descriptors.map { it.id })
        assertNull(reg.descriptor("bogus_cmd"))
        assertEquals(ActionRejection.NOT_EXECUTABLE, reg.refusalFor("bogus_cmd"))
    }

    @Test fun `resolve delegates to the catalog and range-checks`() {
        assertEquals("设置温度22", registry.resolve("ac_set_temp", 22))
        assertNull(registry.resolve("ac_set_temp", null))
        assertNull(registry.resolve("ac_set_temp", 99))
        assertNull(registry.resolve("nonsense", null))
        assertEquals("车门解锁", registry.resolve("doors_unlock", null))
    }

    @Test fun `ranged ids have the same risk at both ends of the range`() {
        val ranged = registry.descriptors.filter { it.range != null }
        assertTrue("expected ranged descriptors", ranged.isNotEmpty())
        for (d in ranged) {
            val risks = listOf(d.range!!.first, d.range!!.last).map { v ->
                val zh = checkNotNull(AgentCommandCatalog.resolve(d.id, v))
                val categories = CommandTranslator.resolve(zh).mapNotNull { allowlist.find(it.actionName)?.category }
                val confirm = ActionDispatcher.isDangerousAction(ActionDef(zh, "", "param")) ||
                    ActionDispatcher.isSpeedGated(zh) ||
                    categories.any { it in CONFIRM_CATEGORIES }
                if (confirm) ActionRisk.CONFIRM else ActionRisk.COMFORT
            }
            assertEquals("${d.id} ends disagree", risks[0], risks[1])
            assertEquals("${d.id} descriptor", risks[0], d.risk)
        }
        assertFalse(ranged.any { it.risk == ActionRisk.CONFIRM })
    }

    private companion object {
        val CONFIRM_CATEGORIES = setOf("windows", "sunroof", "sunshade", "locks", "trunk", "drive_mode", "energy")
    }
}
