package com.bydmate.app.agent

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.CommandTranslator
import com.bydmate.app.data.vehicle.SeatGroup
import com.bydmate.app.data.vehicle.WriteAllowlist
import java.security.MessageDigest

enum class ActionRisk { COMFORT, CONFIRM }

class ActionDescriptor(val id: String, val labelPl: String, val range: IntRange?, val risk: ActionRisk)

/**
 * The commands an assistant may propose: every catalog id that the runtime can really execute
 * (its command resolves through [CommandTranslator] to allowlisted writes), each with a risk class
 * derived from the dispatcher's own gates and the allowlist category, so it cannot drift.
 * `WriteEntry.validated` is not a filter: it only picks error types, not what runs.
 */
class ActionRegistry(
    private val catalog: List<AgentCommandCatalog.Cmd> = AgentCommandCatalog.ALL,
    private val allowlist: WriteAllowlist,
) {
    private val byId: Map<String, AgentCommandCatalog.Cmd> = catalog.associateBy { it.id }

    val descriptors: List<ActionDescriptor> = catalog.mapNotNull { derive(it) }

    private val descriptorById: Map<String, ActionDescriptor> = descriptors.associateBy { it.id }

    val version: String = descriptors
        .joinToString("") { d ->
            "${d.id}|${d.labelPl}|${d.range?.let { "${it.first}..${it.last}" } ?: ""}|${d.risk}\n"
        }
        .let { text ->
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(12)
        }

    fun descriptor(id: String): ActionDescriptor? = descriptorById[id]

    /** Same rule as [AgentCommandCatalog.resolve], applied to this registry's own catalog. */
    fun resolve(id: String, value: Int?): String? = byId[id]?.let { commandFor(it, value) }

    fun refusalFor(id: String): ActionRejection {
        val knownButUnavailable = id in byId && id !in descriptorById
        return if (knownButUnavailable || id.contains("massage", ignoreCase = true) || id.contains("masaz", ignoreCase = true)) {
            ActionRejection.NOT_EXECUTABLE
        } else {
            ActionRejection.UNKNOWN_COMMAND
        }
    }

    /**
     * Local Polish text for the driver (overlay, alias dialog, speech). Built from the registry's own
     * labels, never from assistant prose; control characters are removed.
     */
    fun summarize(action: ValidatedAction): String {
        val text = when (action) {
            is ValidatedAction.Vehicle -> summarizeVehicle(action)
            is ValidatedAction.NavigateQuery -> "Nawiguj do: ${action.query}"
            is ValidatedAction.NavigateShortcut -> when (action.which) {
                ValidatedAction.Shortcut.HOME -> "Nawiguj do domu"
                ValidatedAction.Shortcut.WORK -> "Nawiguj do pracy"
            }
        }
        return text.filterNot(Char::isISOControl)
    }

    /** Puts the value where the label has a standalone `N`, dropping a trailing numeric `(min..max)` hint. */
    private fun summarizeVehicle(action: ValidatedAction.Vehicle): String {
        val label = descriptorById[action.commandId]?.labelPl ?: action.commandId
        val value = action.value ?: return label
        if (!STANDALONE_N.containsMatchIn(label)) return "$label ($value)"
        return label.replace(STANDALONE_N, value.toString()).replace(TRAILING_RANGE_HINT, "")
    }

    private fun commandFor(cmd: AgentCommandCatalog.Cmd, value: Int?): String? {
        val range = cmd.value ?: return cmd.chinese(null)
        val v = value ?: return null
        return if (v in range) cmd.chinese(v) else null
    }

    private fun samples(range: IntRange?): List<Int?> =
        if (range == null) listOf(null)
        else listOfNotNull(range.first, range.last, (range.first + 1).takeIf { it in range }).distinct()

    private fun derive(cmd: AgentCommandCatalog.Cmd): ActionDescriptor? {
        if (cmd.id.contains("massage", ignoreCase = true) || cmd.id.contains("masaz", ignoreCase = true)) return null
        val risks = mutableSetOf<ActionRisk>()
        for (sample in samples(cmd.value)) {
            val zh = commandFor(cmd, sample) ?: return null
            risks += riskOf(zh) ?: return null
        }
        check(risks.size == 1) { "Samples of ${cmd.id} disagree on risk: $risks" }
        return ActionDescriptor(cmd.id, cmd.label, cmd.value, risks.single())
    }

    /** Null when [zh] cannot run: unknown to the translator or an action missing from the allowlist. */
    private fun riskOf(zh: String): ActionRisk? {
        val categories = categoriesOf(zh) ?: return null
        val confirm = ActionDispatcher.isDangerousAction(ActionDef(zh, "", "param")) ||
            ActionDispatcher.isSpeedGated(zh) ||
            categories.any { it in CONFIRM_CATEGORIES } ||
            zh in HAZARD_COMMANDS
        return if (confirm) ActionRisk.CONFIRM else ActionRisk.COMFORT
    }

    private fun categoriesOf(zh: String): List<String>? {
        val resolved = CommandTranslator.resolve(zh)
        if (resolved.isNotEmpty()) {
            return resolved.map { allowlist.find(it.actionName)?.category ?: return null }
        }
        val seat = CommandTranslator.resolveSeat(zh) ?: return null
        val (switch, level) = SEAT_ACTIONS.getValue(seat.group)
        val entries = listOf(switch, level).map { allowlist.find(it) ?: return null }
        return entries.map { it.category }
    }

    private companion object {
        val STANDALONE_N = Regex("(?<![\\p{L}\\p{N}])N(?![\\p{L}\\p{N}])")
        val TRAILING_RANGE_HINT = Regex("\\s*\\(-?\\d+\\.\\.-?\\d+\\)\\s*$")
        val CONFIRM_CATEGORIES = setOf("windows", "sunroof", "sunshade", "locks", "trunk", "drive_mode", "energy")
        val HAZARD_COMMANDS = setOf("双闪打开", "双闪关闭")

        /** Switch and level action names the seat channel writes (see AdaptiveSeatChannel). */
        val SEAT_ACTIONS: Map<SeatGroup, Pair<String, String>> = mapOf(
            SeatGroup.DRIVER_HEAT to ("driver_seat_heat_switch" to "driver_seat_heat_level"),
            SeatGroup.DRIVER_VENT to ("driver_seat_vent_switch" to "driver_seat_vent_level"),
            SeatGroup.PASSENGER_HEAT to ("passenger_seat_heat_switch" to "passenger_seat_heat_level"),
            SeatGroup.PASSENGER_VENT to ("passenger_seat_vent_switch" to "passenger_seat_vent_level"),
        )
    }
}
