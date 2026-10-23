package com.bydmate.app.voice

import com.bydmate.app.agent.AgentCommandCatalog

/** Readable names of dispatch strings for the voice journal: the Chinese protocol string
 *  never reaches the journal screen, the readable catalog id does («windows_open_all»,
 *  «ac_set_temp=22»). */
object VoiceCommandLabels {
    private val byCommand: Map<String, String> by lazy {
        buildMap {
            for (cmd in AgentCommandCatalog.ALL) {
                val range = cmd.value
                if (range == null) putIfAbsent(cmd.chinese(null), cmd.id)
                else for (v in range) putIfAbsent(cmd.chinese(v), "${cmd.id}=$v")
            }
        }
    }

    /** Local-only commands (CommandTranslator.LOCAL_VOICE_ONLY) have no catalog id. */
    private val localOnly: Map<String, String> = mapOf(
        "主驾座椅按摩打开" to "seat_massage_driver_on",
        "主驾座椅按摩关闭" to "seat_massage_driver_off",
    )

    fun of(command: String): String = byCommand[command] ?: localOnly[command] ?: command

    fun of(commands: List<String>): String = commands.joinToString("+") { of(it) }

    /** A human sentence for a journal [label] ("windows_close_all", "ac_set_temp=22"): the catalog
     *  name, or the Polish one for [VoiceLanguage.PL]. A label the catalog does not know
     *  is returned as it is, never invented. */
    fun spoken(label: String, language: VoiceLanguage): String {
        val id = label.substringBefore('=')
        val value = label.substringAfter('=', "").toIntOrNull()
        if (language == VoiceLanguage.PL) return PolishVoiceReplies.commandName(id, value) ?: label
        val cmd = AgentCommandCatalog.ALL.firstOrNull { it.id == id } ?: return label
        return if (value != null) cmd.label.replace("N", value.toString()) else cmd.label
    }
}
