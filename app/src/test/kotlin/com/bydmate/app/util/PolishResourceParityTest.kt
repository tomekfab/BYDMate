package com.bydmate.app.util

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class PolishResourceParityTest {

    @Test
    fun `base and Polish resources have identical names resource types and format signatures`() {
        resourceFiles().forEach { (base, polish) ->
            val baseEntries = readEntries(base)
            val polishEntries = readEntries(polish)

            assertEquals("resource names differ for ${base.name}", baseEntries.keys, polishEntries.keys)
            baseEntries.forEach { (name, baseEntry) ->
                assertEquals(
                    "resource contract differs for $name in ${base.name}",
                    baseEntry,
                    polishEntries.getValue(name),
                )
            }
        }
    }

    @Test
    fun `Polish resources include the required dashboard labels`() {
        val entries = resourceFiles().flatMap { (_, polish) -> readEntries(polish).keys }
        listOf(
            "dashboard_trip1_label",
            "dashboard_trip2_label",
            "dashboard_trip_auto_reset_ac",
            "dashboard_trip_auto_reset_dc",
        ).forEach { name ->
            assertTrue("missing Polish resource: $name", name in entries)
        }
    }

    private fun resourceFiles(): List<Pair<File, File>> = listOf(
        File("src/main/res/values/strings.xml") to File("src/main/res/values-pl/strings.xml"),
        File("src/main/res/values/strings_hybrid_peripherals.xml") to
            File("src/main/res/values-pl/strings_hybrid_peripherals.xml"),
    ).also { files ->
        files.forEach { (base, polish) ->
            check(base.isFile) { "missing base resource file: ${base.path}" }
            check(polish.isFile) { "missing Polish resource file: ${polish.path}" }
        }
    }

    private fun readEntries(file: File): Map<String, ResourceContract> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return (0 until document.documentElement.childNodes.length)
            .mapNotNull { index -> document.documentElement.childNodes.item(index) as? Element }
            .mapNotNull { element ->
                element.getAttribute("name").takeIf { it.isNotBlank() }?.let { name ->
                    name to ResourceContract(
                        type = element.tagName,
                        translatable = element.getAttribute("translatable"),
                        formatted = element.getAttribute("formatted"),
                        itemSignatures = childElements(element).map { item ->
                            item.getAttribute("quantity") to formatSignature(item.textContent)
                        },
                    )
                }
            }
            .toMap()
    }

    private fun childElements(element: Element): List<Element> =
        (0 until element.childNodes.length)
            .mapNotNull { index -> element.childNodes.item(index) as? Element }
            .ifEmpty { listOf(element) }

    private fun formatSignature(text: String): List<String> =
        FORMAT.findAll(text).map { it.value }.toList()

    private data class ResourceContract(
        val type: String,
        val translatable: String,
        val formatted: String,
        val itemSignatures: List<Pair<String, List<String>>>,
    )

    private companion object {
        val FORMAT = Regex("(?<!%)%(?:\\d+\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?[tT]?[a-zA-Z]")
    }
}
