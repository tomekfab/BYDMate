package com.bydmate.app.ui.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Startup wiring contract; dialogs themselves remain available in Settings. */
class AppStartupDialogsTest {
    private val source = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .map { File(it, "app/src/main/kotlin/com/bydmate/app/ui/navigation/AppNavigation.kt") }
        .first { it.isFile }.readText()

    @Test fun startupDoesNotSolicitDonations() {
        assertFalse("Startup must not schedule or show donation prompts", source.contains("Donation", ignoreCase = true))
        assertFalse("Support dialog must be opened only from Settings", source.contains("DonateDialog"))
    }

    @Test fun updateAndPostInstallDialogsRemainAvailable() {
        assertTrue(source.contains("updateChecker.checkForUpdate("))
        assertTrue(source.contains("UpdateDialog("))
        assertTrue(source.contains("PostInstallReminderDialog("))
    }
}
