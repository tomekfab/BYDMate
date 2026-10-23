package com.bydmate.app.agent

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ContactLookupTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun hasPermission_false_when_not_granted() {
        val lookup = ContactLookup(context)
        assertFalse(lookup.hasPermission())
    }

    @Test fun hasPermission_true_when_granted() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS)
        val lookup = ContactLookup(context)
        assertTrue(lookup.hasPermission())
    }

    // findByName goes through the resolver seam — ContentResolver is never
    // touched in a JVM test.
    @Test fun findByName_delegates_to_resolver_seam() = runTest {
        val lookup = ContactLookup(context)
        lookup.resolver = { query -> listOf(ContactLookup.Match("Znaleziono: $query", "+70000000000")) }
        val matches = lookup.findByName("Mama")
        assertEquals(1, matches.size)
        assertEquals("Znaleziono: Mama", matches[0].name)
    }

    @Test fun findByName_empty_resolver_result_is_empty_list() = runTest {
        val lookup = ContactLookup(context)
        lookup.resolver = { emptyList() }
        assertTrue(lookup.findByName("Nikt").isEmpty())
    }

    // filterRows is the pure per-row logic queryContacts feeds from the cursor — no SQL
    // selection is applied upstream, so Unicode case-insensitivity must hold here.
    @Test fun filterRows_matches_unicode_case_insensitively() {
        val lookup = ContactLookup(context)
        val rows = sequenceOf("Łucja Żółta" to "+70000000001", "Jan Kowalski" to "+70000000002")
        val matches = lookup.filterRows(rows, "łucja")
        assertEquals(1, matches.size)
        assertEquals("Łucja Żółta", matches[0].name)
    }

    @Test fun filterRows_dedupes_by_name_keeping_first_phone() {
        val lookup = ContactLookup(context)
        val rows = sequenceOf("Mama" to "+70000000001", "Mama" to "+70000000002")
        val matches = lookup.filterRows(rows, "Mama")
        assertEquals(1, matches.size)
        assertEquals("+70000000001", matches[0].phone)
    }

    @Test fun filterRows_caps_at_ten_matches() {
        val lookup = ContactLookup(context)
        val rows = (1..20).asSequence().map { "Kontakt $it" to "+7000000000$it" }
        val matches = lookup.filterRows(rows, "Kontakt")
        assertEquals(10, matches.size)
    }

    @Test fun filterRows_skips_null_name_or_phone() {
        val lookup = ContactLookup(context)
        val rows = sequenceOf(null to "+70000000001", "Anonim" to null, "Mama" to "+70000000002")
        val matches = lookup.filterRows(rows, "")
        assertEquals(1, matches.size)
        assertEquals("Mama", matches[0].name)
    }
}
