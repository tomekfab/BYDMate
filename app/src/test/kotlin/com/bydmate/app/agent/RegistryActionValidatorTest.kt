package com.bydmate.app.agent

import com.bydmate.app.data.vehicle.WriteAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegistryActionValidatorTest {

    private val allowlist: WriteAllowlist by lazy {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        WriteAllowlist.loadProduction { File(root, "app/src/main/assets/competitor-actions.json").readText() }
    }

    private val registry: ActionRegistry by lazy { ActionRegistry(allowlist = allowlist) }
    private val validator: ActionValidating by lazy { RegistryActionValidator(registry) }

    private fun vehicle(id: String, value: Int? = null) = ActionCandidate("vehicle", id = id, value = value)
    private fun nav(query: String) = ActionCandidate("navigate", query = query)
    private fun shortcut(s: String) = ActionCandidate("navigate", shortcut = s)

    private fun invalid(c: ActionCandidate, version: String? = null): ActionRejection {
        val r = validator.validate(c, version)
        assertTrue("expected Invalid but was $r", r is ActionValidation.Invalid)
        return (r as ActionValidation.Invalid).reason
    }

    private fun valid(c: ActionCandidate, version: String? = null): ValidatedAction {
        val r = validator.validate(c, version)
        assertTrue("expected Valid but was $r", r is ActionValidation.Valid)
        return (r as ActionValidation.Valid).action
    }

    // --- summarize ---

    @Test
    fun summarizeRangedLabelSubstitutesStandaloneN() {
        val a = valid(vehicle("ac_set_temp", 22))
        assertEquals("ustaw temperaturę klimatyzacji na 22°C", registry.summarize(a))
    }

    @Test
    fun summarizeDropsTrailingNumericRangeHint() {
        val a = valid(vehicle("ac_fan_level", 4))
        assertEquals("ustaw prędkość wentylatora na 4", registry.summarize(a))
    }

    @Test
    fun summarizeKeepsNegativeValueAndDropsNegativeRangeHint() {
        val a = valid(vehicle("fridge_cool", -3))
        assertEquals("chłodzenie lodówki do -3 stopni", registry.summarize(a))
    }

    @Test
    fun summarizeUnrangedVehicleKeepsExactLabel() {
        val a = valid(vehicle("hud_on"))
        assertEquals(registry.descriptor("hud_on")!!.labelPl, registry.summarize(a))
    }

    @Test
    fun summarizeRangedLabelWithoutPlaceholderFallsBackToParenthesizedValue() {
        val catalog = listOf(
            AgentCommandCatalog.Cmd("ac_set_temp", "ustaw temperaturę", { n -> "设置温度$n" }, 16..33),
        )
        val reg = ActionRegistry(catalog, allowlist)
        val a = (RegistryActionValidator(reg).validate(vehicle("ac_set_temp", 22), null) as ActionValidation.Valid).action
        assertEquals("ustaw temperaturę (22)", reg.summarize(a))
    }

    @Test
    fun summarizeKeepsNonNumericParenthetical() {
        val catalog = listOf(
            AgentCommandCatalog.Cmd("ac_set_temp", "ustaw na N (komfort)", { n -> "设置温度$n" }, 16..33),
        )
        val reg = ActionRegistry(catalog, allowlist)
        val a = (RegistryActionValidator(reg).validate(vehicle("ac_set_temp", 22), null) as ActionValidation.Valid).action
        assertEquals("ustaw na 22 (komfort)", reg.summarize(a))
    }

    @Test
    fun summarizeDoesNotReplaceNInsideWords() {
        val catalog = listOf(
            AgentCommandCatalog.Cmd("ac_set_temp", "Nastaw N stopni (16..33)", { n -> "设置温度$n" }, 16..33),
        )
        val reg = ActionRegistry(catalog, allowlist)
        val a = (RegistryActionValidator(reg).validate(vehicle("ac_set_temp", 20), null) as ActionValidation.Valid).action
        assertEquals("Nastaw 20 stopni", reg.summarize(a))
    }

    @Test
    fun summarizeNavigateQuery() {
        assertEquals("Nawiguj do: Szybowcowa 5", registry.summarize(valid(nav("Szybowcowa 5"))))
    }

    @Test
    fun summarizeNavigateShortcuts() {
        assertEquals("Nawiguj do domu", registry.summarize(valid(shortcut("home"))))
        assertEquals("Nawiguj do pracy", registry.summarize(valid(shortcut("work"))))
    }

    @Test
    fun summarizeRemovesControlCharactersFromVehicleLabel() {
        val catalog = listOf(
            AgentCommandCatalog.Cmd("hud_on", "włącz\u0007 HUD\n", { "打开抬头显示" }),
        )
        val reg = ActionRegistry(catalog, allowlist)
        val a = (RegistryActionValidator(reg).validate(vehicle("hud_on"), null) as ActionValidation.Valid).action
        assertEquals("włącz HUD", reg.summarize(a))
    }

    @Test
    fun summarizeRemovesControlCharactersFromNavigateQueryText() {
        // Validated queries carry none; summarize is still defensive because the text is shown to the driver.
        val a = valid(nav("Szybowcowa 5"))
        assertTrue(registry.summarize(a).none(Char::isISOControl))
    }

    // --- vehicle ---

    @Test
    fun validVehicleRangedAtTemperature22() {
        val a = valid(vehicle("ac_set_temp", 22)) as ValidatedAction.Vehicle
        assertEquals("ac_set_temp", a.commandId)
        assertEquals(22, a.value)
    }

    @Test
    fun validVehicleUnranged() {
        val a = valid(vehicle("hud_on")) as ValidatedAction.Vehicle
        assertEquals("hud_on", a.commandId)
        assertEquals(null, a.value)
    }

    @Test
    fun rangedMinAndMaxAccepted() {
        valid(vehicle("ac_set_temp", 16))
        valid(vehicle("ac_set_temp", 33))
    }

    @Test
    fun outOfRangeRejected() {
        assertEquals(ActionRejection.VALUE_OUT_OF_RANGE, invalid(vehicle("ac_set_temp", 15)))
        assertEquals(ActionRejection.VALUE_OUT_OF_RANGE, invalid(vehicle("ac_set_temp", 34)))
    }

    @Test
    fun rangedWithoutValueRejected() {
        assertEquals(ActionRejection.MISSING_VALUE, invalid(vehicle("ac_set_temp")))
    }

    @Test
    fun unrangedWithValueRejected() {
        assertEquals(ActionRejection.UNEXPECTED_FIELD, invalid(vehicle("hud_on", 1)))
    }

    @Test
    fun vehicleWithQueryOrShortcutRejected() {
        assertEquals(ActionRejection.UNEXPECTED_FIELD, invalid(ActionCandidate("vehicle", id = "hud_on", query = "x")))
        assertEquals(ActionRejection.UNEXPECTED_FIELD, invalid(ActionCandidate("vehicle", id = "hud_on", shortcut = "home")))
    }

    @Test
    fun massageIdIsNotExecutable() {
        assertEquals(ActionRejection.NOT_EXECUTABLE, invalid(vehicle("driver_massage_lvl1")))
    }

    @Test
    fun unknownIdIsUnknownCommand() {
        assertEquals(ActionRejection.UNKNOWN_COMMAND, invalid(vehicle("nonsense")))
    }

    @Test
    fun missingIdIsUnknownCommand() {
        assertEquals(ActionRejection.UNKNOWN_COMMAND, invalid(ActionCandidate("vehicle")))
    }

    @Test
    fun rawChineseCommandAsIdIsUnknownCommand() {
        assertEquals(ActionRejection.UNKNOWN_COMMAND, invalid(vehicle("设置温度22")))
    }

    @Test
    fun twentyDigitFidAsIdIsUnknownCommand() {
        assertEquals(ActionRejection.UNKNOWN_COMMAND, invalid(vehicle("12345678901234567890")))
    }

    // --- kind and version ---

    @Test
    fun unknownKindRejected() {
        assertEquals(ActionRejection.UNKNOWN_KIND, invalid(ActionCandidate("launch_missiles")))
    }

    @Test
    fun staleVersionRejectedBeforeAnythingElse() {
        assertEquals(ActionRejection.STALE_CAPABILITIES, invalid(ActionCandidate("bogus"), "000000000000"))
        assertEquals(ActionRejection.STALE_CAPABILITIES, invalid(vehicle("hud_on"), "000000000000"))
    }

    @Test
    fun matchingVersionAccepted() {
        valid(vehicle("hud_on"), registry.version)
    }

    @Test
    fun nullVersionAccepted() {
        valid(vehicle("hud_on"), null)
    }

    // --- navigate ---

    @Test
    fun queryWithLetterIAndCommaAcceptedUnchanged() {
        val a = valid(nav("Lecha i Marii 3, Kraków")) as ValidatedAction.NavigateQuery
        assertEquals("Lecha i Marii 3, Kraków", a.query)
    }

    @Test
    fun queryWithColonSpaceAcceptedUnchanged() {
        val a = valid(nav("Plac: Wolności 3")) as ValidatedAction.NavigateQuery
        assertEquals("Plac: Wolności 3", a.query)
    }

    @Test
    fun uriStyleQueriesRejected() {
        assertEquals(ActionRejection.DESTINATION_INVALID, invalid(nav("geo:0,0")))
        assertEquals(ActionRejection.DESTINATION_INVALID, invalid(nav("javascript:alert(1)")))
        assertEquals(ActionRejection.DESTINATION_INVALID, invalid(nav("http://x")))
    }

    @Test
    fun blankQueryIsEmptyDestination() {
        assertEquals(ActionRejection.EMPTY_DESTINATION, invalid(nav("")))
        assertEquals(ActionRejection.EMPTY_DESTINATION, invalid(nav("   ")))
    }

    @Test
    fun overlongQueryRejected() {
        assertEquals(ActionRejection.DESTINATION_INVALID, invalid(nav("a".repeat(201))))
        valid(nav("a".repeat(200)))
    }

    @Test
    fun controlCharacterQueryRejected() {
        assertEquals(ActionRejection.DESTINATION_INVALID, invalid(nav("Szybowcowa\u00005")))
        assertEquals(ActionRejection.DESTINATION_INVALID, invalid(nav("Szybowcowa\n5")))
    }

    @Test
    fun shortcutsHomeAndWorkAccepted() {
        assertEquals(ValidatedAction.Shortcut.HOME, (valid(shortcut("home")) as ValidatedAction.NavigateShortcut).which)
        assertEquals(ValidatedAction.Shortcut.WORK, (valid(shortcut("work")) as ValidatedAction.NavigateShortcut).which)
    }

    @Test
    fun unknownShortcutRejected() {
        assertEquals(ActionRejection.DESTINATION_INVALID, invalid(shortcut("garage")))
    }

    @Test
    fun navigateNeedsExactlyOneOfQueryAndShortcut() {
        assertEquals(ActionRejection.UNEXPECTED_FIELD, invalid(ActionCandidate("navigate")))
        assertEquals(ActionRejection.UNEXPECTED_FIELD, invalid(ActionCandidate("navigate", query = "x", shortcut = "home")))
    }

    @Test
    fun navigateWithIdOrValueRejected() {
        assertEquals(ActionRejection.UNEXPECTED_FIELD, invalid(ActionCandidate("navigate", query = "x", id = "hud_on")))
        assertEquals(ActionRejection.UNEXPECTED_FIELD, invalid(ActionCandidate("navigate", shortcut = "home", value = 1)))
    }
}
