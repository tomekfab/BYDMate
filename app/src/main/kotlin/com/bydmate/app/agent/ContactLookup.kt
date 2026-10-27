package com.bydmate.app.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.bydmate.app.voice.VoiceContact
import com.bydmate.app.voice.VoiceContacts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local contact-book lookup for the call_contact agent tool and the offline voice call. Phone
 * numbers never leave [Match] into the LLM-facing tool response (AgentTools strips them before
 * responding on ambiguous matches). [all] hands the whole book to the offline voice call, which
 * matches misheard names on the device and sends nothing anywhere.
 */
@Singleton
class ContactLookup @Inject constructor(@ApplicationContext private val context: Context) : VoiceContacts {

    data class Match(val name: String, val phone: String)

    override fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    /** Case-insensitive partial match on display name; ≤10 matches. ContentResolver.query
     *  runs synchronously, so this hops off the caller's thread (same pattern as WeatherClient). */
    suspend fun findByName(query: String): List<Match> = withContext(Dispatchers.IO) { resolver(query) }

    /** Every contact with a phone number, for the offline voice call's fuzzy name match. Stays on
     *  the device: the voice controller matches locally and only the chosen number is dialed. */
    override suspend fun all(): List<VoiceContact> = withContext(Dispatchers.IO) { uniquePerContact(readPhoneBook()) }

    // Internal query seam for JVM tests (ContentResolver is not directly mockable):
    internal var resolver: (String) -> List<Match> = { q -> queryContacts(q) }

    // No SQL selection: SQLite's LIKE is case-sensitive for non-ASCII (Cyrillic), so a
    // "DISPLAY_NAME LIKE ?" prefilter would silently DROP matching rows before Kotlin ever
    // sees them. Contact lists on a head unit are small, so a full scan + Kotlin-side
    // case-insensitive filter is both correct and cheap.
    private fun queryContacts(query: String): List<Match> =
        filterRows(readPhoneBook().asSequence().map { it.name to it.phone }, query)

    /** One row per phone number; the contact's default number first. */
    internal data class PhoneRow(val contactId: Long, val name: String?, val phone: String?)

    private fun readPhoneBook(): List<PhoneRow> {
        val rows = mutableListOf<PhoneRow>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                rows += PhoneRow(cursor.getLong(idIdx), cursor.getString(nameIdx), cursor.getString(numberIdx))
            }
        }
        return rows
    }

    /** One entry per contact (its first number). Two people with the same name stay two entries, so
     *  the voice matcher sees a tie and asks instead of dialing whichever row came first. */
    internal fun uniquePerContact(rows: List<PhoneRow>): List<VoiceContact> {
        val unique = LinkedHashMap<Long, VoiceContact>()
        for (row in rows) {
            if (row.name.isNullOrBlank() || row.phone.isNullOrBlank()) continue
            unique.putIfAbsent(row.contactId, VoiceContact(row.name, row.phone))
        }
        return unique.values.toList()
    }

    /** Pure row filter: case-insensitive contains match, skips null name/phone, dedupes
     *  by display name (first phone wins), caps at [MAX_MATCHES]. Unit-testable without
     *  a ContentResolver. */
    internal fun filterRows(rows: Sequence<Pair<String?, String?>>, query: String): List<Match> {
        val matches = LinkedHashMap<String, String>()
        for ((name, phone) in rows) {
            if (matches.size >= MAX_MATCHES) break
            if (name == null || phone == null) continue
            if (!name.contains(query, ignoreCase = true)) continue
            matches.putIfAbsent(name, phone)
        }
        return matches.map { (name, phone) -> Match(name, phone) }
    }

    companion object {
        private const val MAX_MATCHES = 10
    }
}
