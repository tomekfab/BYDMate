package com.bydmate.app.ui.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.vehicle.CommandTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The low-charge Eco template is back exactly as before its removal in ec94f907 (#253). */
class StarterTemplatesTest {

    @Test fun `eco at low charge is shipped disabled with SOC below 15 and a 300 s cooldown`() {
        val rule = starterTemplates("pl").single { it.name == "ECO przy niskim naładowaniu" }
        assertFalse(rule.enabled)
        assertEquals("AND", rule.triggerLogic)
        assertEquals(300, rule.cooldownSeconds)
        val trigger = TriggerDef.listFromJson(rule.triggers).single()
        assertEquals("SOC", trigger.param)
        assertEquals("<", trigger.operator)
        assertEquals("15", trigger.value)
        val action = ActionDef.listFromJson(rule.actions).single()
        assertEquals("ECO模式", action.command)
        assertEquals("ECO tryb", action.displayName)
        assertTrue(CommandTranslator.resolve(action.command).isNotEmpty())
    }

    @Test fun `winter start and summer cooling wait for the BYDMate start, only the sunshade for gear D`() {
        for (lang in listOf("be", "en", "pl", "pt", "zh")) {
            val triggers = starterTemplates(lang).flatMap { TriggerDef.listFromJson(it.triggers) }
            assertTrue(lang, triggers.none { it.param == "PowerState" })
        }
        val pl = starterTemplates("pl")
        fun lastTrigger(name: String) = TriggerDef.listFromJson(pl.single { it.name == name }.triggers).last()
        for (name in listOf("Zimowy start", "Letnie chłodzenie")) {
            val start = lastTrigger(name)
            assertEquals(name, "service_start", start.kind)
            assertEquals(name, "Uruchom BYDMate", start.displayName)
        }
        assertEquals("ExtTemp", TriggerDef.listFromJson(pl.single { it.name == "Zimowy start" }.triggers).first().param)
        assertEquals("InsideTemp", TriggerDef.listFromJson(pl.single { it.name == "Letnie chłodzenie" }.triggers).first().param)
        val gear = lastTrigger("Roleta podczas jazdy")
        assertEquals("Gear", gear.param)
        assertEquals("4", gear.value)
        assertEquals("Bieg = D", gear.displayName)
        assertTrue(TRIGGER_PARAMS.none { it.param == "PowerState" })
    }

    @Test fun `the rule name follows the language`() {
        assertTrue(starterTemplates("en").any { it.name == "ECO at low SOC" })
        assertTrue(starterTemplates("zh").any { it.name == "低电量ECO" })
        // The final two entries are report templates, which already have Belarusian values.
        // Check the six starter templates separately so they cannot silently fall back to Polish.
        assertFalse(
            starterTemplates("be").take(6).map { it.name } == starterTemplates("pl").take(6).map { it.name },
        )
    }

    @Test fun `two Telegram report templates ship disabled with a report action`() {
        val rules = telegramReportTemplates("pl")
        assertEquals(listOf("Gdzie jest samochód", "Stan przy uruchomieniu"), rules.map { it.name })
        rules.forEach { rule ->
            assertFalse(rule.enabled)
            val action = ActionDef.listFromJson(rule.actions).single()
            assertEquals("telegram_report", action.kind)
            assertTrue(action.payload!!.contains("\"soc\""))
        }
        assertTrue(starterTemplates("pl").map { it.name }.containsAll(listOf("Gdzie jest samochód", "Stan przy uruchomieniu")))
    }

    @Test fun `the Telegram report templates have their own name in every interface language`() {
        assertEquals(listOf("Дзе машына", "Стан пры запуску"), telegramReportTemplates("be").map { it.name })
        assertEquals(listOf("Gdzie jest samochód", "Stan przy uruchomieniu"), telegramReportTemplates("pl").map { it.name })
        assertEquals(listOf("Onde está o carro", "Status ao iniciar"), telegramReportTemplates("pt").map { it.name })
    }

    @Test fun `the Telegram report trigger and action names follow the language too`() {
        for (lang in listOf("be", "en", "pl", "pt", "zh")) {
            val rules = telegramReportTemplates(lang)
            val gearTrigger = TriggerDef.listFromJson(rules[0].triggers).single()
            val startupTrigger = TriggerDef.listFromJson(rules[1].triggers).single()
            val actionName = ActionDef.listFromJson(rules[0].actions).single().displayName
            assertFalse(lang, gearTrigger.displayName.isBlank())
            assertFalse(lang, startupTrigger.displayName.isBlank())
            assertFalse(lang, actionName.isBlank())
        }
        assertEquals("Перадача = P", TriggerDef.listFromJson(telegramReportTemplates("be").first().triggers).single().displayName)
        assertEquals("Bieg = P", TriggerDef.listFromJson(telegramReportTemplates("pl").first().triggers).single().displayName)
        assertEquals("Marcha = P", TriggerDef.listFromJson(telegramReportTemplates("pt").first().triggers).single().displayName)
        assertEquals("Справаздача ў Telegram", ActionDef.listFromJson(telegramReportTemplates("be").first().actions).single().displayName)
        assertEquals("Raport w Telegramie", ActionDef.listFromJson(telegramReportTemplates("pl").first().actions).single().displayName)
        assertEquals("Relatório no Telegram", ActionDef.listFromJson(telegramReportTemplates("pt").first().actions).single().displayName)
    }
}
