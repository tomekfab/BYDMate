package com.bydmate.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ValidatedActionConstructionTest {

    @Test
    fun onlyValidatedActionKtConstructsTheSubclasses() {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/kotlin").isDirectory }
            ?: error("Cannot find app/src/main/kotlin from ${File(".").canonicalPath}")
        val scanned = File(root, "app/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scan found no sources", scanned.any { it.name == "ValidatedAction.kt" })
        val offenders = scanned
            .filter { it.name != "ValidatedAction.kt" }
            .filter { constructsValidatedAction(it.readText()) }
            .map { it.relativeTo(root).path }
        assertTrue("Constructed outside ValidatedAction.kt: $offenders", offenders.isEmpty())
    }

    // --- the scan itself: genuine violations fail, unrelated same-named types pass ---

    @Test fun `a qualified constructor call is a violation`() {
        assertTrue(constructsValidatedAction("val a = ValidatedAction.Vehicle(\"x\", null)"))
        assertTrue(constructsValidatedAction("val a = ValidatedAction.NavigateQuery(q)"))
        assertTrue(constructsValidatedAction("val a = ValidatedAction.NavigateShortcut (s)"))
    }

    @Test fun `an unqualified constructor imported from ValidatedAction is a violation`() {
        val src = "import com.bydmate.app.agent.ValidatedAction.NavigateQuery\nval a = NavigateQuery(q)"
        assertTrue(constructsValidatedAction(src))
    }

    @Test fun `an import alias of a nested class is a violation`() {
        val src = "import com.bydmate.app.agent.ValidatedAction.Vehicle as Car\nval a = Car(\"x\", 1)"
        assertTrue(constructsValidatedAction(src))
    }

    @Test fun `an import alias of ValidatedAction is a violation`() {
        val src = "import com.bydmate.app.agent.ValidatedAction as VA\nval a = VA.NavigateShortcut(s)"
        assertTrue(constructsValidatedAction(src))
    }

    @Test fun `a star import of the nested classes is a violation`() {
        val src = "import com.bydmate.app.agent.ValidatedAction.*\nval a = NavigateShortcut(s)"
        assertTrue(constructsValidatedAction(src))
    }

    @Test fun `a typealias of a nested class is a violation`() {
        assertTrue(constructsValidatedAction("typealias Nav = ValidatedAction.NavigateQuery"))
    }

    @Test fun `AliasTarget declarations and constructions are not violations`() {
        val declaration = """
            sealed interface AliasTarget {
                data class NavigateQuery(val query: String) : AliasTarget
                data class NavigateShortcut(val which: String) : AliasTarget
            }
        """.trimIndent()
        assertFalse(constructsValidatedAction(declaration))
        assertFalse(constructsValidatedAction("val t = AliasTarget.NavigateQuery(q)\nval u = AliasTarget.NavigateShortcut(w)"))
        assertFalse(constructsValidatedAction("is ValidatedAction.NavigateQuery -> AliasTarget.NavigateQuery(query)"))
        assertFalse(constructsValidatedAction("import com.bydmate.app.agent.ValidatedAction\nval k = x as? ValidatedAction.Vehicle"))
    }

    @Test fun `importing ValidatedAction alone is not a violation`() {
        assertFalse(constructsValidatedAction("import com.bydmate.app.agent.ValidatedAction\nfun f() = Unrelated()"))
    }

    /**
     * Whether [source] constructs a `ValidatedAction` subclass; only `ValidatedAction.kt` may. Catches a qualified
     * call (also through an `import ... as` alias of `ValidatedAction`), an unqualified or aliased call of a nested
     * class imported from `ValidatedAction` (also through a star import), and a `typealias` of one. A same-named type
     * of its own, such as `AliasTarget.NavigateQuery`, is not an import from `ValidatedAction` and does not match.
     */
    private fun constructsValidatedAction(source: String): Boolean {
        val nested = "Vehicle|NavigateQuery|NavigateShortcut"
        val owners = mutableSetOf("ValidatedAction")
        Regex("""import\s+[\w.]*\bValidatedAction\s+as\s+(\w+)""").findAll(source).forEach { owners += it.groupValues[1] }
        val ownerPattern = owners.joinToString("|")
        if (Regex("""(?<![\w])($ownerPattern)\s*\.\s*($nested)\s*\(""").containsMatchIn(source)) return true
        if (Regex("""typealias\s+\w+\s*=\s*[\w.]*ValidatedAction\.($nested)\b""").containsMatchIn(source)) return true
        val names = mutableSetOf<String>()
        Regex("""import\s+[\w.]*\bValidatedAction\.($nested)(?:\s+as\s+(\w+))?""").findAll(source)
            .forEach { names += it.groupValues[2].ifEmpty { it.groupValues[1] } }
        if (Regex("""import\s+[\w.]*\bValidatedAction\.\*""").containsMatchIn(source)) names += nested.split("|")
        return names.any { Regex("""(?<![\w.])$it\s*\(""").containsMatchIn(source) }
    }
}
